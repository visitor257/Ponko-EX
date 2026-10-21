# 第三方组件与许可（VoxCPM2 插件）

本插件按 **MIT** 发布（见仓库根 `LICENSE`）。APK 里只含**自己写的代码**和**一个推理引擎**；
权重不随包分发。

---

## 1. 推理引擎：llama.cpp-omni / ggml

**MIT** — `Copyright (c) 2023-2026 The ggml authors`

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

`libvoxcpm2jni.so` 把 `voxcpm2_runtime` 与 ggml 静态链接进来，所以上面这份声明
**必须随插件一起分发**。它同时出现在插件界面的「许可」一节里 —— 那是给用户看的，这里是给分发者的。

改动说明（Apache-2.0 那套要求里的一条，顺手一起做）：
`llama-moe-slot.cpp` 里上游无条件调用的 `fcntl(fd, F_NOCACHE, …)` 是 macOS 专有，
Android 编不过，已改为 `#if defined(__APPLE__)`。VoxCPM2 是 dense 模型，不走 MoE 路径。

---

## 2. 模型权重：VoxCPM2

**Apache-2.0** — 版权归 OpenBMB（<https://www.modelscope.cn/models/OpenBMB/VoxCPM2>）
官方模型卡原话：*Released under the Apache-2.0 license, free for commercial use.*

**权重不随 APK 分发**：用户在插件界面里点「下载权重」，由插件从魔搭拉取到自己的私有目录。
这一步是用户自己获取，不构成插件的再分发。

插件里提供两个下载源，界面会显示各自的许可状况：

| 源 | 仓库 | 许可声明 |
|---|---|---|
| 标准源（默认） | 魔搭 `DennisHuang/VoxCPM2-GGUF` | **apache-2.0** |
| 精简源 | 魔搭 `zerg13/MiniCPM-V-Apps-gguf` | 仓库**未填** license 字段 |

精简源是第三方转的 GGUF，上游权重仍受 Apache-2.0 约束，但归属义务落在转换者身上。
所以默认不选它 —— 只在用户主动切换、且只为自己使用时才用。

---

## 3. 界面与 SDK

- 宿主渲染的界面来自 `com.litertchat.ponko:plugin-sdk`（Ponko 项目，MIT）
- 插件不含任何布局代码、不含美术资源；图标是自己画的 XML

---

## 4. 用在别处之前请务必读这一段

Apache-2.0 允许商用，MIT 也允许，所以**协议上**没问题。真正的风险不在代码：

1. **声音权益**（《民法典》第 1023 条）：自然人声音参照肖像权保护。
   拿他人（尤其公众人物）的音频做 zero-shot 克隆，未经同意即可能侵权。
2. **深度合成标识**（《互联网信息服务深度合成管理规定》，2023-01-10 施行）：
   生成内容需要可识别。个人离线自用不涉及；对外发布或提供生成服务时要显著标识。

*本文件是工程内的许可说明，不构成法律意见。*
