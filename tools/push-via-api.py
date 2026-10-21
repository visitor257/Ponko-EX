#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# Ponko-EX · (c) 2026 visitor257 · MIT License
"""
把本地分支推到 GitHub（git push 在本机不通时的替代方案）。

## 为什么不用 git push

本机 `git push` / `fetch` / `clone` 一律 exit 128、无输出，但
`api.github.com` 和 `git ls-remote` 正常。所以只能走 **Git Data API**：

    blobs（base64） → trees → commits → PATCH refs/heads/<branch>

代价：每次推送的 commit sha 与本地不同（API 重建的提交对象没有本地的时间戳
签名信息，parent 也不同）。**内容完全一致，只是 sha 不再对齐。**

## 两条分支怎么推

按给定顺序推。第一条分支从空仓库开始（bootstrap），后面的分支只推
「上一条分支里没有的提交」（`git rev-list A..B`）。

这会把本地的 merge 结构**线性化** —— 推送 API 一次只能挂一个 parent。
所以 `Ponko-EX_test` 在远端看起来是 main 的线性延续，而不是从某个点分叉出来。
内容逐步正确，图的形状不对。这个仓库只有几个提交又是新建的，不值得为它
再写一套多 parent 的逻辑。

## 用法

    python tools/push-via-api.py                  # 干跑：只列出要推什么
    python tools/push-via-api.py --apply          # 真正推送
    python tools/push-via-api.py --apply --create-repo   # 仓库不存在时顺便创建

环境变量：
    GH_TOKEN       直接给 token，优先级最高
    GH_TOKEN_FILE  从文件读；默认 ~/.ponko_gh_token
    GH_REPO        默认 visitor257/Ponko-EX
    GH_BRANCHES    默认 main,Ponko-EX_test

token 只放在本机、绝不入库（`.gitignore` 也挡了常见密钥文件名）。
"""

import base64
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

REPO = os.environ.get("GH_REPO", "visitor257/Ponko-EX")
BRANCHES = [b.strip() for b in
            os.environ.get("GH_BRANCHES", "main,Ponko-EX_test").split(",") if b.strip()]
TOKEN_FILE = os.environ.get(
    "GH_TOKEN_FILE", os.path.join(os.path.expanduser("~"), ".ponko_gh_token"))

APPLY = "--apply" in sys.argv
CREATE_REPO = "--create-repo" in sys.argv

GIT = shutil.which("git") or "git"
NEEDS_SHELL = GIT.lower().endswith((".cmd", ".bat"))


def load_token():
    if os.environ.get("GH_TOKEN"):
        return os.environ["GH_TOKEN"]
    with open(TOKEN_FILE, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    # 文件里可能还有别的内容（第二行 ddl: 之类），只取形如 token 的那一串
    m = re.search(r"gh[pousr]_[A-Za-z0-9]+", raw)
    if not m:
        raise SystemExit("找不到 GitHub token：%s" % TOKEN_FILE)
    return m.group(0)


TK = load_token()


def api(method, path, data=None, attempts=5, allow_404=False, empty_ok=False):
    """请求 GitHub API。

    读响应有两点讲究（踩过）：
      1. **分块读**，不要 `r.read()` 一次拉完 —— 本机链路上大响应会被截断
         （IncompleteRead，每次差的字节数都不同，重试必然同样失败）。
      2. **接受 gzip** —— 加这个头之后 443 KB 的树响应 8.2s → 1.0s，且不再截断。
    """
    url = "https://api.github.com" + path
    body = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
    last = None
    for i in range(attempts):
        req = urllib.request.Request(url, data=body, method=method)
        req.add_header("Authorization", "token " + TK)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("Accept-Encoding", "gzip")
        req.add_header("User-Agent", "ponko-ex-push")
        req.add_header("Connection", "close")
        if body:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                gz = (r.headers.get("Content-Encoding") or "").lower() == "gzip"
                chunks = []
                while True:
                    b = r.read(64 * 1024)
                    if not b:
                        break
                    chunks.append(b)
                raw = b"".join(chunks)
                if gz:
                    raw = gzip.decompress(raw)
                return json.loads(raw.decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read()
            # 请求带了 Accept-Encoding: gzip，错误响应体也可能是压缩的 ——
            # 不先解开就是一堆乱码，看不出到底哪里错了
            if (e.headers.get("Content-Encoding") or "").lower() == "gzip":
                try:
                    raw = gzip.decompress(raw)
                except Exception:
                    pass
            txt = raw.decode("utf-8", "replace")
            if e.code == 404 and allow_404:
                return None
            if e.code == 409:
                # 两种含义：
                #   · 空仓库 —— 查 ref / tree 会返回 409「Git Repository is empty」，
                #     这是**正常状态**，不是故障，调用方按"什么都没有"处理
                #   · 并发更新冲突（PATCH ref 时 fast-forward 失败）
                # 只有后者值得重试，前者重试 5 次也不会变。
                if empty_ok:
                    return None
                if attempts > 1:
                    print("   .. HTTP 409，重试")
                    time.sleep(2)
                    attempts -= 1
                    continue
            if e.code >= 500 and i < attempts - 1:
                print("   .. HTTP %d，退避重试" % e.code)
                time.sleep(2 + 3 * i)
                last = RuntimeError("HTTP %d %s" % (e.code, txt[:300]))
                continue
            print("!! HTTP %d %s" % (e.code, txt[:600]))
            raise
        except Exception as e:
            last = e
            print("   .. 第 %d/%d 次失败：%s: %s" % (i + 1, attempts, type(e).__name__, e))
            time.sleep(2 + 3 * i)
    raise last if last is not None else RuntimeError("请求失败：%s %s" % (method, path))


def git(*args, binary=False):
    cmd = '"' + GIT + '" ' + " ".join('"' + str(a) + '"' for a in args)
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, shell=NEEDS_SHELL)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace").strip())
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def ident(s):
    m = re.match(r"^(.*) <(.*)> (\d+) ([+-]\d{4})$", s)
    name, email, ts, tz = m.groups()
    off = timezone(timedelta(hours=int(tz[0:3]), minutes=int(tz[3:5])))
    return {"name": name, "email": email,
            "date": datetime.fromtimestamp(int(ts), tz=off).isoformat()}


def ensure_repo():
    if api("GET", "/repos/%s" % REPO, allow_404=True):
        print("仓库已存在：%s" % REPO)
        return
    if not CREATE_REPO:
        raise SystemExit(
            "仓库 %s 不存在。\n"
            "确认要新建的话加 --create-repo（会建成 public 仓库，不发 release）" % REPO)
    print("创建仓库：%s" % REPO)
    api("POST", "/user/repos", {
        "name": REPO.split("/")[1],
        "description": "Plugin project for Ponko — SDK-based plugin APKs. "
                       "Branches: main (docs) / Ponko-EX_test (sample plugin).",
        "private": False,
        "has_issues": True,
        "has_wiki": False,
        "has_projects": False,
        "auto_init": False,
    })


def remote_head(branch):
    # empty_ok：仓库刚建好、还是空的时候，这个接口回 409 而不是 404
    ref = api("GET", "/repos/%s/git/ref/heads/%s" % (REPO, branch),
              allow_404=True, empty_ok=True)
    return ref["object"]["sha"] if ref else None


def ls_tree(rev, dirs=False):
    """列出某个提交的文件，返回 {路径: (mode, blob sha)}。

    ## 为什么必须用 `-z`

    `git ls-tree` 默认对**非 ASCII 路径**加引号并转义成八进制，输出的是：

        "log/2026-10-21-\\344\\273\\216\\351\\233\\266....md"

    把这一串当成路径交给 API，远端就会建出一个**名字里带字面反斜杠和数字**的
    目录/文件，而真正的 `log/` 里空着 —— 看起来"推成功了"，实际是坏数据
    （实际踩过：远端顶层多出一个叫 `"log` 的目录）。

    `-z` 用 NUL 分隔，**不加引号、不转义**，路径就是原始 UTF-8 字节。
    """
    out = git("ls-tree", "-r", "-z", *(["-t"] if dirs else []), rev, binary=True)
    items = {}
    for rec in out.split(b"\0"):
        if not rec:
            continue
        meta, path = rec.split(b"\t", 1)
        mode, _type, sha = meta.split()
        items[path.decode("utf-8")] = (mode.decode(), sha.decode())
    return items


def bootstrap_commit():
    """在空仓库里造出第一个提交，返回它的 sha。

    ## 为什么非要有这一步

    **空仓库上 Git Data API 全部回 409**（"Git Repository is empty"）——
    `POST /git/blobs` 也不行。必须先有一个提交，哪怕只有一个文件。

    所以这里用 Contents API（它是唯二能在空仓库上工作的接口）建一个占位文件，
    拿到初始 commit。随后正常流程会把完整的第一个提交接在它后面，
    并且因为本地没有这个占位文件，它会被当成"远端多出来的"删掉。

    结果：远端历史多一条 bootstrap 提交，但**两条分支的 HEAD 内容完全正确**。
    """
    r = api("PUT", "/repos/%s/contents/.bootstrap" % REPO, {
        "message": "bootstrap: initialise the repository\n\n"
                   "Empty repos reject the Git Data API with 409, so this single\n"
                   "placeholder commit exists only to create the first object.\n"
                   "It is removed by the next commit.",
        "content": base64.b64encode(b"bootstrap\n").decode(),
    })
    return r["commit"]["sha"]


def push_commit(sha, parent, branch, is_last):
    """把本地某个提交的**完整文件树**推成远端一个提交。

    不是推 diff —— 本机 git 与远端长期不同步时，只推 diff 会把中间的提交静默丢掉。
    """
    info = git("cat-file", "-p", sha)
    author = re.search(r"^author (.+)$", info, re.M).group(1)
    committer = re.search(r"^committer (.+)$", info, re.M).group(1)
    message = info.split("\n\n", 1)[1]

    local_files = ls_tree(sha)

    base_tree = None
    remote_files = {}
    if not APPLY:
        # 干跑：远端可能还不存在、也可能没有这个 parent，
        # 一律不请求远端 —— 只报这个提交带了多少文件
        msg_head = message.strip().splitlines()[0][:72] if message.strip() else "(no message)"
        print("  %s  %d 个文件（干跑，未比对远端）  %s"
              % (sha[:7], len(local_files), msg_head))
        return sha
    if parent:
        base_tree = api("GET", "/repos/%s/git/commits/%s" % (REPO, parent))["tree"]["sha"]
        for e in api("GET", "/repos/%s/git/trees/%s?recursive=1" % (REPO, parent))["tree"]:
            if e["type"] == "blob":
                remote_files[e["path"]] = (e["mode"], e["sha"])

    entries = []
    changed = 0
    for path, (mode, blob) in sorted(local_files.items()):
        if remote_files.get(path) == (mode, blob):
            continue
        data = git("cat-file", "blob", blob, binary=True)
        new = api("POST", "/repos/%s/git/blobs" % REPO,
                  {"content": base64.b64encode(data).decode(), "encoding": "base64"})["sha"]
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": new})
        changed += 1

    for path in sorted(remote_files):
        if path not in local_files:
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            changed += 1

    msg_head = message.strip().splitlines()[0][:72] if message.strip() else "(no message)"
    print("  %s  %d 个文件变更  %s" % (sha[:7], changed, msg_head))
    if not entries and parent:
        print("     内容无差异，跳过")
        return parent

    tree_body = {"tree": entries}
    if base_tree:
        tree_body["base_tree"] = base_tree
    tree = api("POST", "/repos/%s/git/trees" % REPO, tree_body)["sha"]

    commit_body = {
        "message": message,
        "tree": tree,
        "parents": [parent] if parent else [],
        "author": ident(author),
        "committer": ident(committer),
    }
    new_commit = api("POST", "/repos/%s/git/commits" % REPO, commit_body)["sha"]

    if parent is None:
        api("POST", "/repos/%s/git/refs" % REPO,
            {"ref": "refs/heads/%s" % branch, "sha": new_commit})
    else:
        api("PATCH", "/repos/%s/git/refs/heads/%s" % (REPO, branch),
            {"sha": new_commit, "force": False})
    return new_commit


def main():
    print("仓库：%s" % REPO)
    print("模式：%s" % ("APPLY（真正推送）" if APPLY else "DRY-RUN（只打印）"))
    print()

    if APPLY:
        ensure_repo()
    else:
        print("仓库存在：%s" % bool(api("GET", "/repos/%s" % REPO, allow_404=True)))
        print()

    prev_branch = None
    prev_remote = None
    for branch in BRANCHES:
        local_head = git("rev-parse", branch).strip()
        print("分支 %s（本地 %s）" % (branch, local_head[:7]))

        if prev_branch:
            rng = "%s..%s" % (prev_branch, branch)
        else:
            rng = branch
        commits = [c for c in
                   git("rev-list", "--reverse", "--topo-order", rng).split() if c]

        parent = remote_head(branch) if APPLY else None
        create_ref = False
        if APPLY:
            if parent:
                print("  远端已有，基于 %s 继续" % parent[:7])
                # 远端 sha 与本地不同（API 重建的提交），没法直接用 sha 比对。
                # 但 **tree sha 是内容哈希** —— 找到本地树等于远端树的那个提交，
                # 它之后的就是真正要推的。
                #
                # 不做这一步的后果（踩过）：远端明明已经有这几个提交了，
                # 脚本还是从第一个开始重推，历史里同一批改动出现两遍。
                rtree = api("GET", "/repos/%s/git/commits/%s" % (REPO, parent))["tree"]["sha"]
                upto = None
                for idx, c in enumerate(commits):
                    if git("rev-parse", c + "^{tree}").strip() == rtree:
                        upto = idx
                if upto is None:
                    print("   !! 远端树在本地找不到对应提交（远端被人改过？），将全量重推")
                else:
                    if upto + 1 < len(commits):
                        print("  本地 %s 与远端一致，从它的下一个提交开始"
                              % commits[upto][:7])
                    commits = commits[upto + 1:]
            elif prev_remote:
                # 非默认分支：不能再用 bootstrap（Contents API 只会往**默认分支**
                # 上提交，会污染 main）。改为从上一分支的远端 HEAD 派生一个 ref。
                parent = prev_remote
                create_ref = True
                print("  从上一分支 %s 派生" % parent[:7])
            else:
                parent = bootstrap_commit()
                create_ref = True
                print("  仓库为空，bootstrap 提交 %s" % parent[:7])

            if create_ref:
                api("POST", "/repos/%s/git/refs" % REPO,
                    {"ref": "refs/heads/%s" % branch, "sha": parent})

        for i, c in enumerate(commits):
            parent = push_commit(c, parent, branch, i == len(commits) - 1)
        if not commits:
            print("  （没有新提交）")
        print()

        prev_branch = branch
        prev_remote = parent

    if APPLY:
        print("完成。")
        for branch in BRANCHES:
            h = remote_head(branch)
            print("  %-16s %s" % (branch, h))
        print()
        print("注意：远端 sha 与本地不同（API 重建的提交），内容一致即正常。")
        print("Release 未发布 —— 需要发布时单独做。")
    else:
        print("干跑结束。加 --apply 真正推送。")


if __name__ == "__main__":
    main()
