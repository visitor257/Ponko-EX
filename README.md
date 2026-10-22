# Ponko-EX · VoxCPM2

给 [Ponko](https://github.com/visitor257/Ponko) 用的语音合成插件：
把 [VoxCPM2](https://modelscope.cn/models/OpenBMB/VoxCPM2)（OpenBMB，2B）放进手机里**离线**跑。

[English](README.en.md)

**4.6 MB 的插件 + 3.5 GB 权重（首次自己下）= 一个不联网的 TTS。**
没有服务端、没有 API key、没有遥测。飞行模式也能念。

|  |  |
|---|---|
| 宿主 | Ponko **2.0.0** 及以上（主线 `LiteRT-Chat`） |
| 平台 | Android 9+（minSdk 28）· **arm64-v8a only** |
| 内核 | llama.cpp-omni 的 `voxcpm2_runtime` + 自写 JNI（静态链接，5.8 MB） |
| 许可 | 代码 MIT · VoxCPM2 权重 Apache-2.0（见 [NOTICE.md](plugins/voxcpm2/NOTICE.md)） |

## 能做什么

- **30 种语言 + 9 种中文方言**，不用打语种标签，模型自己认
- **音色设计**：`(年轻女性，声音温柔甜美)你好呀` —— 括号里写描述，凭空造一个不存在的声音
- **零样本克隆**：一段 5～10 秒干净音频，复制音色
- **极致克隆**：参考音频 + 它的逐字稿，连停顿和语气一起还原
- **48 kHz 输出**，AudioVAE 解码端直接出，不做二次超分

## 装

1. 装 `Ponko-EX_VoxCPM2_release.apk`
2. 打开 Ponko →「插件」→ 启用「VoxCPM2」
3. 插件页「1 · 权重」点「下载全部权重」（3.55 GB，支持断点续传，**建议 Wi-Fi**）
4. 点页面上方的「加载模型」（3.5 GB 权重约 10～30 秒），状态行变成「已就绪」就能合成

> 加载这一步**要自己点**。宿主从 2.0.0 起不再替所有插件自动加载 ——
> 只有声明了 `AUTOLOAD_ENGINE` 的轻量插件才连上即加载，这个插件要占 3.5 GB，
> 故意没声明。直接点「合成语音」也行，它会先加载再合成，只是看不到加载进度。
> 想腾内存随时点「释放模型」。

> 功能区是**一个动作一个按钮**：`加载/释放模型 · 合成语音 · 播放 · 停止 · 保存`。
> **「合成语音」只跑推理、产出文件，不出声** —— 合成完再点「播放」才听得到（宿主的提示里也写了）。
> 「播放」直接放上一次的文件，**不重新推理**；手机上一段 5 秒语音要等 1～2 分钟，
> 想再听一遍别去点「合成语音」。
>
> 按钮叫什么由插件定（SDK v3 的 `buildActionLabels()`）。宿主默认那套词是
> 「加载引擎 / 卸载引擎 / 合成 / 播放 / 停止 / 保存」，这里换了两处：
> 加载的是模型不是引擎；卸载之后文件还在磁盘上（所以叫「释放」）。
> 播放 / 停止 / 保存用宿主默认。

> **装之前先确认宿主是新的。** 这版插件报 SDK v3（按钮文案能力），
> 老宿主（`API_MAX_SUPPORTED = 2` 那版）会直接拒绝绑定并提示
> 「插件 SDK v3 不兼容」，不会降级成默认按钮。宿主和插件要一起更新。

> **签名必须和宿主一致。** 宿主用 `protectionLevel="signature"` 保护插件绑定，
> 密钥不一致时 `bindService` 直接抛 `SecurityException`，界面上只会显示「绑定失败」。
> 这个包是用 Ponko 主线那把 `ponko-release.jks` 签的；换 `hw` 线的宿主装就要重签。

## 用

### 宿主侧：合成参数

Ponko 2.0.0 的插件页给了一块「合成参数」，这些值会真的进 `SynthesisRequest`：

| 参数 | 说明 |
|---|---|
| 文本 | 没有硬性字数上限（插件不截断）。手机 RTF 10～30，40 字已经要等几分钟 |
| 模式 | 纯 TTS / 克隆 / 极致克隆 |
| 参考音频 | 从文件选，宿主 open 成 FD 交过来；插件自己解码、重采样到 16 kHz |
| 逐字稿 | 仅「极致克隆」必填 |
| 种子 | `0` = 随机。固定它才能复现同一段演绎 |
| 推理线程 | 自动（核数-1）/ 2 / 4 / 6，改动在下次「加载模型」时生效 |

生成中点「停止」会真的打断：插件把宿主的取消意图转成 `nativeCancel()`，
原生在音频回调里逐块检查，一般几秒内退出（取决于当时卡在哪个阶段）。

### 插件侧：自己的设置

宿主不传 `cfg` / `timesteps` —— 合理范围强依赖模型，这两项由插件自己在界面里给：

| 项 | 默认 | 范围 | 说明 |
|---|---|---|---|
| CFG 引导强度 | 2.0 | 1.0–4.0 | 越大越贴文本，越小越自由 |
| 扩散步数 | 10 | 4–24 | 越多越细，但线性变慢 |
| 语音设计描述 | 空 | — | 等价于宿主传 `voiceDescription`；两边都空就不加前缀 |

插件界面按使用顺序排：顶部「运行状态」（引擎 / 权重 / 存储 / 设备 / 上次合成 / 提示），
然后是「1 · 权重」和「2 · 合成参数」两个分区，最后是一块可折叠的日志
（最近 24 行 + 清空）。

## 三种模式

| 模式 | 需要什么 | 适合 |
|---|---|---|
| 纯 TTS（0） | 文本 | 通用朗读，默认 |
| 克隆（1） | 参考音频 | 想要某个音色，但内容随便说 |
| 极致克隆（2） | 参考音频 + 逐字稿 | 复刻一段演绎：节奏、停顿、语气 |

**参考音频怎么选**：5～10 秒、单人、无背景音乐、无混响。wav / mp3 / m4a / flac
都行（插件用 `MediaCodec` 解码，再重采样到 16 kHz）。

## 权重

不进 APK：3.5 GB 没法发版。首次使用时下到插件自己的外部私有目录：

```
Android/data/com.litertchat.plugin.voxcpm2/files/models/
```

| 源 | BaseLM | Acoustic | 合计 | 备注 |
|---|---|---|---|---|
| **标准源**（默认） | Q8_0 1.73 GB | F16 1.83 GB | **3.55 GB** | 仓库明确声明 `apache-2.0` |
| 精简源 | Q4_K_M 1.00 GB | F16 1.83 GB | **2.83 GB** | 第三方 GGUF 转换件，**仓库未声明许可** |

两个源都走魔搭（ModelScope），都支持 `Range` 断点续传 —— 中断了再点一次接着下。
默认选标准源不是因为它音质好多少，是因为**归属清楚**：将来要分发带权重的整包时，
不需要为许可再解释一遍。

两套里的 `Acoustic` 是同一个文件；**`BaseLM` 和 `Acoustic` 必须来自同一次发布**，
混搭会出噪声。

## 性能预期

| | |
|---|---|
| RTF | 手机纯 CPU 约 **10～30**（桌面 4090 是 0.3） |
| 5 秒语音 | 约 **1～2 分钟** |
| 内存 | 权重 3.55 GB + KV cache，**建议 8 GB 机型**；加载前会预检可用内存 |
| 磁盘 | 4 GB（权重 + 缓存） |

6 GB 机型不一定跑得动：加载前会报「可用 X GB，需要 Y GB」而不是让系统 OOM 掉整个进程。

## 构建

工程路径是纯 ASCII，AGP 直接接受，**就地构建**，不用同步到沙箱。

```bat
tools\build.bat :plugins:voxcpm2:assembleRelease
```

产物是未签名的：

```
plugins\voxcpm2\build\outputs\apk\release\voxcpm2-release-unsigned.apk
```

用宿主那把密钥签名（固定 v1 + v2 + v3 三方案全开）：

```powershell
powershell -ExecutionPolicy Bypass -File <宿主>\tools\sign-apk.ps1 `
  -Apk <unsigned.apk> -Out ...\Ponko-EX_VoxCPM2_release.apk
```

**别用 `gradlew`**：本机 wrapper 的 dists 是空的。`tools\build.bat` 负责载入 MSVC 环境、
指定 JDK 17 / Android SDK，并带 `--no-daemon`。

原生库**不在这里编**。`libvoxcpm2jni.so` 是预编译产物（NDK 27 + CMake 交叉编译
arm64，静态链接 `voxcpm2_runtime`，`llvm-strip` 后 5.77 MB），直接放进
`plugins/voxcpm2/src/main/jniLibs/arm64-v8a/`。要重新生成它，见
[`log/2026-10-21-VoxCPM2插件线.md`](log/2026-10-21-VoxCPM2插件线.md)。

## 已知限制

- **只支持 arm64**：x86 平板装不上（`abiFilters` 就一个）
- **不支持流式**：`req.streamAudio` 会被忽略，整段生成完一次性交回
- **权重只有魔搭一个下载源**：没有 hf-mirror 兜底，魔搭抽风时只能等
- **播放只有最近一份**：宿主保存的是「上一次合成的那份」，换文本再合成一次就把它顶掉了；
  切到别的插件时「播放」也会变灰（文件属于上一个插件）。要留住音频点「保存」，
  落到「音乐/Ponko」
- **长文本很慢**：字数没有硬性上限（插件不截断），但手机 RTF 10～30，40 字就是分钟级
- **取消不是瞬时的**：原生在音频回调里检查取消标志；若正卡在 LLM 预填充阶段，
  可能要几秒才响应
- **结果文件留在插件缓存**：`cache/synth/`，只保留最近 3 个。要长期留存，
  点页面顶部功能区的「保存」

## 许可

- 插件代码、JNI、文档：[MIT](LICENSE) —— `Copyright (c) 2026 visitor257`
- 推理引擎 `llama.cpp-omni` / `ggml`：**MIT**，版权归 ggml authors，
  全文与归属见 [NOTICE.md](plugins/voxcpm2/NOTICE.md)
- VoxCPM2 权重：**Apache-2.0**，由用户自行下载，随权重走，不受本仓库许可影响

MIT 允许商用、修改、闭源分发，条件是**保留版权声明和许可全文**。
所以这个插件把 `NOTICE.md` 一起打进了仓库，界面里也有一节写给用户看的许可说明。

> 另外两条与开源协议无关、但同样要守的：
> **声音权益**（《民法典》1023 条，自然人声音参照肖像权保护）——
> 拿真人音频做克隆要先取得同意；
> **深度合成标识**（《互联网信息服务深度合成管理规定》）——
> 对外发布生成的音频时要可识别。
> 自己录自己用、离线自己听，不涉及这些。

## 目录

```
Ponko-EX/                           这条分支就是本项目
├── README.md / README.en.md        本文件（中 / 英）
├── LICENSE                         MIT
├── doc/                            插件开发手册（快照，最新在 main 线）
├── log/                            这条线踩过的坑
├── tools/build.bat                 构建脚本（载入 MSVC 环境后就地编）
└── plugins/voxcpm2/
    ├── build.gradle.kts            包名 / minSdk / arm64 过滤
    ├── NOTICE.md                   第三方许可与归属（分发前必读）
    └── src/main/
        ├── AndroidManifest.xml     只有服务与激活组件，**没有启动图标**
        ├── jniLibs/arm64-v8a/      libvoxcpm2jni.so（预编译）
        ├── res/                    图标 + 字符串
        └── java/com/litertchat/plugin/voxcpm2/
            ├── VoxCpm2Activator.kt      宿主「启用插件」时打开的那个页面
            ├── VoxCpm2PluginService.kt  服务本体：加载、合成、设置界面、下载
            ├── VoxCpm2Bridge.kt         JNI 声明（10 个 native 方法）
            ├── ModelStore.kt            权重清单 + 断点续传
            └── AudioUtil.kt             参考音频解码 → 16 kHz 单声道 WAV
```

`doc/` 和 `log/` 是**开发这个插件时**用得到的背景，不是插件运行的一部分。
