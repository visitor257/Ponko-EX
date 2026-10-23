# Ponko-EX — VoxCPM2

VoxCPM2 的本地语音合成插件，跑在 Ponko 上。模型在手机 CPU 上推理，
除下载权重外不联网、不上传任何内容。

- 宿主（Ponko）：<https://github.com/visitor257/Ponko>
- 插件开发手册：[`doc/plugin-guide.md`](doc/plugin-guide.md)

[English](README.en.md)

## 这是什么

一个 APK，装了之后 Ponko 的插件列表里多出「VoxCPM2 语音」：

- 输入文字，输出 WAV。三种模式：**语音设计**（用一句话描述音色）、
  **参考音频克隆**（给 5~10 秒录音）、**极致克隆**（录音 + 逐字稿）
- 权重是两份 GGUF（BaseLM + Acoustic），**不在 APK 里**：插件内下载，或自行导入
- 纯 CPU 推理，5 秒语音大约 1~2 分钟；建议 8 GB 内存

它只通过 Ponko 的插件 SDK（AAR）跟宿主通信，不碰宿主源码 —— 宿主升版本时，
只要插件 API 没变就不用动这个插件。

## 下载与安装

**宿主先装**：Ponko 2.0.0 或更新版本，<https://github.com/visitor257/Ponko/releases>。
插件 API 12 起才认这个插件，宿主更老的话装上也连不上。

**插件**：[Releases](https://github.com/visitor257/Ponko-EX/releases) 页下载
`Ponko-EX_VoxCPM2_0.9.0.apk`，当普通 APK 安装。

要求与注意：

- Android 9+（minSdk 28）、arm64，**建议 8 GB 内存**
- 发布包已用**宿主的密钥**签好 v1 + v2 + v3。宿主用 `protectionLevel="signature"`
  的权限保护插件绑定，签名不同 `bindService` 直接抛 `SecurityException` ——
  自己重编的话见下面「签名」一节
- **权重不在 APK 里**。首次使用到插件「模型」页下载：标准 Q8 约 3.55 GB /
  精简 Q4 约 2.83 GB，支持断点续传，三个镜像（HuggingFace / hf-mirror / 魔搭）；
  也可以自己导入两份 GGUF。下载要留出「文件大小 + 同样大小的内存副本」的空间
- 5 秒语音大约 1~2 分钟，合成期间插件会挂前台服务，可以切出去

装完的用法：Ponko → 插件 → VoxCPM2 → 「模型」页备好权重 → **加载模型** → **开始推理**。

校验：

```bat
certutil -hashfile Ponko-EX_VoxCPM2_0.9.0.apk SHA256
apksigner verify --verbose --min-sdk-version 23 Ponko-EX_VoxCPM2_0.9.0.apk
```

0.9.0 的 sha256 是 `a44f55c8b2729990841f5f63e386e54c786648323e1e23338b8ff80c8b06a9c8`，
4,699,635 字节。

## 功能

| 页 | 内容 |
|---|---|
| 模型 | 权重状态、加载/释放、本机导入两份 GGUF、下载（版本 × 镜像）、空间占用 |
| 参数 | 语音描述、CFG 引导强度、扩散步数、种子、参考音频、逐字稿 |
| 结果 | 本次合成结果 + 播放条，下面按时间倒序列出历史，每条各有播放/保存 |

其它：

- **三种模式**：语音设计 / 参考音频克隆 / 极致克隆
- **两个量化**：标准 Q8（约 3.55 GB）/ 精简 Q4（约 2.83 GB）
- **下载管理**：断点续传、三个镜像、取消后可从断点接着下
- **前台服务**：合成期间不挂后台，通知栏显示当前阶段
- **中英双语**：跟**宿主**的界面语言走（宿主没给语言时退回系统语言）
- **引擎失败会说话**：加载失败直接在那行写出原因（内存不足 / 没有权重 /
  原生初始化失败 / 意外异常），不再只是"未加载"

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

改 `plugins/voxcpm2/build.gradle.kts` 里的 `versionCode` / `versionName` → 构建 → 签名 →
传 Release。三处必须对得上：tag 指向的提交、Release 附件、本地产物。

- tag 用 `voxcpm2-<版本>`，附件名用 `Ponko-EX_VoxCPM2_<版本>.apk`
  —— 固定叫 `release.apk` 会让几个版本在下载记录里分不开
- 传完回读附件的 `digest`，跟本地 `sha256` 比。**字节数对上不算数**，
  不同构建经常字节数完全相同

## 代码结构

```
plugins/voxcpm2/
├── build.gradle.kts                    版本号、依赖、JNI 库打包
└── src/main/
    ├── AndroidManifest.xml             服务声明、权限、ponko.actions / ponko.about
    ├── java/com/litertchat/plugin/voxcpm2/
    │   ├── VoxCpm2PluginService.kt     插件主体：界面 schema、交互、合成流程、文案
    │   ├── VoxCpm2Bridge.kt            JNI 桥（加载 / 合成 / 释放 / 错误串）
    │   ├── ModelStore.kt               权重：版本与镜像定义、下载、导入、校验
    │   ├── AudioUtil.kt                参考音频解码、重采样、WAV 封装
    │   └── VoxCpm2Activator.kt         无界面激活组件，清系统 stopped 状态
    ├── jniLibs/arm64-v8a/libvoxcpm2jni.so
    └── res/
        ├── values/                     英文（默认语言）
        ├── values-zh/                  中文
        ├── raw/about.txt              卡片「关于」的正文（英）
        └── raw-zh/about.txt           同上（中）
```

`VoxCpm2PluginService.kt` 是主文件（约 1800 行）：界面用 schema 描述（宿主负责渲染），
文案全部走 `res/values*`，日志保持中文 —— 那是诊断输出，单一语言便于 grep。

## 许可

[MIT](LICENSE) —— 插件代码和文档。

```text
Copyright (c) 2026 visitor257
```

**模型权重不在这个许可范围内。** 权重不随本仓库或 APK 分发，各自遵循其来源声明的许可：
官方 VoxCPM2 权重是 Apache-2.0，精简 Q4 是第三方转换、其仓库未声明许可。
商用前请自己确认。
