# Ponko 插件开发手册

写给要写插件的人（也可能就是三个月后的我自己）。

手册讲**怎么做**。为什么这么设计、踩过哪些坑，在 [`../log/`](../log/) 里。

[English](plugin-guide.en.md)

---

## 1. 插件是什么

一个**独立的 APK**，有自己的包名、自己的进程、自己的 `.so` 和模型。
它通过 AIDL 跟 Ponko 说话，Ponko 只认接口，不认实现。

```
┌─ Ponko（com.litertchat.app）─┐        ┌─ 插件（com.litertchat.plugin.xxx）─┐
│                              │        │                                    │
│  PluginSession               │ bind   │  XxxPluginService : PonkoPluginService
│   └ PluginConnection ────────┼───────►│    ├ loadEngineBlocking()          │
│        │                     │ AIDL   │    ├ synthesizeBlocking()          │
│        ◄─────────────────────┼────────│    └ closeEngineBlocking()         │
│        onStage / onResult    │ 回调   │                                    │
└──────────────────────────────┘        └────────────────────────────────────┘
```

**为什么必须是 APK**：Android 10+ 禁止应用从自己的目录 `dlopen` 可执行文件。
只有安装时由系统释放到 `nativeLibraryDir` 的 `.so` 才能加载 —— 而那个目录
只接受 APK 里的 `jniLibs`。所以"自己往手机里丢个 so 让 Ponko 加载"这条路
在系统层就被封死了，没有绕过的方法。

### 分工

| 谁 | 管什么 |
|---|---|
| 插件 | **能做什么**：加载模型、跑推理、给出一份界面字段清单 |
| 宿主 | **怎么展示**：配色、控件样式、按钮放哪、进度显示在哪、深浅色 |

这条分工决定了后面几乎所有设计：插件**不写任何布局代码**，也不能读宿主的主题。

---

## 2. 最小插件：五个文件

照 `plugins/echo`（在 `Ponko-EX_test` 线上）抄，改三处就能变成真插件。

```
plugins/myplugin/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/litertchat/plugin/myplugin/
    │   ├── MyPluginActivator.kt     无界面激活组件（5 行）
    │   └── MyPluginService.kt       全部逻辑都在这
    └── res/values/strings.xml
```

### build.gradle.kts

```kotlin
android {
    namespace = "com.litertchat.plugin.myplugin"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.litertchat.plugin.myplugin"   // 必须与宿主不同
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 只能这样依赖，不能用 project(":ponko-plugin-sdk")
    implementation("com.litertchat.ponko:plugin-sdk:1.0.0")
}
```

> 跨工程用 `project(...)` 是不存在的路径。SDK 必须由宿主先
> `publishReleasePublicationToLocalRepoRepository` 发到本地 maven，这边才拿得到。

### 别忘了在 settings.gradle.kts 里 include

```kotlin
include(":plugins:echo")          // 新线就再加一行
```

---

## 3. Manifest：六件事，缺一个就连不上

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- ① 前台服务 + 唤醒锁的权限 -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- ② 让插件能查到宿主（判断有没有装） -->
    <queries>
        <package android:name="com.litertchat.app" />
    </queries>

    <application android:allowBackup="false" ...>

        <!-- ③ 无界面的激活组件：见下一节 -->
        <activity
            android:name=".MyPluginActivator"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="com.litertchat.ponko.ACTIVATOR" />
            </intent-filter>
        </activity>

        <!-- ④⑤⑥ 插件主服务 -->
        <service
            android:name=".MyPluginService"
            android:exported="true"
            android:foregroundServiceType="specialUse"
            android:permission="com.litertchat.ponko.permission.BIND_PLUGIN">
            <intent-filter>
                <action android:name="com.litertchat.ponko.PLUGIN" />   <!-- ④ 被发现 -->
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Runs local speech synthesis requested by Ponko" />  <!-- ⑤ -->
        </service>
    </application>
</manifest>
```

逐个说清楚：

| # | 项 | 缺了会怎样 |
|---|---|---|
| ① | 前台服务 / WAKE_LOCK 权限 | 长推理被系统杀掉，或 `startForeground` 直接抛异常 |
| ② | `<queries>` 含宿主包名 | 插件查不到宿主，某些 SDK 能力受限（不致命但建议写） |
| ③ | 激活组件 | **新装的插件永远连不上**，`bindService` 静默返回 false |
| ④ | `intent-filter` + ACTION_PLUGIN | 宿主扫描时看不见你，列表里根本没有这个插件 |
| ⑤ | `foregroundServiceType` + subtype 属性 | Android 14+ 不允许启动前台服务 |
| ⑥ | `android:permission` | 少了它谁都能 bind；写错方向则自己被挡住 |

### ⑥ 的方向容易搞反

`android:permission` 约束的是**调用方**（bind 你的那一方），不是你自己。

- 插件在 `<service>` 上写这个属性 → 意思是「**要 bind 我的人必须持有此权限**」
- 所以**宿主**需要声明 `<uses-permission>`，**插件自己不需要**
- 宿主那边还同时声明了 `<permission protectionLevel="signature">`，也就是这个权限只能由同签名 APK 拿到

结果：只有和宿主同一把密钥签名的 APK 能 bind，冒充者在系统层就被挡住。

---

## 4. 激活：为什么必须有个 Activity，但桌面上看不到

新安装的包处于系统 **stopped** 状态。这个状态下别的应用 bind 到它的服务会
**直接返回 false** —— 不抛异常、不报错，只有一句很难搜到的系统日志。

解除方式**只有一个**：包自己的 Activity 被启动一次。

实测无效的做法（别浪费时间）：发广播、`startService`、`pm enable`、重装同一个 APK。
Android 15 起官方更明确：只能由用户的直接或间接操作解除。

### 做法：无界面激活组件

```kotlin
class MyPluginActivator : PonkoActivatorActivity()
```

就这一行。基类已经处理好了：`onCreate` 里判断是不是"只为激活而来"、
立刻 `finish()`、不做任何绘制。

配合 Manifest 里那五个属性，效果是：

- **桌面上没有图标**（没有 `CATEGORY_LAUNCHER`）
- 用户点「启用」时，宿主自动拉起它，用户只看到一闪，回来就自动连上
- 和系统组件一样，只能在「设置 → 应用」里看到

> 关键认知：清 stopped 只要求「某个组件被启动」，**不要求那个组件有桌面入口**。
> 这两件事经常被混为一谈。

用户也可能手动点卡片上的「激活」——那是同一个动作，用于补救（被系统强停过、
或自动激活被某些 ROM 拦掉）。

---

## 5. AIDL 接口速查（API v2）

### 宿主 → 插件

| 方法 | 同步? | 说明 |
|---|---|---|
| `getApiVersion()` | 同步 | 返回 `PluginConst.API_VERSION`，宿主先校验这个再决定要不要继续 |
| `getManifest()` | 同步 | 插件自述 |
| `registerCallback(cb)` | 同步 | 宿主绑定后第一件事就是它；重复调用以最后一次为准 |
| `loadEngine(models, config)` | oneway | 加载模型；幂等（已在加载/已加载时忽略）。`config.threads` 宿主会传真值（见 §6.2） |
| `closeEngine()` | oneway | 释放模型内存；推理中调用 = 先取消再卸 |
| `isEngineReady()` | 同步 | **必须 O(1)**，只读 volatile |
| `getStatus()` | 同步 | **必须 O(1)**，返回 `EngineStatus` |
| `synthesize(req)` | oneway | 合成一次；已有任务在跑立刻回 `BUSY`，**不排队** |
| `cancel()` | oneway | 取消；插件必须回一次 `onError(CANCELLED)` |
| `getSettingsSchema()` | 同步 | 返回界面数据；**必须快**，不做 IO |
| `setSetting(key, value)` | oneway | 用户改了某项 |
| `invokeAction(key)` | oneway | 用户点了某个 `ACTION` |

> 同步方法只有三个半（版本、就绪、状态、界面数据），它们都在**宿主的 Binder 线程**
> 上执行。加锁或等 worker 会占死宿主 16 个 Binder 线程里的一个，期间宿主所有 IPC
> 都排队。所以实现里只读缓存字段。

### 插件 → 宿主

| 方法 | 时机 |
|---|---|
| `onEngineLoaded(info)` | 加载成功，回报采样率 / 声道 / 占用字节 |
| `onEngineError(err)` | 加载失败（OOM、文件损坏、ABI 不符） |
| `onStage(stage, label)` | 阶段变化；`label` 是可读文案，宿主直接显示 |
| `onAudioChunk(pcm, rate, ch, final)` | 仅 `streamAudio=true` 时；每块 ≤ 64KB |
| `onResult(bundle)` | 最终结果，**只发一次** |
| `onError(err)` | 出错，**只发一次**；取消也走这里 |
| `onSchemaChanged()` | 界面数据变了，请宿主重画 |

**`oneway` 没有背压**：插件发得快、宿主处理慢会在宿主侧排队，极端情况吃满
1MB Binder 配额连累其它 IPC。所以基类给 `onStage` 加了 500ms 限流 ——
别绕过它自己狂发。

---

## 6. 基类帮你做掉的 / 你必须自己做的

`PonkoPluginService` 已经实现：

- **线程**：所有 `oneway` 从 Binder 线程进来后立刻转到**单线程 worker**，
  绝不在 Binder 线程跑推理（每进程只有 16 个 Binder 线程，占死会连累别的 IPC）
- **前台服务**：`onBind` 里就 `startForeground`（见第 12 节的原因）
- **唤醒锁**：`PARTIAL_WAKE_LOCK`，四个出口都会撤；单次上限 30 分钟防泄漏，
  **工作期间每 10 分钟自动续期**（所以下载 3.5 GB 模型这种长任务不会中途掉锁）
- **状态机**：`UNLOADED / LOADING / READY / RUNNING`，`isEngineReady()` 是 volatile 读
- **取消**：`cancel()` 置标志，插件在耗时循环里调 `throwIfCancelled()`
- **回调去重**：`onResult` / `onError` 只发一次
- **异常映射**：`PluginException` → 对应错误码，其它异常 → `PLUGIN_INTERNAL`

你只需要实现四个方法：

```kotlin
class MyPluginService : PonkoPluginService() {

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "myplugin",
        displayName = "我的插件",
        versionName = "1.0.0",
        author = "…",
        capabilities = listOf(Capability.TTS),
        modelFamilies = listOf("my-model"),
        needsHostModels = false,
        minSdkHint = null,
    )

    override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
        // 可以是阻塞几十秒的活。可以直接 while 轮询进度。
        // 返回 modelBytes 用于显示；没有模型文件就填 0（不影响状态机）。
    }

    override fun closeEngineBlocking() {
        // 幂等。free 掉 native 句柄、关掉文件。
    }

    override fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle {
        out.stage(Stage.TEXT_ENCODE, "分析文本")
        // …耗时循环里周期性 out.throwIfCancelled()
        out.stage(Stage.DECODE, "已生成 3.2 秒")
        // 把音频交回去：大的走 FD，小的内联
    }
}
```

`createManifest()` 会在 Binder 线程上被调，**必须轻量且纯函数**。

### 能力（Capability）声明的是"承诺"，不是"现象"

| 常量 | 含义 | 宿主怎么用 |
|---|---|---|
| `TTS` | 文本→音频 | 「朗读回答」的正式路由会选它 |
| `ASR` | 音频→文本 | 预留 |
| `IMG` | 图像生成 | 预留 |
| `TEST` | 测试 / 示例插件 | 不进朗读路由，仍能在插件界面里合成并播放。**是否连上就加载引擎另由 `AUTOLOAD_ENGINE` 决定** |

**示例插件一律标 `TEST`，不要标 `TTS`。** 它生成的是固定音调不是语音，
标成 TTS 会让宿主在真的"朗读回答"时选到它，用户会听到一段莫名其妙的琶音。

### 6.1 `AUTOLOAD_ENGINE`：加载时机由你决定

**默认不自动加载。** 用户点「加载引擎」或「合成」时才加载。

这是刻意的默认值。加载代价的差别是数量级的：

| 插件类型 | 加载耗时 | 内存 |
|---|---|---|
| 玩具 / 纯算法（Echo） | 不到 1 秒 | 0 |
| 真模型（VoxCPM2） | 几十秒到分钟 | 数 GB |

宿主**猜不出**这个差别。以前它一律在连上时 `loadEngine`，
结果就是"用户切到插件页看一眼连接状态，后台开始灌 3.5 GB" ——
用户没请求过这个动作，也没法拒绝（除了不进那一页），而且会挤掉宿主
自己的对话 / 绘图模型，甚至触发 OOM。

所以：

```kotlin
capabilities = listOf(Capability.TTS, Capability.AUTOLOAD_ENGINE)  // 连上就加载
capabilities = listOf(Capability.TTS)                              // 用户手动加载（推荐给真模型）
```

**什么时候该声明：**

- 加载是"免费的"（几十毫秒、不占内存）→ 声明。装上立刻能用，少一次点击
- 加载要几秒以上，或者要占几百 MB 以上 → **别声明**。让用户决定什么时候付这个代价

声明的和没声明的，宿主界面上显示的东西不一样：

| | 声明了的 | 没声明的 |
|---|---|---|
| 连上后 | 直接开始加载 | 什么都不做，出现「加载引擎」按钮 |
| 用户点「合成」 | 直接合成 | 先加载再合成（不用先手动点加载） |

也就是说**没声明不等于用不了**，只是把"什么时候付代价"交回给用户。

> ⚠ 别为了"看起来更快"而随手声明。慢不是问题，替用户花钱/花内存才是。
> 这和 Ponko 主界面的模型页一致 —— 那里加载模型也是手动点按钮的。


### 6.2 `config.threads` 现在会收到真值

宿主在插件页给了「推理线程」选项，**连上插件（且要加载引擎）时就把值传下来**，
默认是 `核数 - 1`（夹在 1–6，用户可选 自动/2/4/6）。

所以 `loadEngineBlocking` 里应该**优先用 `config.threads`**，只有它 `<= 0` 才退回自己的默认：

```kotlin
override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
    val threads = if (config.threads > 0) config.threads
                  else minOf(4, Runtime.getRuntime().availableProcessors())
    // …用 threads 初始化运行时
}
```

以前宿主恒传 `0`，插件只能猜；现在传了真值却忽略它，用户改了线程数会发现"没变化"，
而界面上明明写着"下次加载引擎生效"——这种不一致最难解释。

> 值变了**不会自动重载引擎**（重载 = 用户正在等的模型又要重来一遍）。
> 新值在下一次 `loadEngine` 时生效，宿主界面上也是这么写的。

---

## 7. 引擎生命周期

```
UNLOADED ──loadEngine──► LOADING ──成功──► READY ──synthesize──► RUNNING
    ▲                       │                ▲                      │
    └────── 失败 ◄──────────┘                └──── 合成结束 ─────────┘
    └────────────────── closeEngine / onDestroy ─────────────────────┘
```

三条要点：

1. **`loadEngine` 幂等**。什么时候被调由 §6.1 决定：
   声明了 `AUTOLOAD_ENGINE` 的插件连上就调一次；没声明的等用户点「加载引擎」或「合成」。
   重复调不会出错，也不会重复加载。
2. **合成结束后回到 `READY`，不是 `UNLOADED`** —— 除非引擎真的没加载成功。
   （早期用「模型体积 > 0」判断，导致无模型的插件第一次合成完就被判成未加载，
   第二次合成直接报「引擎尚未加载」。见 `log/`。）
3. **`closeEngine` 要幂等**。用户点「卸载引擎」、停用插件、页面销毁、进程被回收都会调。

`modelBytes` **只用于显示**，不参与状态判断。没模型的插件填 0 完全正常。

### 卸载没有完成回调

`IPonkoCallback` 里有 `onEngineLoaded` / `onEngineError`（加载的两条路都有），
但**没有 `onEngineUnloaded`**。所以宿主判断"卸载完了没"只能轮询 `getStatus().state`。

对你的影响：

- `closeEngineBlocking()` 里**别依赖宿主来通知什么** —— 你只管同步地 free 完返回
- 想 fast：**尽早返回**。宿主会一直轮询到 `UNLOADED`，`closeEngineBlocking` 拖得越久，
  用户看着"正在卸载引擎…"的时间就越长
- 正在推理时被要求卸载：SDK 会置取消标志，你要在耗时循环里查 `throwIfCancelled()`。
  查得越稀，卸载越慢（宿主那边就是干等）
- 卸载窗口内如果有 `synthesize` 进来，SDK 现在回 **`NOT_READY`**（不是 `BUSY`）——
  因为那会儿什么都没在生成，回"上一段语音还在生成"是撒谎

> 早期的 SDK 在同一个窗口回 `BUSY`，害得用户点了卸载再点合成会看到
> "上一段语音还在生成，请稍候"。**已修**（SDK 侧加了独立的 `closing` 标志）。
> 这条修复要重发 AAR + 重编插件才生效。

---

## 8. 做一次合成

### 请求里会有什么

宿主（Ponko 2.0.0 起）的插件页有一块「合成参数」，它把这些字段真的填上了 ——
不再是"接口里存在但没人用"：

| 字段 | 谁填 | 备注 |
|---|---|---|
| `text` | 用户输入 | **没有长度上限**，多行输入。宿主只在旁边显示耗时估算，不拦不截断 |
| `mode` | 用户在界面上选 | `0` 纯 TTS / `1` 克隆 / `2` 极致克隆 |
| `referenceAudio` | 用户选文件后由宿主打开 | **已 open 的 FD**，只在 `mode≥1` 时非空 |
| `referenceTranscript` | 用户输入 | 只在 `mode=2` 时填；`mode=1` 可能为空 |
| `seed` | 用户输入 | `0` = 随机 |
| `voiceDescription` | 暂未暴露 | 恒为空串，插件照常拼接即可 |
| `cfg` / `timesteps` / `maxSteps` | **没填** | 保持 `-1 / 0 / 0`，也就是"用你的默认" |

`cfg` / `timesteps` 没暴露是故意的：合理范围强依赖具体模型
（VoxCPM2 是 cfg≈2.0、steps 10–20），宿主猜不出默认值。这些该由插件在
**自己的** `UiSchema` 里暴露，宿主不插手。

所以插件必须对"字段可能没填"做容错：`<= 0` / `< 0` / 空串 一律当"用我的默认"。
宿主只有一处保证：`mode≥1` 时 `referenceAudio` 一定非空（界面层会挡住）。

### `text` 没有长度上限，所以长度是你的责任

这条要说清楚，因为它决定插件该不该自己设限。

**宿主不限制、不截断、不打断。** 用户想合成一篇 5000 字的文章，界面就让他填 ——
他要等多久是他自己的判断，宿主只负责在旁边显示一个粗略的耗时估算。

为什么不设：**UI 归 UI，推理归推理。** 宿主既不知道你的 RTF，也没有资格替
所有插件定一个"多长算太长"。真设一个全局上限，轻量模型（Piper 之类）
也会被同一根绳子捆住 —— 那 40 字对它们纯属添乱。

那插件该怎么做：

| 情况 | 做法 |
|---|---|
| 能处理任意长度 | 什么都不用做。这是大多数情况 |
| 有硬上限（上下文窗口、显存） | 自己判断，超了回 `PluginException.invalidRequest("文本超过 N 字，请分段")` |
| 长度影响内部策略 | 自己按 `req.text.length` 分支（比如长文本改用更小的 batch） |

**不要指望宿主挡在前面。** 以前宿主确实卡过 40 字，那是错的，已撤掉。

### 两个 FD 的生命周期

`referenceAudio` 那个 FD 是**宿主的**。Binder 传参时系统会 dup 一份给你，
所以你手上这个是独立句柄 ——

```kotlin
val fd = req.referenceAudio ?: throw PluginException.invalidRequest("克隆模式缺少参考音频")
val tmp = File(cacheDir, "ref.wav")
// fdopen 后复制到自己目录，然后**关掉自己这份**
ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
    tmp.outputStream().use { input.copyTo(it) }
}
// 之后全用自己的 tmp 路径：解码、重采样到 16 kHz、送进模型
```

- **不要**长期持有这个 FD 当模型输入 —— 它指向用户的文档（可能是网盘里的），
  随时可能失效
- **要**关掉自己这份，否则句柄泄漏
- 格式按 `SynthesisRequest` 的约定：优先 16-bit PCM WAV；用户可能选 mp3，
  解码成 16 kHz 单声道是**插件**的责任（宿主只负责把文件给你）

### 进度

```kotlin
out.stage(Stage.TEXT_ENCODE, "分析文本（${req.text.length} 字）")
out.stage(Stage.DECODE, "已生成 ${"%.1f".format(seconds)} 秒")
out.stage(Stage.AUDIO_DECODE, "封装 WAV")
out.stage(Stage.DONE, "完成")
```

`stage()` 自带 500ms 限流。`label` 会被宿主**原样显示**，所以要写人话，
不要写 `"decoding step 37"`。

> `DECODE` 在真模型上占 80% 以上耗时（1–2 分钟）。**必须每 500ms 报一次
> 「已生成 X 秒」**，否则用户会以为卡死然后强杀进程。

### 取消

宿主插件页有「停止」按钮，生成中可以随时点。插件侧要做的只有一件事：

```kotlin
for (i in 0 until n) {
    out.throwIfCancelled()      // 被取消就抛，基类负责回 onError(CANCELLED)
    // …一步计算
}
```

**必须周期性地查**，而且要查得够密。不检查的后果不是"慢一点"——
宿主那边会一直停在"正在停止…"，「合成」按钮始终灰着，用户只能退出页面重进。
检查点太稀疏（比如只在每个音结束后查一次）体验上等同于没实现取消。

> **一定要让 `onError(CANCELLED)` 真的发出去。** 基类在 `throwIfCancelled()` 抛出后
> 会自动发，所以只要用 `throwIfCancelled` 就够了；如果自己写了别的方式提前 return，
> 记得仍然要让基类走到收尾 —— 宿主靠这个回调复位按钮。

### 音频回传：两条路


| 方式 | 条件 | 用哪个 |
|---|---|---|
| `ResultBundle.inlineAudio` | 结果 < 256 KB | 短音频、测试用 |
| `ResultBundle.audio`（`ParcelFileDescriptor`） | 任意大小 | **正常情况都用这个** |

```kotlin
// 写到自己 cacheDir，然后把 FD 交出去
val dir = File(cacheDir, "out").apply { mkdirs() }
val f = File(dir, "speech.wav")
f.writeBytes(wav)

return ResultBundle(
    requestId = req.requestId,
    sampleRate = 48000,
    channelCount = 1,
    durationMs = …,
    elapsedMs = …,
    audioFormat = PluginConst.FMT_WAV,      // 或 FMT_PCM16
    audio = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY),
    inlineAudio = null,
    debugInfo = "…",
)
```

- **不能传路径**：插件读不到宿主目录，宿主也读不到插件目录。
- **不能塞 ByteArray 传大音频**：Binder 全进程 1 MB 配额会炸。
- **FD 归宿主**：插件交出去之后不要自己 close 那个 `ParcelFileDescriptor` 对象
  （文件本体留在自己的 cache 里，宿主读完就关 FD；缓存由你自己清理）。

### `debugInfo` 会被用户看见

宿主插件页底部有一个折叠的「诊断信息」区，里面显示 `debugInfo` 和**最后一条错误的
`detail`**。所以：

- 往里写**对排查有用**的东西：模型名 / 量化 / 实际步数 / 重采样前后的采样率 / RTF
- **不要**往里写路径、token、用户文本原文 —— 这块内容用户能长按复制、会贴给别人
- 出错时 `PluginError.detail` 放堆栈或原生报错原文（宿主只放进诊断区，不当正文显示）
- `message` 仍然是给用户看的一句话，两者别混

这比只进 logcat 有用得多：普通用户装不了 adb，但他能截图。

### 流式（可选）

`req.streamAudio == true` 时用 `out.chunk(pcm, rate, ch, isFinal)` 边生成边推，
每块 ≤ 64KB。适合长文本边播。v1 宿主默认走非流式。

---

## 9. 插件界面：宿主渲染

插件**不写布局**。它返回一份字段清单，宿主按自己的主题画。

```kotlin
override fun buildSettingsSchema(): UiSchema = UiSchema(
    intro = "这段说明会显示在页面顶部，可以用 **粗体**、`代码` 这类行内格式。",
    items = listOf(
        UiItem("g1", UiType.GROUP_START, label = "音色"),
        UiItem("speed", UiType.SLIDER, label = "语速",
               value = "1.0", min = 0.5, max = 2.0, step = 0.1),
        UiItem("voice", UiType.CHOICE, label = "音色模板",
               value = "default",
               options = listOf(UiOption("default", "默认"), UiOption("soft", "柔和"))),
        UiItem("g1e", UiType.GROUP_END),
        UiItem("dl", UiType.ACTION, label = "下载模型", primary = true),
    ),
)

override fun onSettingChanged(key: String, value: String) { /* 存值 */ }
override fun onActionInvoked(key: String) { /* 做耗时活，做完 notifySchemaChanged() */ }
```

### 文本格式：只有一个很小的子集

`intro` 和 `INFO` 的正文支持**两种**行内格式：

| 写法 | 效果 |
|---|---|
| `**粗体**` | 加粗 |
| `` `等宽` `` | 等宽字体 |

**其它 markdown 一律按原文显示** —— `#` 标题、`- ` 列表、`[链接](url)`、表格都不解析，
会连符号一起显示出来。`label` / `help` / `value` 这些字段**完全不解析**，纯文本。

> ⚠ 这条以前不一致：手册说支持粗体和代码，实际宿主只是 `text = t`，
> 插件照着写就会在界面上看到一堆星号。**宿主 2.0.0 之后才真的支持**（只支持上面两个）。
>
> 写文案时按"只有粗体和代码"来考虑布局，别指望列表和标题。

### 12 种控件

| 类型 | 用途 | 关键字段 |
|---|---|---|
| `INFO` | 一段说明文字 | `label` = 正文 |
| `SECTION` | 分组标题（不可折叠） | `label` |
| `SWITCH` | 开关 | `value` = `"true"` / `"false"` |
| `SLIDER` | 数值滑块 | `min` / `max` / `step` / `value` |
| `CHOICE` | 单选 | `options` + `value` |
| `TEXT` | 单行输入 | `value` |
| `ACTION` | 按钮 | `key`（点了走 `invokeAction`） |
| `PROGRESS` | **只读**进度条 | `value` = 0–100 百分比字符串 |
| `STATUS` | **只读**「键 → 值」状态行 | `label` / `value` |
| `TEXTAREA` | 多行输入 | `value` |
| `GROUP_START` | 可折叠分组开始 | `label` = 标题，`key` 记住折叠状态 |
| `GROUP_END` | 分组结束 | — |

`UiType.MAX_KNOWN = 11` 是宿主认识的最大编号。你用了更大的值，宿主只能降级成
纯文本（不会崩，但控件没了）。**所以这个集合是宿主侧一次放够的，插件不要指望加新类型。**

### 值归插件管

宿主**不持久化任何设置值**，每次重画都重新调 `getSettingsSchema()`。
所以：

- 把"当前值"填进 `UiItem.value`，而不是从宿主读
- 用户改了就存（SharedPreferences / 自己的文件都行）
- 别的项的状态也跟着变时（比如选了模式 A 要让某滑块灰掉），先存好再 `notifySchemaChanged()`

**不要靠 `enabled=false` 隐藏东西** —— 用户需要看到「为什么这个不能点」，
看得到才有线索。真要隐藏就干脆不返回那一项。

### 什么该放界面、什么该放卡片

| 位置 | 内容 | 例子 |
|---|---|---|
| 卡片（宿主画） | 管理动作 | 启用 / 激活 / 进入 / 卸载 |
| 插件界面（宿主画，内容来自插件） | **功能**与参数 | 合成、播放、下载模型、语速、音色 |

判断标准：**插件管"能做什么"（能力），宿主管"怎么触发"（按钮）。**
「合成」是宿主发起一次推理产出文件（不播放），所以按钮由宿主画；但它是**功能**，
所以放在插件界面里，而不是卡片上。

### 音频插件会得到哪些按钮（插件作者需要知道）

只要插件声明了 `TTS` 或 `TEST`，插件界面顶部就会出现这几个**宿主画的**按钮。
它们不由插件定义，但**文字可以改**（见下一节）：

| 按钮 | 什么时候有 | 谁在动 |
|---|---|---|
| 加载引擎 / 卸载引擎 | 只在**没**声明 `AUTOLOAD_ENGINE` 时 | 宿主调 `loadEngine` / `closeEngine` |
| 合成 | 一直有 | 宿主发一次 `synthesize`，**只落盘，不播放** |
| 播放 | **有产物之后** | 只重放上一次的文件，**不重新推理** |
| 停止 | 合成期间可点 | 宿主调 `cancel()`，你回 `CANCELLED` |
| 保存 | 有产物之后 | 把音频存进系统「音乐/Ponko」 |

所以：**你不需要在自己的 `UiSchema` 里再放一个"合成"或"播放"按钮** ——
宿主已经处理了。把界面留给真正属于插件的东西（模型选择、音色、参数、下载）。

> **合成和播放是两个独立动作，各有各的按钮。** 用户听完想再听一遍是**高频**操作，
> 而手机上重新合成要等 1~2 分钟 —— 所以别指望"合成完自动播"能省一个按钮，
> 那样每次生成都强迫用户听完，在安静场合只能用不了。

### 给宿主按钮改名字（SDK v3 起）

默认文案是按"有个引擎要加载"写的。如果你的插件其实不是那样，可以覆盖：

```kotlin
override fun buildActionLabels(): Map<String, String> = mapOf(
    ActionLabel.ENGINE_LOAD to "加载模型 3.5G",
    ActionLabel.ENGINE_UNLOAD to "释放模型",
)
```

**只改文字。** 位置、个数、颜色、可点状态、点了做什么，全部还是宿主说了算 ——
所以插件拿不到"新按钮"，只能给自己那一格换个说法。

#### 可用的 key

| key | 宿主默认 | 什么时候显示 |
|---|---|---|
| `ActionLabel.ENGINE_LOAD` | 加载引擎 | 引擎未就绪 |
| `ActionLabel.ENGINE_UNLOAD` | 卸载引擎 | 引擎已就绪（同一个按钮的另一个状态） |
| `ActionLabel.REASONING` | 合成 | 一直有。**只跑推理产出文件，不播放** |
| `ActionLabel.PLAY` | 播放 | 一直有（有产物才可点）。只重放，不推理 |
| `ActionLabel.STOP` | 停止 | 一直有（合成期间才可点） |
| `ActionLabel.SAVE` | 保存 | 一直有（有产物才可点） |

**没写的 key 用宿主默认。** 不需要整组都覆盖 —— Echo 就只改了一个，
剩下的继续用默认（这也是一份现成的验证：两种来源能同时存在于同一行按钮里）。

> **合成和播放是两个动作，各有一个按钮。** 以前合成完会自动播，
> 于是同一个按钮在"还没听过"和"听过了"时含义不同，还得加 `preview.again` 给它改名。
> 现在拆开了：合成只管产出文件，想问"这段好听吗"点播放。**没有 `.again` 这种变体。**
>
> 顺带说命名：`preview` 改成了 `reasoning`，因为「试听」描述的是**听**，
> 而按钮做的是跑推理。名字错了会带来实际困惑 —— 用户点完没听到声音，会以为坏了，
> 其实音频已经生成好，就在那儿等着播。

#### 什么时候值得改

| 你的插件 | 建议 | 为什么 |
|---|---|---|
| 走本地模型 | `engine.load` → 「加载模型 3.5G」 | 带体积用户才知道这一下要等多久、占多少内存 |
| 走网络 API | `engine.load/unload` → 「连接服务 / 断开服务」 | 它根本没有模型，说"引擎"是错的 |
| 模型可常驻复用 | `engine.unload` → 「释放内存」 | 用户关心的是内存，不是"卸载"这个动作 |
| 玩具 / 测试插件 | `reasoning` → 「跑一次测试」 | 它不合成真语音，叫「合成」会让人以为在合成人声 |

#### 硬性约束

| 情况 | 结果 |
|---|---|
| key 写错（不在上表里） | 该项**丢弃**，其它项照常生效 |
| 值是空串 | 该项丢弃 |
| 值超过 12 个字符 | **整个丢弃**（不是截断），退回宿主默认 |
| `buildActionLabels()` 抛异常 | 全部退回默认，**插件照常可用** |

超长为什么是丢弃而不是截断：截出来是「加载模型 3.5…」，比原来那句默认文案更难懂。

#### 别用它干的事

- **别把破坏性动作改得听起来无害**（把「停止」叫成「保存」）。用户按字面理解去点，
  然后丢掉正在生成的东西 —— 这是唯一一个我没法用代码拦住的滥用手法。
- 别在这里做耗时活：方法跑在 Binder 线程上，打开界面时就会调。

---

## 10. 兼容性：版本号怎么抬

```kotlin
const val API_VERSION = 3                   // AIDL 表面版本，任何改动都 +1
const val API_WITH_SCHEMA = 2               // 从这一版起有界面那组方法
const val API_WITH_ACTION_LABELS = 3        // 从这一版起能改按钮文案
const val API_MIN_SUPPORTED = 1             // 宿主接受的最老插件
const val API_MAX_SUPPORTED = 3             // 宿主接受的最新插件
```

### 两类改动

| 类型 | 例子 | 处理 |
|---|---|---|
| **扩展** | 加方法、加字段、加常量 | `API_VERSION + 1`，老插件继续能用，宿主按版本判断新能力是否可用 |
| **破坏** | 删方法、改字段语义 | `API_VERSION + 1` **并且抬 `API_MIN_SUPPORTED`**，老插件直接被拒 |

绝大多数改动是扩展。真要破坏时先想清楚：所有已发出去的插件都得重编。

### 新方法必须加在 AIDL 的**最末尾**

Binder 事务码按声明顺序分配。在中间插入会让后面所有方法的事务码整体后移，
**老插件收到的码对不上，调用串味** —— 本该调 A 却执行了 B。这比直接报错糟糕得多。

### 判断"能不能调"靠版本号，不靠 try-catch

宿主想调 `getSettingsSchema()` 前先看 `pluginApi >= API_WITH_SCHEMA`。
为什么不 try-catch：AIDL 遇到不认识的方法时行为不保证 —— 同步方法可能读到空
Parcel 返回 null 或怪值，`oneway` 方法**静默无操作**。把它当异常处理不可靠。

---

## 11. 调试

### 日志

| tag | 来源 |
|---|---|
| `PonkoPlugin` | 插件基类（SDK 侧） |
| `PonkoPluginConn` | 宿主侧单个连接（状态机、bind 结果） |
| `PonkoPluginSession` | 宿主侧插件门面（扫描、启用、朗读路由） |
| `PonkoPluginPage` / `PonkoPluginUi` | 宿主侧界面 |

```bat
adb logcat -s PonkoPlugin PonkoPluginConn PonkoPluginSession
```

### 错误码

| 码 | 名字 | 通常是 |
|---|---|---|
| 1 | `NOT_READY` | 引擎没加载（宿主会自动重载一次再重试） |
| 2 | `MODEL_INVALID` | 模型缺文件 / 格式不对 |
| 3 | `OUT_OF_MEMORY` | 内存不足，提示用户关点东西 |
| 4 | `CANCELLED` | **不是错误**，宿主静默收尾 |
| 5 | `INVALID_REQUEST` | 空文本、缺参考音频 |
| 6 | `PLUGIN_INTERNAL` | 插件自己抛了异常 |
| 7 | `UNSUPPORTED` | 不支持的模型 / 模式 |
| 8 | `TIMEOUT` | 宿主侧超时 |
| 9 | `BUSY` | 上一段还在跑（不排队，立刻回） |

`PluginError.message` 由插件**本地化**，宿主直接显示、不再加工。所以文案要写人话。

### 常见失败对照

| 现象 | 大概率原因 |
|---|---|
| 列表里根本看不到插件 | Manifest 少了 `ACTION_PLUGIN` 的 intent-filter，或 `<service>` 没 `exported="true"` |
| `bindService` 返回 **false** | ① 包处于 **stopped**（新装没激活过）② 服务组件被系统禁用 ③ intent 解析不到 |
| 抛 **SecurityException** | 签名不一致，或宿主没拿到 signature 权限（重启手机后再试） |
| 绑上了但 5 秒后超时 | 插件进程起不来：缺 `.so`、ABI 不符、初始化崩溃 |
| 第一次能合成，第二次报「引擎尚未加载」 | 插件侧状态机用错了判据（见第 7 节） |
| 运行时 `NoSuchMethodError` | AIDL 改了但**没重发 SDK AAR**，插件编的是旧接口 |
| 界面显示成「这个控件需要更新 Ponko」 | 插件用了 `type > MAX_KNOWN` 的控件 |

---

## 12. 几个必须知道的设计约束

### 前台服务必须在 `onBind` 里起

```kotlin
override fun onBind(intent: Intent?): IBinder {
    goForeground(Stage.LOADING, "准备中…")   // 基类已做
    return binder
}
```

Android 12+ 禁止后台启动前台服务。`onBind` 那一刻宿主刚 `bindService`、
还在前台，一定能过；等到 `synthesize` 里才起就可能抛
`ForegroundServiceStartNotAllowedException`，服务起不来，推理被中途杀掉。

### 一大块音频只能走 FD

Binder 全进程只有 1 MB 配额。传 `ByteArray` 超过这个量会炸，
而且是那种"小音频能跑、长文本必崩"的诡异形态。

### 屏幕常亮是宿主的事

插件持 `PARTIAL_WAKE_LOCK` 只保住 CPU，**不保屏幕**。
屏幕由宿主用 `FLAG_KEEP_SCREEN_ON` 负责 —— 两者互不替代。

---

## 13. 发版检查清单

- [ ] `versionCode` 加过了（同版本号覆盖安装会让某些 ROM 缓存旧 dex）
- [ ] `capabilities` 声明的是真实承诺（示例插件用 `TEST`，不用 `TTS`）
- [ ] 用宿主的密钥签名，`apksigner verify --min-sdk-version 23` 三项全 true
- [ ] 桌面没有图标（只有激活组件，没有 `CATEGORY_LAUNCHER`）
- [ ] 装到干净设备上，从「启用」走到「合成 → 播放出声」一次成功
- [ ] 连续合成两次以上（验证引擎状态机）
- [ ] **生成中点「停止」能立刻收尾**（插件检查点够密，宿主按钮能复位）
- [ ] 克隆模式下选一段参考音频能真的出结果（宿主会传 FD，插件要解码到 16 kHz）
- [ ] 改了「推理线程」后重启插件，确认 `config.threads` 真的用上了
- [ ] `AUTOLOAD_ENGINE` 声明得对（真模型**不该**声明，否则用户一切到插件页就灌几个 G）
- [ ] 没声明的话：「加载引擎」按钮能出现、能加载、能卸载，文案跟着状态变
- [ ] 「卸载引擎」点完后按钮会自己复位（不是停在"卸载引擎"，再点反而加载）
- [ ] 卸载后立刻点「合成」，不会报"上一段语音还在生成"
- [ ] 有产物后「播放」能只重放、不重新推理
- [ ] `intro` / `label` / `help` 都是按纯文本写的，没用 markdown 的列表和标题
- [ ] 覆盖过的按钮文案 ≤ 12 字符，且没覆盖的那几个仍然显示宿主默认（不是空按钮）
- [ ] 改过名字的按钮，点下去干的事和提示文字仍然对得上（没把「停止」说成别的）
- [ ] 停用后插件的模型内存确实释放了
- [ ] 改过 AIDL 的话：SDK 已重发，插件已重编
- [ ] 判包看 **sha256**，不是字节数（相邻版本字节数可能完全相同）

---

## 14. 更多

- 设计取舍与踩过的坑：[`../log/`](../log/)
- 接口定义原文：宿主工程的 `ponko-plugin-sdk/src/main/aidl/`
- 完整示例：`Ponko-EX_test` 线的 `plugins/echo/`
