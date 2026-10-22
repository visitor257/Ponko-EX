// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.litertchat.plugin.ActionLabel
import com.litertchat.plugin.Capability
import com.litertchat.plugin.EngineConfig
import com.litertchat.plugin.EngineInfo
import com.litertchat.plugin.ErrCode
import com.litertchat.plugin.ModelBundle
import com.litertchat.plugin.PluginConst
import com.litertchat.plugin.PluginException
import com.litertchat.plugin.PluginManifest
import com.litertchat.plugin.PonkoPluginService
import com.litertchat.plugin.ResultBundle
import com.litertchat.plugin.Stage
import com.litertchat.plugin.SynthesisRequest
import com.litertchat.plugin.UiItem
import com.litertchat.plugin.UiOption
import com.litertchat.plugin.UiSchema
import com.litertchat.plugin.UiType
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * VoxCPM2 语音合成插件。
 *
 * ## 它做什么
 *
 * 加载 `libvoxcpm2jni.so`（5.6 MB，APK 里就这一个原生库），把宿主发来的
 * [SynthesisRequest] 翻成原生侧的三参数（mode / 参考音频 / 提示文本），
 * 跑完后把 48 kHz WAV 用 FD 交回宿主。
 *
 * ## 三个必须守住的点
 *
 * 1. **一次合成 1~2 分钟**（手机纯 CPU，RTF 10~30）。所以必须有轮询线程
 *    每 500 ms 报「已生成 X 秒」，否则用户会以为卡死然后强杀进程。
 * 2. **取消要真的打断**：原生侧是阻塞调用，只能靠 `nativeCancel()` 置标志，
 *    它的音频回调每块都检查。轮询线程负责把宿主的取消意图转成这一次调用。
 * 3. **权重不在 APK 里**：3.3 GB，首次用前必须自己下（支持断点续传）。
 *    没下的时候要给出明确的错误，而不是让原生侧报一个看不懂的 GGUF 错误。
 */
class VoxCpm2PluginService : PonkoPluginService() {

    // ---- 引擎状态：全部是 volatile，因为 getSettingsSchema() 跑在宿主 Binder 线程上 ----
    @Volatile private var nativeReady = false
    /** 「释放模型」点下去之后、原生真的 free 完之前的短暂状态 */
    @Volatile private var engineUnloading = false
    @Volatile private var sampleRate = 48_000
    @Volatile private var loadedBytes = 0L
    @Volatile private var loadedSourceKey = ""
    @Volatile private var loadedThreads = 0

    // ---- 下载状态 ----
    @Volatile private var downloading = false
    @Volatile private var dlPercent = 0.0
    @Volatile private var dlDetail = ""
    @Volatile private var dlNotice = ""
    private val dlCancel = AtomicBoolean(false)
    @Volatile private var dlThread: Thread? = null

    /** 上次推下载进度的时刻（节流用，见 [pushDownloadProgress]） */
    @Volatile private var lastDlPushAt = 0L

    /**
     * 下载进度：推给进度条和当前文件那一行（都是**原地补丁**）。
     *
     * 下载回调一秒来几十次。以前这里是"每秒整页重建一次"，代价是日志滚动位置
     * 和用户正在填的音色描述每次都被冲掉 —— 宿主 v4 给了 `onUiPatch` 之后
     * 就不该再这么干了：两个 key 各改各的，别的控件一动不动。
     *
     * 仍然要节流：`onUiPatch` 是 oneway、没有背压。
     */
    private fun pushDownloadProgress(spec: ModelStore.Spec, got: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDlPushAt < DL_PUSH_INTERVAL_MS) return
        lastDlPushAt = now
        notifyUiPatch(KEY_DL_PROGRESS, "%.1f".format(dlPercent), false)
        notifyUiPatch(
            "${KEY_FILE_PREFIX}${spec.fileName}",
            "下载中 · ${ModelStore.human(got)} / ${ModelStore.human(total)}",
            false,
        )
    }

    // ---- 设置项（宿主不持久化，所以值由插件自己存）----
    @Volatile private var sourceKey = ModelStore.SOURCES[0].key
    @Volatile private var cfg = 2.0f
    @Volatile private var timesteps = 10
    @Volatile private var voiceDesign = ""

    /** 数字输入框的校验回显：输入非法时告诉用户"保留了什么"，而不是默默吞掉 */
    @Volatile private var cfgHint = ""
    @Volatile private var stepsHint = ""

    // ---- 设备信息：**不能在 buildSettingsSchema() 里现取** ----
    //
    // getMemoryInfo() 是一次到 system_server 的 Binder 调用，而 buildSettingsSchema()
    // 跑在宿主的 Binder 线程上。在那儿做 IPC 会把宿主的 16 个 Binder 线程占一个，
    // 所以这里缓存，只在 onCreate / 加载完 / 下载完 / 合成完这些**自己线程**的时机刷新。
    @Volatile private var devCores = Runtime.getRuntime().availableProcessors()
    @Volatile private var devMemTotal = 0L
    @Volatile private var devMemAvail = 0L
    @Volatile private var devAbi = ""

    // ---- 上次合成结果（给「上次」状态行用）----
    @Volatile private var lastAudioSec = 0.0
    @Volatile private var lastGenMs = 0L
    @Volatile private var lastRtf = 0.0
    @Volatile private var lastMode = -1

    // ---- 界面日志 ----
    //
    // 宿主从 SDK v4（2026-10-22）起给了 `UiType.LOG`：固定 160dp 高、内部自己滚、
    // 长按可复制的只读框，内容靠 `onUiPatch` 送 —— **不再整页重画**。
    // 这一点是关键：以前的 Info 快照只能靠 `notifySchemaChanged()` 刷新，
    // 那是 `removeAllViews()` 全量重建，日志一多就是"每来一行把整页拆一遍"，
    // 滚动位置、正在编辑的 TEXTAREA 全被冲掉。
    private val logLines = ArrayDeque<String>()
    private val logLock = Any()

    /**
     * 界面日志框里**已经有几行**（自上次整段重置起）。
     *
     * 推补丁时只送这之后的差量。整段替换也能用，但用户往上翻看历史时
     * 内容会被整体换掉、视线跟着跑 —— 宿主那边「只在停在底部时才跟随滚动」
     * 的设计就是为这个做的，整段替换绕过它。
     */
    @Volatile private var pushedLines = 0

    /**
     * 缓冲被裁剪过（超过 [LOG_KEEP] 丢过最老的行）。
     *
     * 一旦裁剪，界面上的行数与缓冲就对不上了，差量算不出来 —— 只能整段重置一次。
     */
    @Volatile private var logTrimmed = false

    /** 上次把日志推给界面的时刻（节流用，见 [pushLog]） */
    @Volatile private var lastLogPushAt = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 被节流掉的那一行留在缓冲里，靠这个延迟任务补推一次 */
    private val flushLogTask = Runnable { pushLog(force = true) }

    /** 写一行界面日志，并**单独**把日志框更新掉（不重画别的控件） */
    private fun logLine(msg: String) {
        // 不用 String.format("%tH")：Kotlin 会把 $tH 当成字符串模板
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logLock) {
            logLines.addLast("$ts  $msg")
            while (logLines.size > LOG_KEEP) {
                logLines.removeFirst()
                // 丢了最老的 → 界面上那几行成了孤儿，差量算不准，下次推整段
                logTrimmed = true
            }
        }
        Log.i(TAG, msg)
        pushLog()
    }

    /**
     * 把日志缓冲推给日志框。
     *
     * ## 常规是"只推新行"，裁剪之后才整段重置
     *
     * 宿主（2026-10-22 起）的跟随规则是：**追加时，只有用户本来就停在底部才滚到最新**。
     * 这要求插件送的是真追加 —— 每次推整段快照的话，用户翻上去看历史时内容会被
     * 整体换掉（位置不变但行变了），跟"自动滚到底"一样烦。
     *
     * 但差量要一个游标（[pushedLines]），而缓冲满了会丢最老的行，那时游标失效：
     * 所以只有 [logTrimmed] 为真时才整段重置一次，把界面砍回 [LOG_KEEP] 行。
     *
     * 页面重建（`notifySchemaChanged()`）时界面日志会被换成 schema 里的快照，
     * [logSnapshotForSchema] 会把游标一起对齐，避免重建后重复推同一行。
     *
     * ## 为什么要节流又想得起补推
     *
     * `onUiPatch` 是 `oneway`、**没有背压**，下载/推理时回调来得比这快得多。
     * 但直接丢掉被节流的那次也不行 —— 如果之后没有新日志（比如最后一行是
     * "下载完成"），那一行就永远不出现了。所以被节流时挂一个延迟任务补推。
     */
    private fun pushLog(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLogPushAt < LOG_PUSH_INTERVAL_MS) {
            mainHandler.removeCallbacks(flushLogTask)
            mainHandler.postDelayed(flushLogTask, LOG_PUSH_INTERVAL_MS)
            return
        }
        mainHandler.removeCallbacks(flushLogTask)
        lastLogPushAt = now

        // 取差量与推进游标必须是同一把锁里的一步 ——
        // 分开做的话，两条日志并发进来会算重或漏
        val out = synchronized(logLock) {
            val size = logLines.size
            when {
                logTrimmed -> {
                    val whole = logLines.joinToString("\n")
                    logTrimmed = false
                    pushedLines = size
                    whole to false
                }
                size > pushedLines -> {
                    val fresh = logLines.toList().subList(pushedLines, size).joinToString("\n")
                    // 首行没有前文，别送一个空行开头
                    val payload = if (pushedLines == 0) fresh else "\n$fresh"
                    pushedLines = size
                    payload to true
                }
                else -> null
            }
        } ?: return
        notifyUiPatch(KEY_LOG, out.first, out.second)
    }

    /**
     * 给 schema 用的整段快照，**同时把游标对齐到它**。
     *
     * 两件事必须在同一把锁里做：先取快照再记行数，中间插进来的一行会被
     * 当成"已经送过"，从此丢掉；反过来则会在重建后重复推。
     */
    private fun logSnapshotForSchema(): String = synchronized(logLock) {
        pushedLines = logLines.size
        logTrimmed = false
        if (logLines.isEmpty()) "" else logLines.joinToString("\n")
    }

    private fun refreshDeviceStats() {
        devCores = Runtime.getRuntime().availableProcessors()
        devAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            devMemTotal = mi.totalMem
            devMemAvail = mi.availMem
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        // 这里读一次就够：之后所有地方都读缓存字段（同步方法不能做 IO）
        prefs.let {
            sourceKey = it.getString(KEY_SOURCE, sourceKey) ?: sourceKey
            cfg = it.getFloat(KEY_CFG, cfg).coerceIn(1f, 4f)
            timesteps = it.getInt(KEY_STEPS, timesteps).coerceIn(4, 32)
            voiceDesign = it.getString(KEY_VOICE, "") ?: ""
        }
        refreshDeviceStats()
        logLine("插件进程启动 · $devCores 核 · 内存 ${gb(devMemAvail)}/${gb(devMemTotal)} 可用")
    }

    // ======================================================== 清单

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "voxcpm2",
        displayName = "VoxCPM2 语音合成",
        versionName = "0.4.0",
        author = "visitor257",
        // 真实语音合成 —— 和 Echo 那个 TEST 不同，这个会被宿主的「朗读回答」路由选中
        capabilities = listOf(Capability.TTS),
        modelFamilies = listOf("voxcpm2"),
        // 插件自管权重（v1 约定）
        needsHostModels = false,
        minSdkHint = "建议 8 GB 内存；纯 CPU 推理，5 秒语音约需 1~2 分钟",
    )

    // ======================================================== 引擎

    override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
        val t0 = SystemClock.elapsedRealtime()
        // 卸载后 600 ms 内又点了加载：把"正在释放"清掉，否则界面会一直显示它
        engineUnloading = false

        val chosen = pickUsableSource()
            ?: run {
                logLine("加载中止：两套权重都不完整")
                throw PluginException.modelInvalid(
                    "权重还没下载。请先在插件界面点「下载权重」（约 3.5 GB），" +
                        "或在设置里换成精简源（约 2.8 GB）。"
                )
            }

        if (VoxCpm2Bridge.nativeIsReady()) {
            // 已经加载过（幂等）：不重复 init，直接回报
            return EngineInfo(sampleRate, 1, loadedBytes, SystemClock.elapsedRealtime() - t0)
        }

        // 内存预检：宁可在这里拒绝，也别让系统 OOM kill 掉整个进程
        val need = chosen.totalBytes + 768L * 1024 * 1024
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val limit = if (config.maxMemoryBytes > 0) min(config.maxMemoryBytes, mi.availMem) else mi.availMem
        if (limit < need) {
            logLine("内存不足：可用 ${ModelStore.human(mi.availMem)}，需要 ${ModelStore.human(need)}")
            throw PluginException.outOfMemory(
                "可用内存 ${ModelStore.human(mi.availMem)}，加载需要约 ${ModelStore.human(need)}。" +
                    "先关掉其它应用，或换成精简源。"
            )
        }

        // 宿主（Ponko 2.0.0 起）在插件页给了「推理线程」，连上时传的是真值：
        // 默认 核数-1，夹在 1..6。只有用户选了「自动」（宿主传 0）才退回自己的估算。
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (config.threads > 0) config.threads
        else (cores - 1).coerceIn(2, 6)

        val rc = VoxCpm2Bridge.nativeInit(
            ModelStore.fileFor(this, chosen.baseLm).absolutePath,
            ModelStore.fileFor(this, chosen.acoustic).absolutePath,
            threads,
        )
        if (rc != 0) {
            val err = VoxCpm2Bridge.nativeLastError().ifBlank { "未知原因" }
            logLine("加载失败：$err（${chosen.key} / $threads 线程）")
            throw PluginException.modelInvalid("加载失败：$err")
        }

        sampleRate = VoxCpm2Bridge.nativeSampleRate().takeIf { it > 0 } ?: 48_000
        loadedBytes = chosen.totalBytes
        loadedSourceKey = chosen.key
        loadedThreads = threads
        nativeReady = true

        refreshDeviceStats()
        logLine(
            "引擎就绪：${chosen.key} · ${ModelStore.human(loadedBytes)} · $threads 线程 · " +
                "${sampleRate / 1000} kHz · 加载 ${SystemClock.elapsedRealtime() - t0} ms"
        )
        // 「引擎」那行立刻从"未加载"变成"已就绪"，不然用户点完「合成语音」还得退出再进来
        notifySchemaChanged()
        return EngineInfo(
            sampleRate = sampleRate,
            channelCount = 1,
            modelBytes = loadedBytes,
            loadElapsedMs = SystemClock.elapsedRealtime() - t0,
        )
    }

    override fun closeEngineBlocking() {
        // 幂等：用户点「释放模型」、停用插件、页面销毁、进程回收都会调
        val wasReady = nativeReady
        engineUnloading = wasReady
        // 先让界面从"已就绪"切到"正在释放"。否则卸载的几百毫秒里，
        // 宿主功能区在说"正在释放模型"，插件页却已经写"未加载 · 点加载"—— 自相矛盾
        if (wasReady) notifySchemaChanged()

        runCatching { VoxCpm2Bridge.nativeFree() }
            .onFailure { Log.w(TAG, "nativeFree 失败", it) }
        nativeReady = false
        loadedBytes = 0L
        loadedSourceKey = ""
        loadedThreads = 0
        if (wasReady) {
            refreshDeviceStats()
            logLine("引擎已释放 · 内存回到 ${gb(devMemAvail)} 可用")
            // 延迟一会儿再喊一声，把「正在释放内存…」换成「未加载」。
            //
            // ⚠ 这一声只是给**插件页自己**用的。宿主从 2.0.0（2026-10-22 那批）
            // 起会自己轮询 `getStatus().state` 直到 UNLOADED，不再看这个通知来
            // 复位按钮 —— 以前它只做一次 250 ms 抽查，靠这里补，现在不用了。
            // 之所以还要延迟：SDK 是在 closeEngineBlocking **返回之后**才把
            // state 设成 UNLOADED 的，立刻通知的话宿主读到的状态还是旧的。
            Thread({
                try {
                    Thread.sleep(UNLOAD_SETTLE_MS)
                } catch (_: InterruptedException) {
                    // 进程在退出，没必要处理
                }
                engineUnloading = false
                notifySchemaChanged()
            }, "voxcpm2-unload").apply { isDaemon = true; start() }
        }
    }

    /** 选一套可用权重：优先用户在界面里选的那套，不行就退到另一套 */
    private fun pickUsableSource(): ModelStore.Source? {
        val selected = ModelStore.sourceAt(ModelStore.sourceIndex(sourceKey))
        if (ModelStore.isSourceReady(this, selected)) return selected
        return ModelStore.SOURCES.firstOrNull { ModelStore.isSourceReady(this, it) }
    }

    // ======================================================== 合成

    override fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle {
        if (!nativeReady || !VoxCpm2Bridge.nativeIsReady()) throw PluginException.notReady()

        val rawText = req.text.trim()
        if (rawText.isEmpty()) throw PluginException.invalidRequest("文本为空")

        val mode = req.mode
        if (mode !in 0..2) throw PluginException.unsupported("不支持的合成模式：$mode")

        // 语音设计靠文本前缀实现，不是独立参数（见 SDK 里 SynthesisRequest 的注释）
        val design = req.voiceDescription.trim().ifBlank { voiceDesign.trim() }
        val text = if (mode == 0 && design.isNotEmpty() && !rawText.startsWith("(")) {
            "($design)$rawText"
        } else {
            rawText
        }

        val cache = File(cacheDir, "synth").apply { mkdirs() }
        var refWav = ""
        var promptText = ""

        if (mode == 1 || mode == 2) {
            val fd = req.referenceAudio
                ?: throw PluginException.invalidRequest("这个模式需要一段参考音频")

            // 宿主把用户选的文档 open 成 FD 交过来。Binder 传参时系统 dup 过，
            // 所以我们手上这份是独立句柄、由我们负责关。
            //
            // 但不要直接拿它去解码：MediaExtractor 需要随机 seek，而 SAF 背后
            // 可能是个管道（网盘里的文档）。先复制到自己目录，再按本地路径解码。
            out.stage(Stage.LOADING, "读取参考音频")
            val raw = File(cache, "ref_in_${System.currentTimeMillis()}")
            val copied = try {
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                    raw.outputStream().use { sink ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_REFERENCE_BYTES) {
                                throw IOException(
                                    "文件超过 ${MAX_REFERENCE_BYTES / 1024 / 1024} MB，参考音频只要 5~10 秒"
                                )
                            }
                            sink.write(buf, 0, n)
                        }
                        total
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "参考音频读取失败", t)
                raw.delete()
                throw PluginException.invalidRequest(
                    "参考音频读不出来：${t.message ?: t.javaClass.simpleName}。换一段本地音频试试。"
                )
            }
            if (copied <= 0L) {
                raw.delete()
                throw PluginException.invalidRequest("参考音频是空文件")
            }
            logLine("参考音频 ${ModelStore.human(copied)} 已复制到缓存")

            out.stage(Stage.LOADING, "解码参考音频")
            val wav = File(cache, "ref_${System.currentTimeMillis()}.wav")
            val ok = AudioUtil.toReferenceWav(raw.absolutePath, wav)
            raw.delete()
            if (!ok) {
                wav.delete()
                logLine("参考音频解码失败")
                throw PluginException.invalidRequest(
                    "参考音频解码失败。换一段 5~10 秒、能正常播放的音频试试。"
                )
            }
            refWav = wav.absolutePath

            if (mode == 2) {
                promptText = req.referenceTranscript.trim()
                if (promptText.isEmpty()) {
                    throw PluginException.invalidRequest("「极致克隆」需要参考音频的逐字稿")
                }
            }
        }

        out.stage(Stage.TEXT_ENCODE, "分析文本（${rawText.length} 字）")
        out.throwIfCancelled()

        val outFile = File(cache, "out_${System.currentTimeMillis()}.wav")
        val seed = if (req.seed != 0) req.seed else (System.nanoTime() and 0x7fffffff).toInt()
        val useCfg = if (req.cfg > 0f) req.cfg else cfg
        val useSteps = if (req.timesteps > 0) req.timesteps else timesteps

        lastMode = mode
        logLine(
            "开始合成：${rawText.length} 字 · mode=$mode · cfg=$useCfg · steps=$useSteps · " +
                "seed=$seed" + if (design.isNotEmpty()) " · 有音色描述" else ""
        )

        // 进度轮询：原生侧是阻塞调用，没有回调，只能这样量
        val finished = AtomicBoolean(false)
        val poller = Thread({
            var announcedLoad = false
            while (!finished.get()) {
                if (out.isCancelled) VoxCpm2Bridge.nativeCancel()
                when (VoxCpm2Bridge.nativeStage()) {
                    VoxCpm2Bridge.STAGE_GENERATING -> {
                        if (!announcedLoad) {
                            announcedLoad = true
                            out.stage(Stage.DECODE, "开始生成…")
                        }
                        val sec = VoxCpm2Bridge.nativeAudioSeconds()
                        if (sec > 0.0) {
                            out.stage(Stage.DECODE, "已生成 ${"%.1f".format(sec)} 秒")
                        }
                    }
                }
                try {
                    Thread.sleep(400)
                } catch (e: InterruptedException) {
                    return@Thread
                }
            }
        }, "voxcpm2-progress").apply { isDaemon = true; start() }

        val tGen = SystemClock.elapsedRealtime()
        val ok = try {
            VoxCpm2Bridge.nativeGenerate(
                text, mode, refWav, promptText,
                useCfg, useSteps, seed, 0,
                outFile.absolutePath,
            )
        } finally {
            finished.set(true)
            runCatching { poller.join(1_500) }
        }
        val genMs = SystemClock.elapsedRealtime() - tGen

        if (out.isCancelled) {
            logLine("已取消（生成 ${genMs} ms）")
            // 宿主在结果/错误回调里**不会**重画插件页（只更新功能区的提示），
            // 所以这一页的内容要自己喊一声，否则日志会停在"开始合成"。
            notifySchemaChanged()
            throw PluginException(ErrCode.CANCELLED, "已取消")
        }

        if (!ok || !outFile.isFile || outFile.length() <= 44) {
            val nativeErr = VoxCpm2Bridge.nativeLastError()
            Log.e(TAG, "生成失败：$nativeErr")
            logLine("生成失败：${nativeErr.ifBlank { "引擎没有产出音频" }}")
            notifySchemaChanged()
            throw PluginException(
                ErrCode.PLUGIN_INTERNAL,
                "生成失败：${nativeErr.ifBlank { "引擎没有产出音频" }}",
                retryable = true,
            )
        }

        out.stage(Stage.AUDIO_DECODE, "封装 WAV")
        val seconds = AudioUtil.wavDurationSeconds(outFile)

        // 记住这一次，界面上的「上次」状态行直接读它（不用再算）
        lastAudioSec = seconds
        lastGenMs = genMs
        lastRtf = if (seconds > 0.05) genMs / 1000.0 / seconds else 0.0
        lastMode = mode
        refreshDeviceStats()
        logLine(
            "完成：${"%.1f".format(seconds)} s 音频 / ${genMs} ms / " +
                "RTF ${"%.1f".format(lastRtf)} · 模式 $mode"
        )
        // 让"上次合成"状态行和日志立刻出现，不用等用户退出再进来
        notifySchemaChanged()

        // 清掉旧产物，只留最近 3 个 —— 插件缓存没人替我们清
        runCatching {
            cache.listFiles { f -> f.name.startsWith("out_") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(3)
                ?.forEach { it.delete() }
        }

        // 宿主的「诊断信息」区会原样显示这段（用户能长按复制），所以只放对排查有用的：
        // 模式、量化、线程、参数、采样率、RTF。不放路径，也不放用户文本原文。
        val rtf = if (seconds > 0.05) genMs / 1000.0 / seconds else 0.0
        return ResultBundle(
            requestId = req.requestId,
            sampleRate = sampleRate,
            channelCount = 1,
            durationMs = (seconds * 1000).toLong(),
            elapsedMs = genMs,
            audioFormat = PluginConst.FMT_WAV,
            audio = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_ONLY),
            inlineAudio = null,
            debugInfo = "voxcpm2 mode=$mode src=$loadedSourceKey threads=$loadedThreads " +
                "cfg=$useCfg steps=$useSteps seed=$seed sr=$sampleRate " +
                "text=${rawText.length}chars design=${if (design.isNotEmpty()) "yes" else "no"} " +
                "audio=${"%.1f".format(seconds)}s gen=${genMs}ms rtf=${"%.2f".format(rtf)}",
        )
    }

    // ======================================================== 界面

    /**
     * 功能区那几个按钮叫什么（SDK v3 起可改，只改文字）。
     *
     * 宿主默认是「加载引擎 / 卸载引擎 / 合成 / 播放 / 停止 / 保存」——
     * 前两个是按"有个引擎要加载"写的，放到这个插件上不贴：
     *
     * - 这里加载的是 **3.5 GB 的模型**，说「模型」比「引擎」具体；卸载之后
     *   东西还在磁盘上，说「释放模型」比「卸载」准确（宿主的「卸载」很容易被
     *   理解成卸载插件）。
     * - 「合成」改成「合成语音」，和上面两个「…模型」排在一起时更成句。
     *
     * 播放 / 停止 / 保存用宿主默认：「播放」本来就准确。
     *
     * ## 2026-10-22 宿主把合成与播放拆开了
     *
     * 以前合成完会自动播，同一个按钮因此有了第二重含义（还没听过 = 听，
     * 听过了 = 再算一遍），菜加了 `preview.again` 让它变成「重新合成」。
     * 现在合成只产文件、不播放，「点几次都是同一个动作」，那个 key 被删了，
     * `ActionLabel.PREVIEW` 也改名成 [ActionLabel.REASONING]。
     * 所以这里不再有 `.again` 那一行，也不该有 —— 但**说明区必须告诉用户
     * 合成完不出声、要去点「播放」**，否则会以为没成功。
     *
     * ⚠ 长度上限 [ActionLabel.MAX_LEN]（12 字符，超了整个丢弃）；这个方法在
     * 打开界面时于 Binder 线程调用，所以只能是常量 map，不能做 IO。
     */
    override fun buildActionLabels(): Map<String, String> = mapOf(
        ActionLabel.ENGINE_LOAD to "加载模型",
        ActionLabel.ENGINE_UNLOAD to "释放模型",
        ActionLabel.REASONING to "合成语音",
    )

    /**
     * 宿主渲染的插件页。
     *
     * 和独立的测试 App 取向不同：那一版是"操作台"，把所有旋钮摊在一屏；
     * 这一版是"状态 + 缺什么补什么"—— 真正的操作（文本、模式、参考音频、合成）
     * 由宿主在页面顶部的「功能」区提供，插件不重复摆一遍。
     *
     * 分区按**使用频率**分页（SDK v5 的标签页），页内仍按使用顺序排：
     * 状态（每次进来都看：能不能用 + 上次的结果）→
     * 参数（改一次就很少动：1 权重、2 合成参数）→
     * 说明（几乎不看）→ 日志（出问题才看）。
     *
     * 分页的另一个好处在这里：日志是整页最高的一块（160 dp），
     * 以前它把页面顶得很长，现在只在需要时出现。
     *
     * ⚠ 这个方法跑在**宿主的 Binder 线程**上，必须快：只读 volatile 缓存 +
     * 几次 `File.length()`。没有网络、没有解码、不调用系统服务
     * （内存信息在 [refreshDeviceStats] 里提前取好）。
     */
    override fun buildSettingsSchema(): UiSchema {
        val src = ModelStore.sourceAt(ModelStore.sourceIndex(sourceKey))
        val ready = ModelStore.isSourceReady(this, src)
        val installed = ModelStore.installedBytes(this)

        val items = mutableListOf<UiItem>()

        // ==================== v5：标签页 ====================
        //
        // 这一页以前是一条长竖列：状态 → 试听 → 权重 → 参数 → 说明 → 日志，
        // 手机上要滚三四屏。SDK v5 的 TABS_* 把它拆成四页。
        //
        // 切换是纯 visibility（宿主一次把所有页建好），所以切回来时输入框内容、
        // 滑块值、滚动位置都还在。选中哪一页由宿主按 `TABS_START.key` 记住 ——
        // 整页重画（加载完/合成开始/合成完）之后用户还停在他原来那一页。
        //
        // 代价：插件从此报 v5，老宿主（v4 及更早）会把这三个类型逐个降级成
        // 「未知控件」文字块，界面会散（不会崩）。README 里写明了。
        items += UiItem(key = "tabs_main", type = UiType.TABS_START, label = "")

        // ---- 页 1 · 状态（一眼判断能不能用）----
        items += UiItem(key = "tab_state", type = UiType.TAB, label = "状态")
        items += UiItem(
            key = "st_engine", type = UiType.STATUS, label = "引擎",
            value = when {
                engineUnloading -> "正在释放内存…"
                nativeReady -> "已就绪 · ${sampleRate / 1000} kHz · $loadedThreads 线程"
                // 宿主从 2.0.0 起不再替所有插件自动加载（见插件文档 §6.1）：
                // 这个插件不声明 AUTOLOAD_ENGINE，所以"未加载"是正常起点，不是故障
                ready -> "未加载 · 点上面「加载模型」，或直接点「合成语音」"
                else -> "未加载 · 先下载权重"
            },
        )
        items += UiItem(
            key = "st_weights", type = UiType.STATUS, label = "权重",
            value = when {
                ready -> "已就绪 · ${ModelStore.human(src.totalBytes)}"
                installed > 0 -> "不完整 · 已有 ${ModelStore.human(installed)}"
                else -> "未下载"
            },
        )
        items += UiItem(
            key = "st_disk", type = UiType.STATUS, label = "存储",
            value = "剩 ${ModelStore.human(ModelStore.usableSpace(this))} / 需 ${ModelStore.human(src.totalBytes)}",
        )
        items += UiItem(
            key = "st_device", type = UiType.STATUS, label = "设备",
            value = "$devCores 核 · ${devAbi.ifBlank { "未知 ABI" }} · ${gb(devMemAvail)}",
        )
        if (lastAudioSec > 0.0) {
            items += UiItem(
                key = "st_last", type = UiType.STATUS, label = "上次合成",
                value = "${modeName(lastMode)} · ${"%.1f".format(lastAudioSec)}s / " +
                    "${lastGenMs / 1000}s · RTF ${"%.1f".format(lastRtf)}",
            )
        }
        if (dlNotice.isNotEmpty()) {
            items += UiItem(key = "st_notice", type = UiType.STATUS, label = "提示", value = dlNotice)
        }

        items += UiItem(key = "sec_play", type = UiType.SECTION, label = "试听")
        items += UiItem(
            key = "audio", type = UiType.AUDIO, label = "最近一次合成",
            help = "和上面「播放」按钮是同一个播放器（宿主提供的），这里能拖进度。" +
                "还没合成过时是灰的。",
        )

        // ---- 页 2 · 参数 ----
        //
        // 状态与参数分两页：
        // 参数改一次就很少动，状态每次进来都要看。
        items += UiItem(key = "tab_param", type = UiType.TAB, label = "参数")

        // ==================== 1 · 权重 ====================
        items += UiItem(key = "sec_w", type = UiType.SECTION, label = "1 · 权重")

        items += UiItem(
            key = "source", type = UiType.CHOICE, label = "下载源",
            help = if (ready) "当前源已就绪，可以直接合成" else src.note,
            value = src.key,
            options = ModelStore.SOURCES.map {
                UiOption(it.key, "${ModelStore.human(it.totalBytes)} · ${sourceShort(it.key)}")
            },
            enabled = !downloading,
        )

        // 逐文件状态。独立版里这块最有用：能分清"压根没下"和"下到一半断了"
        src.files().forEach { spec ->
            val f = ModelStore.fileFor(this, spec)
            val part = File(f.absolutePath + ".part")
            val state = when {
                ModelStore.isReady(this, spec) -> "已就绪 · ${ModelStore.human(spec.bytes)}"
                part.isFile -> "断点 · ${ModelStore.human(part.length())} / ${ModelStore.human(spec.bytes)}"
                f.isFile -> "不完整 · ${ModelStore.human(f.length())}"
                else -> "未下载 · ${ModelStore.human(spec.bytes)}"
            }
            items += UiItem(
                key = "$KEY_FILE_PREFIX${spec.fileName}", type = UiType.STATUS,
                label = spec.label, value = state,
            )
        }

        // 没下过也没在下的时候不摆一条 0% 的空进度条
        if (downloading || installed > 0) {
            items += UiItem(
                key = KEY_DL_PROGRESS, type = UiType.PROGRESS, label = "下载进度",
                help = if (downloading && dlDetail.isNotEmpty()) dlDetail
                else "支持断点续传：中断后再点一次会从断点接着下",
                value = "%.1f".format(if (downloading) dlPercent else if (ready) 100.0 else 0.0),
            )
        }

        items += UiItem(
            key = "dl_start", type = UiType.ACTION,
            label = when {
                downloading -> "下载中…"
                ready -> "重新下载（覆盖现有文件）"
                installed > 0 -> "继续下载"
                else -> "下载全部权重"
            },
            help = if (downloading) "下载在独立线程里跑，可以退出这一页，回来还在下"
            else "共 ${ModelStore.human(src.totalBytes)}；只连所选源，不下多余文件",
            enabled = !downloading,
            primary = !ready,
        )
        if (downloading) {
            items += UiItem(key = "dl_cancel", type = UiType.ACTION, label = "取消下载")
        }
        if (installed > 0 && !downloading) {
            items += UiItem(
                key = "dl_delete", type = UiType.ACTION, label = "删除已下载权重",
                help = "释放 ${ModelStore.human(installed)}。引擎正在用时删不掉，先在卡片上停用插件",
            )
        }

        items += UiItem(
            key = "src_note", type = UiType.INFO,
            label = "权重不进 APK，下到插件自己的私有目录，卸载插件时一起删掉。\n" +
                "标准源仓库声明 apache-2.0；精简源是第三方 GGUF 转换件、仓库没填许可（上游权重仍是 " +
                "apache-2.0）—— 介意许可就留在标准源。",
        )

        // ==================== 2 · 合成参数 ====================
        items += UiItem(key = "sec_p", type = UiType.SECTION, label = "2 · 合成参数")
        items += UiItem(
            key = "param_intro", type = UiType.INFO,
            label = "这一组只是默认值。要合成的文本、合成模式、参考音频、随机种子在页面顶部的「功能」区，" +
                "由 Ponko 提供 —— 那边填了就用那边的，没填才回落到这里。",
        )

        items += UiItem(
            key = "voice", type = UiType.TEXTAREA, label = "语音设计描述（默认）",
            help = "只在上面没填描述时生效。会拼成「(描述)正文」，相当于凭空造一个音色；留空用模型自带音色",
            value = voiceDesign,
        )
        items += UiItem(
            key = "cfg", type = UiType.TEXT, label = "CFG 引导强度",
            help = cfgHint.ifEmpty { "1.0 ~ 4.0，官方建议 2.0。越大越贴文本、越小越自由" },
            value = trimNum(cfg.toDouble()),
        )
        items += UiItem(
            key = "steps", type = UiType.TEXT, label = "扩散步数",
            help = stepsHint.ifEmpty { "4 ~ 24 的整数，10 起步；调高更细腻，但也更慢" },
            value = timesteps.toString(),
        )
        if (nativeReady) {
            items += UiItem(
                key = "st_effective", type = UiType.STATUS, label = "将使用",
                help = "功能区那边填了 cfg / 步数时以那边为准，这里只是回落值",
                value = "cfg ${trimNum(cfg.toDouble())} · steps $timesteps · $loadedThreads 线程",
            )
        }

        // ---- 页 3 · 说明 ----
        items += UiItem(key = "tab_doc", type = UiType.TAB, label = "说明")

        // ==================== 说明 ====================
        items += UiItem(
            key = "help", type = UiType.INFO,
            label = "**手机纯 CPU 推理，很慢**：5 秒语音约 1~2 分钟，生成中点「停止」可以打断。\n\n" +
                "参考音频要 5~10 秒、单一说话人、尽量干净；任意格式都行，插件自己解码成 16 kHz " +
                "单声道，超过 64 MB 会拒绝。\n\n" +
                "三种模式：普通合成（可在文本前用括号写音色描述）、声音克隆（只给参考音频）、" +
                "极致克隆（参考音频 + 逐字稿，最像）。\n\n" +
                "首次「加载模型」约 10~30 秒，之后一直留着（重复点只会立刻返回）。" +
                "想腾内存点「释放模型」，或在卡片上停用插件。\n\n" +
                "「合成语音」只跑推理、产出文件，**不会自动出声** —— 合成完到「试听」那条播放器" +
                "上点播放（拖动进度条能跳到某一句），或者直接点上面的「播放」，两者是同一个播放器。" +
                "听第二遍不用重新推理，它直接放上一次的文件（重新推理要再等一两分钟）。",
        )
        items += UiItem(
            key = "license", type = UiType.INFO,
            label = "许可：本插件 MIT。推理引擎 llama.cpp-omni / ggml 为 MIT，版权归 The ggml " +
                "authors；模型 VoxCPM2 为 Apache-2.0，版权归 OpenBMB。权重由你自行下载，不随插件分发。\n\n" +
                "声音使用：用他人音频做音色克隆可能侵犯声音权益（《民法典》第 1023 条），" +
                "请只克隆自己或已获授权的音色；对外发布合成音频还需按深度合成相关规定做标识。",
        )

        // ---- 页 4 · 日志 ----
        //
        // 以前是「可折叠分组 + 一段 INFO 快照」：内容靠整页重建刷新，日志多了
        // 就是反复拆页面。现在换成 `UiType.LOG` —— 固定高度、内部滚动、
        // 可长按复制，新行走 [pushLog] 增量送。
        //
        // `value` 只是**初始内容**（进页面/整页重建时用），之后的变化一律走
        // `notifyUiPatch`，两边的 key 必须都是 [KEY_LOG]。
        items += UiItem(key = "tab_log", type = UiType.TAB, label = "日志")
        val logs = logSnapshotForSchema()
        items += UiItem(
            key = KEY_LOG, type = UiType.LOG,
            value = logs,
            help = "长按可复制。只保留最近 $LOG_KEEP 行；这和 logcat 是两回事，" +
                "两个都有：adb logcat -s VoxCpm2Plugin",
        )
        items += UiItem(
            key = "log_clear", type = UiType.ACTION, label = "清空日志",
            enabled = logs.isNotEmpty(),
        )

        // 页签组收尾。缺这一行的话，宿主会继续把后面的项往最后一页里塞
        items += UiItem(key = "tabs_main_end", type = UiType.TABS_END)

        return UiSchema(intro = buildIntro(ready, installed), items = items)
    }

    /**
     * 页面顶部那段。
     *
     * 不只写自我介绍 —— 第一句永远是「**现在该干什么**」。
     * 插件页比独立 App 少一大块操作区，用户进来最容易迷路的就是"下一步"。
     */
    private fun buildIntro(ready: Boolean, installed: Long): String {
        val next = when {
            downloading -> "正在下载权重（${"%.0f".format(dlPercent)}%），下完就能合成。"
            ready && nativeReady -> "引擎已就绪 —— 去上面的「功能」区点「合成语音」，" +
                "合成完到「状态」页的播放条上听（合成不会自动出声）。"
            ready -> "权重已就绪，模型还没加载。点上面「加载模型」（约 10~30 秒），" +
                "或者直接点「合成语音」—— 它会自己先加载再合成。"
            installed > 0 -> "权重还没下完（已有 ${ModelStore.human(installed)}）。" +
                "到「参数」页的「1 · 权重」点「继续下载」。"
            else -> "还没下载权重。到「参数」页下载（约 3.5 GB），下完才能合成。"
        }
        return "VoxCPM2（OpenBMB，2B）本地语音合成：30 种语言、9 种中文方言，" +
            "音色设计与零样本克隆，48 kHz，除下载权重外全程离线。\n\n**$next**"
    }

    private fun sourceShort(key: String): String = when (key) {
        "licensed" -> "标准源 Q8_0"
        "compact" -> "精简源 Q4_K_M"
        else -> key
    }

    private fun modeName(m: Int): String = when (m) {
        0 -> "普通合成"
        1 -> "声音克隆"
        2 -> "极致克隆"
        else -> "—"
    }

    /** 去掉末尾多余的 0：「2.00」→「2」，「0.70」→「0.7」 */
    private fun trimNum(v: Double): String =
        "%.2f".format(v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun gb(bytes: Long): String = String.format("%.1f GB", bytes / 1e9)

    override fun onSettingChanged(key: String, value: String) {
        when (key) {
            "source" -> {
                sourceKey = value
                prefs.edit().putString(KEY_SOURCE, value).apply()
                dlNotice = ""
                logLine("切换下载源：$value")
                notifySchemaChanged()
            }
            "voice" -> {
                voiceDesign = value
                prefs.edit().putString(KEY_VOICE, value).apply()
            }
            "cfg" -> {
                // 数字输入框：宿主只在失焦时把原文送过来，所以校验和回显全在这里做。
                // 非法输入**不改值**，但要在 help 里说清楚"保留了原来那个"，不能默默吞掉。
                val raw = value.trim()
                val v = raw.toFloatOrNull()
                if (v == null || v < 1f || v > 4f) {
                    cfgHint = if (raw.isEmpty()) "不能为空，保留 ${trimNum(cfg.toDouble())}"
                    else "「${raw.take(8)}」不是 1.0~4.0 的数，保留 ${trimNum(cfg.toDouble())}"
                } else {
                    // 只留两位小数：界面上的值和真正用的值必须是同一个，否则显示 2.33 实际跑 2.3333
                    cfg = Math.round(v * 100f) / 100f
                    cfgHint = ""
                    prefs.edit().putFloat(KEY_CFG, cfg).apply()
                }
                notifySchemaChanged()
            }
            "steps" -> {
                val raw = value.trim()
                val v = raw.toIntOrNull()
                if (v == null || v < 4 || v > 24) {
                    stepsHint = if (raw.isEmpty()) "不能为空，保留 $timesteps"
                    else "「${raw.take(8)}」不是 4~24 的整数，保留 $timesteps"
                } else {
                    timesteps = v
                    stepsHint = ""
                    prefs.edit().putInt(KEY_STEPS, timesteps).apply()
                }
                notifySchemaChanged()
            }
        }
    }

    override fun onActionInvoked(key: String) {
        when (key) {
            "dl_start" -> startDownload()
            "dl_cancel" -> {
                dlCancel.set(true)
                dlNotice = "正在取消…"
                logLine("用户点取消下载")
                notifySchemaChanged()
            }
            "dl_delete" -> {
                if (nativeReady) {
                    dlNotice = "引擎正在使用权重，请先在插件卡片上停用，再回来删除"
                    logLine("删除被拒：引擎正在使用权重")
                } else {
                    val freed = ModelStore.deleteAll(this)
                    dlNotice = "已删除权重，释放 ${ModelStore.human(freed)}"
                    dlPercent = 0.0
                    logLine("已删除权重，释放 ${ModelStore.human(freed)}")
                }
                notifySchemaChanged()
            }
            "log_clear" -> {
                synchronized(logLock) {
                    logLines.clear()
                    pushedLines = 0
                    logTrimmed = false
                }
                notifySchemaChanged()
            }
        }
    }

    // ======================================================== 下载

    /**
     * 下载必须**另开线程**，不能占着基类的 worker：
     * worker 是单线程的，几十分钟的下载会把 `dl_cancel` 这个 action 一起堵死，
     * 用户就永远取消不了。
     */
    private fun startDownload() {
        if (downloading) return
        val src = ModelStore.sourceAt(ModelStore.sourceIndex(sourceKey))
        val need = src.totalBytes
        val free = ModelStore.usableSpace(this)
        if (free < need + 256L * 1024 * 1024) {
            dlNotice = "空间不够：需要 ${ModelStore.human(need)}，只剩 ${ModelStore.human(free)}"
            logLine("下载中止：$dlNotice")
            notifySchemaChanged()
            return
        }

        downloading = true
        dlCancel.set(false)
        dlPercent = 0.0
        dlDetail = "准备中…"
        dlNotice = ""
        logLine("开始下载 ${src.key} · ${ModelStore.human(src.totalBytes)}")
        notifySchemaChanged()

        val t = Thread({
            var failed: String? = null
            var done = 0L
            var loggedPct = 0.0
            try {
                for (spec in src.files()) {
                    if (dlCancel.get()) break
                    val base = done
                    val r = ModelStore.download(this, src, spec, dlCancel) { got, total ->
                        val overall = ((base + got).toDouble() / src.totalBytes * 100.0)
                        dlPercent = overall.coerceIn(0.0, 100.0)
                        dlDetail = "${spec.label}：${ModelStore.human(got)} / ${ModelStore.human(total)}" +
                            "（总进度 ${"%.0f".format(overall)}%）"
                        // 日志每 10% 记一行 —— 日志框现在是增量追加的，不心疼；
                        // 但 10% 一条已经够看清进度了，再密就是噪声
                        if (overall - loggedPct >= 10.0) {
                            loggedPct = overall
                            logLine("下载 ${"%.0f".format(overall)}% · ${spec.label} " +
                                "${ModelStore.human(got)}/${ModelStore.human(total)}")
                        }
                        // 进度**走原地补丁**，不再整页重建：这个回调一秒来几十次，
                        // 每次重建都会把日志滚动位置和正在填的音色描述冲掉
                        pushDownloadProgress(spec, got, total)
                    }
                    if (r.isFailure) {
                        failed = r.exceptionOrNull()?.message ?: "下载失败"
                        break
                    }
                    done += spec.bytes
                    // 一个文件下完了：这一行从"下载中"变成"已就绪"，
                    // 得整页刷一次才对得上（补丁只能改值，改不了行的存在与否）
                    notifySchemaChanged()
                }
            } catch (t: Throwable) {
                failed = t.message ?: "下载异常"
            } finally {
                downloading = false
                dlDetail = ""
                dlNotice = when {
                    dlCancel.get() -> "已取消，下次继续会从断点续传"
                    failed != null -> "下载失败：$failed"
                    ModelStore.isSourceReady(this, src) -> "下载完成（${ModelStore.human(src.totalBytes)}）。" +
                        "现在可以点上面「加载模型」，或者直接点「合成语音」（会先加载再合成）。"
                    else -> "下载中断，再点一次「继续下载」可以接着下"
                }
                refreshDeviceStats()
                logLine("下载结束：$dlNotice")
                notifySchemaChanged()
            }
        }, "voxcpm2-download").apply { isDaemon = true; start() }
        dlThread = t
    }

    override fun onDestroy() {
        dlCancel.set(true)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoxCpm2Plugin"
        private const val PREFS = "voxcpm2"
        private const val KEY_SOURCE = "source"
        private const val KEY_CFG = "cfg"
        private const val KEY_STEPS = "steps"
        private const val KEY_VOICE = "voice"

        /** 参考音频上限：只要 5~10 秒，给足余地但不让用户把几百 MB 的音频塞进来 */
        private const val MAX_REFERENCE_BYTES = 64L * 1024 * 1024

        /** 内存里最多留多少行日志（再多也就是滚动噪声） */
        private const val LOG_KEEP = 120

        // ---- 要打补丁的 key：**必须和 schema 里那个 UiItem.key 一模一样** ----
        // 对不上宿主就静默忽略（它不去猜"你大概想改哪个"），所以只写在这个常量里，
        // 别在 schema 和补丁两处各写一遍字面量。
        private const val KEY_LOG = "log_body"
        private const val KEY_DL_PROGRESS = "dl_progress"
        private const val KEY_FILE_PREFIX = "file_"

        /**
         * 日志推送间隔。
         *
         * `onUiPatch` 是 oneway、没有背压，宿主处理慢于插件时消息会在它那边排队。
         * 250 ms 对人眼已经是"实时"了，再密只是压 Binder 配额。
         */
        private const val LOG_PUSH_INTERVAL_MS = 250L

        /** 下载进度推送间隔（回调本身每秒几十次，同理节流） */
        private const val DL_PUSH_INTERVAL_MS = 300L

        /**
         * 卸载后延迟多久再通知界面重画。
         *
         * SDK 在 [closeEngineBlocking] 返回之后才把 state 设成 UNLOADED。
         * 这一声只影响插件页自己那行「引擎」（从"正在释放"切到"未加载"）：
         * 宿主那边已经改成自己轮询 `getStatus().state`，不看这个通知。
         */
        private const val UNLOAD_SETTLE_MS = 600L
    }
}
