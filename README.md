# Ponko-EX

![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![minSdk](https://img.shields.io/badge/minSdk-28-orange.svg) ![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey.svg) ![Host](https://img.shields.io/badge/host-Ponko%202.0.0%2B-informational) ![API](https://img.shields.io/badge/plugin%20API-v12-blueviolet)

> Ponko 的插件工程 —— 语音合成这类能力放在插件里，不进主 App。
>
> **与宿主 [Ponko](https://github.com/visitor257/Ponko) 完全独立**：这里只依赖插件 SDK 的 AAR，不碰宿主任何实现代码。

[English](README.en.md) · 中文

**本仓库只放文档和约定，不放安装说明。** 插件的获取与安装由各插件线自己负责；
这里管的是「插件怎么写、接口长什么样、为什么这么设计」。

- 插件开发手册：[`doc/plugin-guide.md`](doc/plugin-guide.md)
- 设计与踩坑记录：[`log/`](log/)

## 目录

- [插件一览](#插件一览)
- [前置：先发布插件 SDK](#前置先发布插件-sdk)
- [构建](#构建)
- [签名（关键）](#签名关键)
- [发布](#发布)
- [一条线一件事](#一条线一件事)
- [仓库结构](#仓库结构)
- [文档在哪](#文档在哪)
- [许可](#许可)

---

## 插件一览

| 插件 | 做什么 | 分支 | 版本 |
|---|---|---|---|
| **VoxCPM2 语音合成** | 本地文本转语音，纯手机 CPU 推理 | `Ponko-EX_VoxCPM2` | `0.9.0` |
| **Echo** | 不做什么，只为验证插件链路、当模板 | `Ponko-EX_test` | — |

---

# 开发

## 前置：先发布插件 SDK

插件依赖 `com.litertchat.ponko:plugin-sdk` 的 AAR，它由**宿主**发布到本地 maven 仓库：

```bat
:: 在宿主的构建沙箱里跑
tools\build-android.bat :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository
```

产物落在本地 maven 仓库（路径见 `settings.gradle.kts` 里的 `maven { url = ... }`）。

> ⚠ **AIDL 或 Parcelable 一改就必须重新发布**，否则这边编出来的插件用的还是旧接口。
> 症状是运行时 `NoSuchMethodError`，或者 Parcel 读写错位读出乱值 —— 很难查。
>
> 反过来也一样：**只重编宿主不重编插件是没用的**，插件消费的是 maven 里那份 AAR。

## 构建

工程路径是纯 ASCII，AGP 直接接受，所以**就地构建**，不需要同步到沙箱。

```bat
tools\build.bat :plugins:voxcpm2:assembleRelease
```

产物：`plugins\voxcpm2\build\outputs\apk\release\voxcpm2-release-unsigned.apk`

`tools\build.bat` 里面做了三件事，缺一个都编不出来：

1. 载入 MSVC 环境（`vcvars64.bat`）—— 没有它 CMake 找不到 build program
2. 指定 JDK 17 / Android SDK
3. 调 gradle，带 `--no-daemon`

**别用 `gradlew`**：本机 wrapper 的 dists 是空的，跑起来只会卡住或报错。

> ⚠ `.bat` 必须**纯 ASCII**。cmd.exe 按 GBK 解析 .bat，中文注释会被拆行、
> 打断 if 块，历史上真出现过脚本自己刷自己 22 MB 日志。

## 签名（关键）

宿主用 `protectionLevel="signature"` 的权限保护插件绑定，
**插件必须用同一把密钥签名**，否则 `bindService` 直接抛 `SecurityException`。

用宿主项目里的签名脚本（它固定 v1 + v2 + v3 三方案全开）：

```powershell
powershell -ExecutionPolicy Bypass -File <宿主>\tools\sign-apk.ps1 `
  -Apk <unsigned.apk> -Out <signed.apk>
```

密钥和口令都**不进仓库**：`.gitignore` 已经挡住 `*.jks` / `*.keystore` /
`keystore.properties`。脚本从 `keystore.properties` 或环境变量读口令。

验证签名：

```bat
apksigner verify --verbose --min-sdk-version 23 <apk>
:: v1 / v2 / v3 三项都应为 true
```

## 发布

改版本号 → 构建 → 签名 → 传 Release。

三处必须对得上：**tag 指向的提交、Release 附件、本地产物**。

附件名带版本（`Ponko-EX_VoxCPM2_0.9.0.apk`），别用固定的 `release.apk`，
否则几个版本在下载记录里分不开。

---

# 仓库

## 一条线一件事

这个仓库用**分支**分线。同一份工作树，`git checkout` 切过去就是另一条线。

| 分支 | 内容 | 用途 |
|---|---|---|
| `main` | 本 README、`doc/`、`log/` + 构建骨架，**没有任何插件模块** | 主线。文档和约定都住这儿 |
| `Ponko-EX_test` | 上面全部 + `plugins/echo`（Echo 测试插件） | 测试线。验证链路、当模板 |
| `Ponko-EX_VoxCPM2` | 上面全部 + `plugins/voxcpm2`（VoxCPM2 语音合成） | 插件线。发布版从这儿出 |

```bash
git checkout Ponko-EX_test     # 去测试线，要编 Echo 就切过去
git checkout main              # 回主线，只有文档
```

**为什么插件模块不在主线**：主线留给文档和约定，模型家族各占一条线
（VoxCPM2 等）。测试插件是脚手架 —— 它验完链路、被抄成模板之后就该让位，
不该长期占着主线的位置。

> ⚠ 在 `main` 上跑构建会提示「没有插件模块」。那是**预期行为**，不是坏了。

## 仓库结构

```
Ponko-EX/
├── README.md / README.en.md        本文件（中 / 英）
├── doc/
│   ├── plugin-guide.md             插件开发手册
│   └── plugin-guide.en.md          同上（英）
├── log/
│   ├── README.md                   这个文件夹是什么
│   └── 2026-10-21-*.md             开发过程中的问题记录（中文，不正式）
├── settings.gradle.kts             本地 maven 仓库指向插件 SDK
├── build.gradle.kts                KGP 版本必须与宿主一致
├── gradle.properties
├── tools/build.bat                 纯 ASCII；载入 MSVC 环境后**就地构建**
├── tools/push-via-api.py           git push 不通时用 REST API 推送
└── plugins/                        只在插件线上存在
    ├── echo/                       Ponko-EX_test
    └── voxcpm2/                    Ponko-EX_VoxCPM2
```

## 文档在哪

| 想知道 | 看 |
|---|---|
| 怎么写一个插件、接口长什么样、UI 配置怎么给 | [`doc/plugin-guide.md`](doc/plugin-guide.md) |
| 为什么某个东西是这么设计的、踩过哪些坑 | [`log/`](log/) |

手册是**怎么用**，`log/` 是**为什么**。遇到"照着写却跑不通"的情况，
先翻 `log/` —— 大概率有人踩过了。

## 许可

[MIT](LICENSE) —— 代码、文档、示例插件都在这个许可下。

```text
Copyright (c) 2026 visitor257
```

也就是说：你可以拿去用、改、商用、闭源分发，只要保留版权声明。
**不提供任何担保** —— 插件跑在你的设备上、调你的模型，后果自负。

> 宿主 Ponko 用的是同一条许可，但两个仓库各自独立：宿主的 `LICENSE`
> 不覆盖这里的文件，反之亦然。

**模型权重不在这个许可范围内。** 权重不随本仓库或 APK 分发，各自遵循其来源声明的许可：
官方 VoxCPM2 权重是 Apache-2.0，精简 Q4 是第三方转换、其仓库未声明许可。
商用前请自己确认。
