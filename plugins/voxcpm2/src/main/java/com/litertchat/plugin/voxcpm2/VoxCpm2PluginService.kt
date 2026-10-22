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
     * 正在复制哪个槽位（MODEL_SLOT 的 key）；空 = 没有导入在进行。
     *
     * 复制 1.7 GB 要几十秒，这期间界面必须说"正在复制…"而不是停在"未导入" ——
     * 否则用户会以为按钮没反应，再点一次。同时它也是互斥标记：
     * 导入进行中不许再点另一个槽位（两个大文件同时读会把 IO 挤死）。
     */
    @Volatile private var importingRole = ""

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

    /**
     * 推理进度：推给参数页/结果页那条进度条（原地补丁）。
     *
     * ## 为什么是"估计值"
     *
     * 原生侧只暴露 `nativeAudioSeconds()`（已经生成了多少秒音频），没有
     * 总步数、也没有 ETA。总长只能按字数估（中文约 4.5 字/秒），所以进度条
     * 会走得快一点或慢一点，最后一段可能"卡"在 90% 然后直接跳满。
     *
     * 这仍然比没有强：用户要判断的是"它还在动吗"，不是"还剩几秒"。
     * help 里写明了是估计，别把它当精确值。
     */
    private fun pushInferProgress(generatedSec: Double, estSec: Double) {
        val pct = (generatedSec / estSec * 100.0).coerceIn(0.0, 99.0)
        inferPercent = pct
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferPushAt < INFER_PUSH_INTERVAL_MS) return
        lastInferPushAt = now
        notifyUiPatch(KEY_INFER_PROGRESS, "%.1f".format(pct), false)
    }

    /** 文本预览：界面上只显示开头一段，完整文本可能上千字 */
    private fun preview(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 40) oneLine else oneLine.take(40) + "…（共 ${oneLine.length} 字）"
    }

    /** 历史快照（最新在前）。拿副本，避免画界面时和合成线程抢同一把锁 */
    private fun historySnapshot(): List<Record> = synchronized(historyLock) {
        history.toList().reversed()
    }

    // ---- 历史音频（SDK v7：每条 AUDIO 条目可以播自己的文件）----

    /** schema 里那条 AUDIO 的 key。**和 [notifyAudioFile] 里的 key 必须一模一样** */
    private fun historyAudioKey(index: Int): String = "h${index}_audio"

    /** 历史音频目录。插件私有，卸载时系统一起删 */
    private fun histDir(): java.io.File =
        java.io.File(filesDir, "hist").apply { if (!exists()) mkdirs() }

    /**
     * 给一条历史留一份**自己的**音频，并交给宿主绑定到它那一行。
     *
     * 为什么必须复制：宿主只保留"最近一次产物"，再合成一次就被覆盖了；
     * 而历史里的第 3 条永远是第 3 条的音频，不能跟着最新那次跑。
     *
     * 复制和 Binder 回调都在锁外做 —— IO 和跨进程调用不能占着历史锁。
     */
    private fun rememberAudioForHistory(src: java.io.File, index: Int) {
        val dst = try {
            val f = java.io.File(histDir(), "h$index.wav")
            src.copyTo(f, overwrite = true)
            f
        } catch (t: Throwable) {
            Log.w(TAG, "历史音频另存失败：${t.javaClass.simpleName}")
            return
        }
        // 送了才画播放条：没送成功的记录画了也只会放最新那条，那是骗人
        synchronized(historyLock) {
            history.firstOrNull { it.index == index }?.hasAudio = true
        }
        // fd 交给宿主，宿主读完负责关（和 notifyImage 一样，这里不要自己 close）
        notifyAudioFile(historyAudioKey(index), dst)
    }

    /** 一条记录被挤出去时删掉它的音频，不能只删记录 */
    private fun dropHistoryAudio(index: Int) {
        runCatching { java.io.File(histDir(), "h$index.wav").delete() }
    }

    /**
     * 槽位状态文字。空串 = 未导入（宿主据此决定要不要画「清除」按钮）。
     *
     * 不报"文件名"：宿主只把 `value` 画成一行小字，文件名一长就被截断，
     * 反而把真正有用的"多大"挤掉了。用户关心的是"导没导进来、多大"。
     */
    private fun importedState(role: String): String {
        val f = ModelStore.importedFile(this, role)
        return if (f.isFile && f.length() > 0) "已导入 · ${ModelStore.human(f.length())}" else ""
    }

    private fun logCount(): Int = synchronized(logLock) { logLines.size }

    // ---- 设置项（宿主不持久化，所以值由插件自己存）----
    // 下载目标拆成两个维度：**版本**（标准 Q8 / 精简 Q4）和 **镜像**
    // （hf-mirror / HF / 魔搭）。以前是一个平铺的"源"下拉，用户看不出
    // "精度"和"从哪下"是两件事；而且 HF 上根本没有标准版那个仓库，
    // 平铺就必然会出现"点了 404 的组合"。
    @Volatile private var versionKey = ModelStore.STANDARD.key
    @Volatile private var mirrorKey = ModelStore.SOURCES[0].mirror.key

    @Volatile private var cfg = 2.0f
    @Volatile private var timesteps = 10
    @Volatile private var voiceDesign = ""

    /** 数字输入框的校验回显：输入非法时告诉用户"保留了什么"，而不是默默吞掉 */
    @Volatile private var cfgHint = ""
    @Volatile private var stepsHint = ""

    // ---- 种子 / 线程：**只有宿主一份**（SDK v7 起）----
    //
    // 以前插件页也各有一个输入框，结果是两套值：用户在插件页填了种子、又去改宿主的
    // 「种子」，改完没反应 —— 因为插件的值优先。线程同理。
    //
    // 现在宿主的参数区（`PreviewParams.seed` / `threads`）就在「参数」页里、
    // 紧挨着插件自己的参数，用户看到的是一个控件，实际也只有一份值：
    // · 种子 —— 走 `SynthesisRequest.seed`
    // · 线程 —— 走 `EngineConfig.threads`（改完下次加载引擎生效）

    // ---- 设备信息：**不能在 buildSettingsSchema() 里现取** ----
    //
    // getMemoryInfo() 是一次到 system_server 的 Binder 调用，而 buildSettingsSchema()
    // 跑在宿主的 Binder 线程上。在那儿做 IPC 会把宿主的 16 个 Binder 线程占一个，
    // 所以这里缓存，只在 onCreate / 加载完 / 下载完 / 合成完这些**自己线程**的时机刷新。
    @Volatile private var devCores = Runtime.getRuntime().availableProcessors()
    @Volatile private var devMemTotal = 0L
    @Volatile private var devMemAvail = 0L
    @Volatile private var devAbi = ""

    // ---- 上次合成结果（给「结果」页用）----
    @Volatile private var lastAudioSec = 0.0
    @Volatile private var lastGenMs = 0L
    @Volatile private var lastRtf = 0.0
    @Volatile private var lastMode = -1
    @Volatile private var lastSeed = 0
    @Volatile private var lastCfg = 0f
    @Volatile private var lastSteps = 0
    /** 上次合成用的文本，界面只显示开头一段（完整文本可能上千字） */
    @Volatile private var lastText = ""

    // ---- 推理进度（结果页/参数页的进度条）----
    @Volatile private var inferring = false
    @Volatile private var inferPercent = 0.0
    @Volatile private var inferStage = ""

    /** 上次推推理进度的时刻（节流；轮询线程 400 ms 一次，别每次都打 Binder） */
    @Volatile private var lastInferPushAt = 0L

    /**
     * 推理历史，**只在内存里，最多 [HISTORY_KEEP] 条**。
     *
     * 关掉进程就没意义了 —— 那条清单本身（种子 / 参数 / 文本）留着也没法重播，
     * 所以不落盘。但**音频要在**：SDK v7 起宿主支持 `onAudioFile(key, fd, name)`，
     * 录一条就送一份，那一行播的、存的都是它自己（见 [rememberAudioForHistory]）。
     * 音频文件放 `filesDir/hist/`，跟着记录一起删，卸载插件时一起清掉。
     */
    private val history = ArrayDeque<Record>()
    private val historyLock = Any()

    /** 一条历史记录。[hasAudio] = 已经给宿主送过这一条的文件（送了才画播放条） */
    private data class Record(
        val index: Int,
        val seed: Int,
        val cfg: Float,
        val steps: Int,
        val mode: Int,
        val chars: Int,
        val text: String,
        val audioSec: Double,
        val genMs: Long,
        var hasAudio: Boolean = false,
    )

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
            versionKey = ModelStore.version(it.getString(KEY_VERSION, versionKey)).key
            mirrorKey = it.getString(KEY_MIRROR, mirrorKey) ?: mirrorKey
            cfg = it.getFloat(KEY_CFG, cfg).coerceIn(1f, 4f)
            timesteps = it.getInt(KEY_STEPS, timesteps).coerceIn(4, 32)
            voiceDesign = it.getString(KEY_VOICE, "") ?: ""
        }
        // 历史清单只在内存里，进程新建时它必然是空的 ——
        // 上一轮留下的音频文件已经没人能播（没有对应的记录），直接清掉，
        // 否则 filesDir/hist 会一轮轮攒下去，用户还看不见
        runCatching { histDir().listFiles()?.forEach { f -> f.delete() } }
        refreshDeviceStats()
        logLine("插件进程启动 · $devCores 核 · 内存 ${gb(devMemAvail)}/${gb(devMemTotal)} 可用")
    }

    // ======================================================== 清单

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "voxcpm2",
        displayName = "VoxCPM2 语音合成",
        versionName = "0.5.0",
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

        // 本地导入优先，其次任何一套已经下完整的（见 ModelStore.ready）
        val chosen = ModelStore.ready(this, versionKey)
            ?: run {
                logLine("加载中止：本地导入不全，下载的权重也没有一套完整")
                throw PluginException.modelInvalid(
                    "还没有可用的模型文件。到「模型」页：从本地导入两份（BaseLM + Acoustic），" +
                        "或者下载一套（标准 Q8 3.55 GB / 精简 Q4 2.83 GB）。"
                )
            }

        if (VoxCpm2Bridge.nativeIsReady()) {
            // 已经加载过（幂等）：不重复 init，直接回报
            return EngineInfo(sampleRate, 1, loadedBytes, SystemClock.elapsedRealtime() - t0)
        }

        // 内存预检：宁可在这里拒绝，也别让系统 OOM kill 掉整个进程
        val need = chosen.bytes + 768L * 1024 * 1024
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

        // 线程数只有一个来源：宿主参数区的「推理线程」（`EngineConfig.threads`）。
        // 插件页那个输入框在 v0.6.0 删掉了 —— 两份值只会互相覆盖。
        // 宿主默认传「核数-1」并夹在 1..6；只有用户选「自动」（传 0）才由这里估算。
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (config.threads > 0) config.threads
        else (cores - 1).coerceIn(2, 6)

        val rc = VoxCpm2Bridge.nativeInit(
            chosen.baseLm.absolutePath,
            chosen.acoustic.absolutePath,
            threads,
        )
        if (rc != 0) {
            val err = VoxCpm2Bridge.nativeLastError().ifBlank { "未知原因" }
            logLine("加载失败：$err（${chosen.origin} / $threads 线程）")
            throw PluginException.modelInvalid("加载失败：$err")
        }

        sampleRate = VoxCpm2Bridge.nativeSampleRate().takeIf { it > 0 } ?: 48_000
        loadedBytes = chosen.bytes
        loadedSourceKey = chosen.origin
        loadedThreads = threads
        nativeReady = true

        refreshDeviceStats()
        logLine(
            "引擎就绪：${chosen.origin} · ${ModelStore.human(loadedBytes)} · $threads 线程 · " +
                "${sampleRate / 1000} kHz · 加载 ${SystemClock.elapsedRealtime() - t0} ms"
        )
        // 「引擎」那行立刻从"未加载"变成"已就绪"，不然用户点完「开始推理」还得退出再进来
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
        // 种子只有一个来源：宿主参数区的「种子」。0 = 随机，非 0 就能复现同一段演绎
        val seed = req.seed.takeIf { it != 0 }
            ?: (System.nanoTime() and 0x7fffffff).toInt()
        val useCfg = if (req.cfg > 0f) req.cfg else cfg
        val useSteps = if (req.timesteps > 0) req.timesteps else timesteps

        lastMode = mode
        logLine(
            "开始合成：${rawText.length} 字 · mode=$mode · cfg=$useCfg · steps=$useSteps · " +
                "seed=$seed" + if (design.isNotEmpty()) " · 有音色描述" else ""
        )

        // 进度条的"总长"只能估：原生侧只给"已生成多少秒"，不给总步数。
        // 中文正常语速约 4.5 字/秒，取 0.22 秒/字；估偏了也只是进度条走得快慢，
        // 不会让任何实际参数变化 —— 所以 help 里明说是估计值。
        val estSec = (rawText.length * 0.22).coerceAtLeast(2.0)
        inferring = true
        inferPercent = 0.0
        inferStage = "分析文本"
        notifyUiPatch(KEY_INFER_PROGRESS, "0.0", false)

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
                            pushInferProgress(sec, estSec)
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
        inferring = false
        inferStage = ""

        if (out.isCancelled) {
            logLine("已取消（生成 ${genMs} ms）")
            inferPercent = 0.0
            // 宿主在结果/错误回调里**不会**重画插件页（只更新功能区的提示），
            // 所以这一页的内容要自己喊一声，否则日志会停在"开始合成"。
            notifySchemaChanged()
            throw PluginException(ErrCode.CANCELLED, "已取消")
        }

        if (!ok || !outFile.isFile || outFile.length() <= 44) {
            val nativeErr = VoxCpm2Bridge.nativeLastError()
            Log.e(TAG, "生成失败：$nativeErr")
            logLine("生成失败：${nativeErr.ifBlank { "引擎没有产出音频" }}")
            inferPercent = 0.0
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
        lastSeed = seed
        lastCfg = useCfg
        lastSteps = useSteps
        lastText = rawText
        inferPercent = 100.0

        var recordIndex = 0
        val evicted = ArrayList<Int>()
        synchronized(historyLock) {
            recordIndex = (history.lastOrNull()?.index ?: 0) + 1
            history.addLast(
                Record(
                    index = recordIndex, seed = seed, cfg = useCfg, steps = useSteps, mode = mode,
                    chars = rawText.length, text = preview(rawText),
                    audioSec = seconds, genMs = genMs,
                )
            )
            while (history.size > HISTORY_KEEP) evicted += history.removeFirst().index
        }
        // 给这条历史留一份自己的音频并交给宿主（v7）——「结果」页那一条才有独立的播放/保存。
        // 必须在下面的 notifySchemaChanged() 之前：播放条是画给 hasAudio = true 的记录的。
        rememberAudioForHistory(outFile, recordIndex)
        evicted.forEach { dropHistoryAudio(it) }
        refreshDeviceStats()
        logLine(
            "完成：${"%.1f".format(seconds)} s 音频 / ${genMs} ms / " +
                "RTF ${"%.1f".format(lastRtf)} · 模式 $mode"
        )
        // 让"上次合成"状态行和日志立刻出现，不用等用户退出再进来
        notifySchemaChanged()
        // 合成是他点的，结果就该出现在眼前 —— 不用再手动点「结果」
        jumpToResultPage()

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
     * - 「合成」改成「开始推理」：这个按钮**只跑推理、不播放**，音频出来要另外
     *   点「播放」。叫「合成语音」时用户会等一句声音，等不到就以为坏了；
     *   「开始推理」既点明了是"开始"一个耗时动作，也不承诺出声。
     *   文字统一放在 [ACTION_REASONING]，下面的指路文案都引用它。
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
        ActionLabel.REASONING to ACTION_REASONING,
    )

    /**
     * 宿主渲染的插件页：**三页**，最外层所以宿主会画成可左右滑的分页（v5）。
     *
     * ## 为什么是 模型 / 参数 / 结果
     *
     * 按**使用顺序**排，不按重要性 —— 这和"把配置项全摊在一屏"是两种取向：
     *
     * | 页 | 多久动一次 | 里面放什么 |
     * |---|---|---|
     * | 模型 | 一次配好，之后基本不动 | 导入 / 下载 / 加载 / 删除 |
     * | 参数 | 每次合成前可能要调 | 音色描述、cfg、扩散步数、种子、线程 |
     * | 结果 | 每次合成完都要看 | 种子、参数、文本、播放条、历史 |
     *
     * 以前是一条长竖列（状态 → 试听 → 权重 → 参数 → 说明 → 日志），手机上要滚
     * 三四屏；更要紧的是"配置"和"结果"混在一起，合成完想看这次的种子，
     * 得先滚过二十个控件。
     *
     * ## 哪些控件是真的，哪些干脆不摆
     *
     * 这一页能改的都真的生效（都进了 native 的入参）。
     * **不能改的**：目标文本、合成模式、参考音频、逐字稿 —— 它们属于宿主的
     * `PreviewParams`，是"这一次调用怎么发"的一部分，由发起方（宿主）决定
     * （见 `PreviewParams` 的类注释，宿主写得很清楚：UI 归 UI，调用归调用）。
     *
     * 所以这里**不摆那些控件**，只用一行 INFO 说明去哪儿改。摆一个改完不生效的
     * 文本框，比不摆更糟 —— 用户会以为那句话被用上了，然后对着一段旧文本困惑。
     *
     * ## 跑在宿主的 Binder 线程上
     *
     * 必须快：只读 volatile 缓存 + 几次 `File.length()`。没有网络、没有解码、
     * 不调系统服务（内存信息在 [refreshDeviceStats] 里提前取好）。
     */
    override fun buildSettingsSchema(): UiSchema {
        val v = ModelStore.version(versionKey)
        val readySet = ModelStore.ready(this, versionKey)
        val installed = ModelStore.installedBytes(this)
        val usable = ModelStore.usableSpace(this)
        val versionReady = ModelStore.isVersionReady(this, v)

        val items = mutableListOf<UiItem>()

        // 最外层标签页 → 宿主渲染成可左右滑的 ViewPager（2026-10-22 那版）。
        // 前提是它**不在 GROUP 里**：两层横滑会抢手势，所以内层会退化成点击切换。
        items += UiItem(key = GROUP_TABS, type = UiType.TABS_START, label = "")

        // ==================== 页 1 · 模型 ====================
        items += UiItem(key = "tab_model", type = UiType.TAB, label = "模型")

        items += UiItem(
            key = "st_model", type = UiType.STATUS, label = "可用模型",
            value = when {
                readySet != null -> "${readySet.origin} · ${ModelStore.human(readySet.bytes)}"
                installed > 0 -> "不完整 · 已有 ${ModelStore.human(installed)}"
                else -> "没有可用的模型文件"
            },
        )
        items += UiItem(
            key = "st_engine", type = UiType.STATUS, label = "引擎",
            value = when {
                engineUnloading -> "正在释放内存…"
                nativeReady -> "已就绪 · ${sampleRate / 1000} kHz · $loadedThreads 线程"
                readySet != null -> "未加载 · 点下面「加载模型」"
                else -> "未加载 · 先导入或下载"
            },
        )
        items += UiItem(
            key = "st_disk", type = UiType.STATUS, label = "存储",
            value = "剩 ${ModelStore.human(usable)} · 已占 ${ModelStore.human(installed)}",
        )

        // 加载 / 释放：插件自己调自己（引擎本来就归它）。
        //
        // 宿主顶部功能区也有一对同名按钮，两处是**同一个操作**。这不是重复摆着玩：
        // 用户进「模型」页是想"把它用起来"，手就停在这里；而功能区那对是
        // "我现在就想跑"的入口。两边都能按，状态也都会立刻同步。
        items += UiItem(
            key = "en_load", type = UiType.ACTION, label = "加载模型",
            // 只在**按不下去**时说原因。能按的时候不解释要花多久、占多少内存 ——
            // 那是第一次用才需要的知识，之后每天都看见就是噪音
            help = if (readySet == null) "先导入或下载模型文件" else "",
            enabled = !nativeReady && readySet != null && !engineUnloading,
            primary = !nativeReady && readySet != null,
        )
        items += UiItem(
            key = "en_unload", type = UiType.ACTION, label = "释放模型",
            enabled = nativeReady && !engineUnloading,
        )

        // ---- 从本地导入（SDK v6 的 MODEL_SLOT）----
        //
        // 宿主画成「槽位名 · 当前状态 · [导入] [清除]」，选择器由**宿主**弹
        // （插件是 bound Service，没有 startActivityForResult，Android 10+
        // 也禁止它后台启动 Activity）。插件拿到只读 FD，复制进自己的私有目录。
        //
        // 为什么是**两个**槽位而不是一个"选择模型"：SAF 一次只能选一个文件，
        // 而 VoxCPM2 是两份 GGUF。硬凑成一个按钮就只能"选完再问你选另一份"，
        // 那比两个明确的槽位更难懂。
        items += UiItem(
            key = ModelStore.ROLE_BASE_LM, type = UiType.MODEL_SLOT,
            label = "主干模型 BaseLM",
            value = if (importingRole == ModelStore.ROLE_BASE_LM) "正在复制…"
            else importedState(ModelStore.ROLE_BASE_LM),
            // help 只留**文件名**：这是选文件时唯一要对照的东西，不是一句说明
            help = "VoxCPM2-BaseLM-*.gguf",
            enabled = !nativeReady && !downloading && importingRole.isEmpty(),
        )
        items += UiItem(
            key = ModelStore.ROLE_ACOUSTIC, type = UiType.MODEL_SLOT,
            label = "声学模型 Acoustic",
            value = if (importingRole == ModelStore.ROLE_ACOUSTIC) "正在复制…"
            else importedState(ModelStore.ROLE_ACOUSTIC),
            help = "VoxCPM2-Acoustic-*.gguf",
            enabled = !nativeReady && !downloading && importingRole.isEmpty(),
        )

        // ---- 下载 ----
        items += UiItem(key = "sec_dl", type = UiType.SECTION, label = "下载")

        items += UiItem(
            key = "version", type = UiType.CHOICE, label = "模型版本",
            value = v.key,
            options = ModelStore.VERSIONS.map {
                UiOption(it.key, "${it.label} · ${ModelStore.human(it.totalBytes)}")
            },
            enabled = !downloading,
        )

        // 逐文件状态：能分清"压根没下"和"下到一半断了"
        v.files().forEach { spec ->
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

        // 每个**真的存在**的镜像一个按钮。
        // 标准版只有魔搭有仓库，所以那一页只会出现一个按钮 —— 与其画一个
        // 点下去 404 的「从 HuggingFace 下载」，不如不画。
        ModelStore.sourcesFor(v.key).forEach { src ->
            items += UiItem(
                key = "dl_${src.mirror.key}", type = UiType.ACTION,
                label = "从 ${src.mirror.label} 下载",
                enabled = !downloading && !versionReady,
                primary = !downloading && !versionReady && src.mirror.key == mirrorKey,
            )
        }

        if (downloading || (installed > 0 && !versionReady)) {
            items += UiItem(
                key = KEY_DL_PROGRESS, type = UiType.PROGRESS, label = "下载进度",
                // dlDetail 是实时速度 / 剩余时间，属于状态，不是说明
                help = if (downloading) dlDetail else "",
                value = "%.1f".format(if (downloading) dlPercent else 0.0),
            )
        }
        if (downloading) {
            items += UiItem(key = "dl_cancel", type = UiType.ACTION, label = "取消下载")
        }
        if (installed > 0 && !downloading) {
            items += UiItem(
                key = "dl_delete", type = UiType.ACTION, label = "删除已下载的模型",
                help = "释放 ${ModelStore.human(installed)}",
            )
        }
        if (dlNotice.isNotEmpty()) {
            items += UiItem(key = "st_notice", type = UiType.STATUS, label = "提示", value = dlNotice)
        }

        // ==================== 页 2 · 参数 ====================
        items += UiItem(key = "tab_param", type = UiType.TAB, label = "参数")

        // ★ 宿主的功能块就画在这里（SDK v7 的 HOST_SLOT）。
        //
        // 里面是：加载 / 开始推理 / 播放 / 停止 / 保存 那一排按钮 +
        // 目标文本 / 模式 / 参考音频 / 逐字稿 / 种子 / 线程 / CPU-GPU + 进度提示 + 诊断。
        //
        // 为什么让宿主画、插件不自己摆：那排按钮背后是宿主的状态机
        // （引擎是否就绪、是否正在合成、参考音频的 FD、存到音乐库），
        // 插件重画就得把这些也搬过来，两边各存一份必然打架。这里只换挂载点。
        //
        // 只在「参数」页放一个：宿主只认第一个，多放几个也只生效第一个。
        items += UiItem(key = "host", type = UiType.HOST_SLOT)

        items += UiItem(key = "sec_param", type = UiType.SECTION, label = "插件默认值")
        items += UiItem(
            key = "voice", type = UiType.TEXTAREA, label = "语音设计描述（默认）",
            value = voiceDesign,
        )
        items += UiItem(
            key = "cfg", type = UiType.TEXT, label = "CFG 引导强度",
            // cfgHint / stepsHint 只在**填错**时才有内容（校验回显），不是常驻说明
            help = cfgHint,
            value = trimNum(cfg.toDouble()),
        )
        items += UiItem(
            key = "steps", type = UiType.TEXT, label = "扩散步数",
            help = stepsHint,
            value = timesteps.toString(),
        )

        // ---- 本次推理 ----
        items += UiItem(key = "sec_infer", type = UiType.SECTION, label = "本次推理")
        items += UiItem(
            key = KEY_INFER_PROGRESS, type = UiType.PROGRESS, label = "推理进度",
            value = "%.1f".format(if (inferring) inferPercent else if (lastAudioSec > 0) 100.0 else 0.0),
        )
        if (lastAudioSec > 0.0) {
            items += UiItem(
                key = "st_last_param", type = UiType.STATUS, label = "上次用的参数",
                value = "种子 $lastSeed · cfg ${trimNum(lastCfg.toDouble())} · 步数 $lastSteps · " +
                    modeName(lastMode),
            )
            items += UiItem(
                key = "st_last_perf", type = UiType.STATUS, label = "上次耗时",
                value = "${"%.1f".format(lastAudioSec)} s 音频 / ${lastGenMs / 1000} s · " +
                    "RTF ${"%.1f".format(lastRtf)} · $loadedThreads 线程",
            )
        }

        // ---- 插件内部状态（默认收起）----
        //
        // 和宿主那个「诊断信息」不是一回事：宿主显示的是 `debugInfo` + 最后一条错误，
        // 这里放的是插件进程自己的设备 / 引擎 / 状态快照，排查"为什么慢、为什么没加载"用。
        // `value = "collapsed"` 是 SDK v7 起支持的默认收起（用户手动点开过后以用户为准）。
        items += UiItem(
            key = "g_diag", type = UiType.GROUP_START,
            label = "插件内部状态", value = "collapsed",
        )
        items += UiItem(
            key = "diag_dev", type = UiType.STATUS, label = "设备",
            value = "$devCores 核 · ${devAbi.ifBlank { "未知 ABI" }} · " +
                "内存 ${gb(devMemAvail)} / ${gb(devMemTotal)}",
        )
        items += UiItem(
            key = "diag_engine", type = UiType.STATUS, label = "引擎",
            value = if (nativeReady)
                "${loadedSourceKey.ifBlank { "—" }} · $loadedThreads 线程 · ${sampleRate} Hz · " +
                    "占 ${ModelStore.human(loadedBytes)}"
            else "未加载",
        )
        items += UiItem(
            key = "diag_state", type = UiType.STATUS, label = "内部状态",
            value = "下载 ${if (downloading) "进行中" else "空闲"} · " +
                "推理 ${if (inferring) "进行中" else "空闲"} · " +
                "日志 ${logCount()} 行",
        )
        items += UiItem(key = "g_diag_end", type = UiType.GROUP_END)

        // ---- 日志（也默认收起：平时不用看，出事才展开）----
        val logs = logSnapshotForSchema()
        items += UiItem(
            key = "g_log", type = UiType.GROUP_START,
            label = "运行日志", value = "collapsed",
        )
        items += UiItem(
            key = KEY_LOG, type = UiType.LOG,
            value = logs,
        )
        items += UiItem(
            key = "log_clear", type = UiType.ACTION, label = "清空日志",
            enabled = logs.isNotEmpty(),
        )
        items += UiItem(key = "g_log_end", type = UiType.GROUP_END)

        // ==================== 页 3 · 结果 ====================
        items += UiItem(key = TAB_RESULT, type = UiType.TAB, label = "结果")

        if (lastAudioSec <= 0.0) {
            items += UiItem(
                key = "res_empty", type = UiType.INFO,
                label = "还没有结果 · 到「参数」页点「$ACTION_REASONING」",
            )
        } else {
            items += UiItem(
                key = "res_seed", type = UiType.STATUS, label = "种子",
                value = "$lastSeed",
            )
            items += UiItem(
                key = "res_param", type = UiType.STATUS, label = "参数",
                value = "cfg ${trimNum(lastCfg.toDouble())} · 扩散 $lastSteps 步 · " +
                    modeName(lastMode) + " · ${"%.1f".format(lastAudioSec)} s / ${lastGenMs / 1000} s" +
                    " · RTF ${"%.1f".format(lastRtf)}",
            )
            items += UiItem(
                key = "res_text", type = UiType.STATUS, label = "目标文本",
                value = preview(lastText),
            )
            // 播放条：播的是**宿主**那个播放器（它持有最近一次合成的产物）。
            // 插件在另一个进程，自己播的话退出这一页声音还在响，宿主管不到。
            items += UiItem(
                key = "audio", type = UiType.AUDIO, label = "这次的结果",
            )
        }

        // ---- 历史（每条都有自己的音频，SDK v7）----
        //
        // v7 起宿主支持 `onAudioFile(key, fd, name)`：给某个 AUDIO 条目送它自己的文件，
        // 那一行播的就是它、播放条上的「保存」存的也是它。
        // 合成成功时插件已经把 WAV 另存到 `filesDir/hist/` 并通知过宿主
        // （见 [rememberAudioForHistory]），这里用同一个 key 画播放条就能对上。
        //
        // 为什么必须自己留一份：宿主持有的只是"最近一次产物"，再合成一次就覆盖了。
        val hist = historySnapshot()
        if (hist.isNotEmpty()) {
            items += UiItem(
                key = "sec_hist", type = UiType.SECTION,
                label = "历史",
            )
            hist.forEach { r ->
                items += UiItem(
                    key = "h${r.index}_meta", type = UiType.STATUS,
                    label = "#${r.index} · 种子 ${r.seed}",
                    value = "cfg ${trimNum(r.cfg.toDouble())} · 扩散 ${r.steps} · " +
                        modeName(r.mode) + " · ${"%.1f".format(r.audioSec)} s / ${r.genMs / 1000} s",
                )
                items += UiItem(
                    key = "h${r.index}_text", type = UiType.STATUS,
                    label = "文本（${r.chars} 字）", value = r.text,
                )
                // 只有真的送过文件的才画：没送成功（或这一版之前生成的老记录）
                // 画出来也只会放最新那条，那是骗人
                if (r.hasAudio) {
                    items += UiItem(
                        key = historyAudioKey(r.index), type = UiType.AUDIO,
                        label = "#${r.index} 试听",
                    )
                }
            }
        }

        // 页签组收尾。缺这一行的话，宿主会继续把后面的项往最后一页里塞
        items += UiItem(key = "tabs_main_end", type = UiType.TABS_END)

        return UiSchema(intro = buildIntro(readySet != null, installed), items = items)
    }

    /**
     * 合成成功后翻到「结果」页（SDK v8 的 `notifySelectTab`）。
     *
     * **不能同步调**：基类是在 [synthesizeBlocking] **return 之后**才
     * `callback.onResult(...)`，而宿主是在 onResult 里把音频落盘、让结果页的
     * 播放条变成可用的。同步调 `selectTab` 就变成"先翻页、后拿到结果"，
     * 用户会先看到一条空播放条。挂一小段延迟，让 onResult 先落地。
     *
     * 宿主 API < 8 时没有这个回调，`callback.selectTab` 会抛，被
     * [notifySelectTab] 自己 catch 掉 —— 老宿主上是静默无效，不用判版本。
     *
     * 不判断"用户是不是正在别的页"：合成是他点的，翻过去就是他要看的东西。
     */
    private fun jumpToResultPage() {
        mainHandler.postDelayed({
            runCatching { notifySelectTab(GROUP_TABS, TAB_RESULT) }
        }, TAB_SELECT_DELAY_MS)
    }

    /**
     * 页面顶部那一行。
     *
     * **只留「现在该干什么」，不写产品介绍**：插件页本身已经很挤，
     * 每次进来都先读两行广告会把三个页签顶下去一个屏幕。
     * 模型就绪之后连这一行也不该占地方 —— 那时用户只需要知道按钮在哪。
     */
    private fun buildIntro(ready: Boolean, installed: Long): String = when {
        importingRole.isNotEmpty() -> "正在复制模型文件…"
        downloading -> "正在下载 ${"%.0f".format(dlPercent)}%"
        ready && nativeReady -> "填「文本」，点「$ACTION_REASONING」"
        ready -> "点「加载模型」，或直接点「$ACTION_REASONING」"
        installed > 0 -> "模型不完整 · 点「模型」页继续下载"
        else -> "先到「模型」页下载或导入模型"
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
            "version" -> {
                versionKey = ModelStore.version(value).key
                prefs.edit().putString(KEY_VERSION, versionKey).apply()
                dlNotice = ""
                // 换版本 = 换一套文件，进度条和"断点"的读数全变了，整页刷一次
                logLine("切换模型版本：${ModelStore.version(versionKey).label}")
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
            // 种子 / 线程不再由插件页接收（v0.6.0）：宿主参数区是唯一入口，
            // 插件那边只读 `SynthesisRequest.seed` 和 `EngineConfig.threads`
        }
    }

    override fun onActionInvoked(key: String) {
        when (key) {
            // ---- 模型页：加载 / 释放 ----
            //
            // 这两行是"插件自己调自己"。宿主功能区的「加载模型 / 释放模型」
            // 走的是 `loadEngine` / `closeEngine` 那条 AIDL，最终也落到这两个
            // 方法上，所以两条入口的状态是同一份（都读 nativeReady）。
            "en_load" -> loadFromUi()
            "en_unload" -> {
                if (!nativeReady) return
                logLine("用户点「释放模型」")
                closeEngineBlocking()
            }

            // ---- 下载：一个镜像一个按钮 ----
            "dl_cancel" -> {
                dlCancel.set(true)
                dlNotice = "正在取消…"
                logLine("用户点取消下载")
                notifySchemaChanged()
            }
            "dl_delete" -> {
                if (nativeReady) {
                    dlNotice = "引擎正在使用模型，请先在插件卡片上停用，再回来删除"
                    logLine("删除被拒：引擎正在使用模型")
                } else {
                    val freed = ModelStore.deleteAll(this)
                    dlNotice = "已删除已下载的模型，释放 ${ModelStore.human(freed)}"
                    dlPercent = 0.0
                    logLine("已删除已下载的模型，释放 ${ModelStore.human(freed)}")
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
            else -> {
                // dl_hfmirror / dl_hf / dl_modelscope —— key 的后半段就是镜像 key。
                // 用前缀匹配而不是把三个键硬编码一遍：以后加镜像只需要在 ModelStore
                // 里加一行，这里不用动。
                if (key.startsWith("dl_")) startDownload(key.removePrefix("dl_"))
            }
        }
    }

    /**
     * 「加载模型」按钮。
     *
     * 为什么另开线程而不是用基类的 worker：基类那个 worker 是单线程的，
     * 加载要 10~30 秒，期间同一队列里的「释放模型」「取消下载」全都排在后面 ——
     * 用户点了没反应，只会更用力地点。
     *
     * 走 [loadEngineBlocking] 而不是自己调 nativeInit：内存预检、幂等、
     * 内存统计刷新都在那边，复制一份迟早会分叉。
     */
    private fun loadFromUi() {
        if (nativeReady) return
        logLine("用户点「加载模型」")
        notifySchemaChanged()
        Thread({
            try {
                // config 传空 threads：用自动估算（宿主参数区的线程数才是唯一入口）
                loadEngineBlocking(null, EngineConfig())
            } catch (t: Throwable) {
                Log.w(TAG, "加载失败", t)
                dlNotice = "加载失败：${t.message ?: t.javaClass.simpleName}"
                logLine("加载失败：${t.message ?: t.javaClass.simpleName}")
            } finally {
                notifySchemaChanged()
            }
        }, "voxcpm2-load").apply { isDaemon = true; start() }
    }

    // ======================================================== 本地导入（v6）

    /**
     * 宿主替用户选好了文件，把只读 FD 交过来。**在基类的 worker 线程上**，
     * 所以可以直接同步读几百 MB。
     *
     * ## 为什么要先写 `.part` 再改名
     *
     * 复制到一半进程被杀（几十秒的窗口，用户很可能去清后台）会留下一个
     * 半截文件 —— 那比没有更糟：下次 `ready` 会把它当成"已导入"，
     * nativeInit 报一个看不懂的 GGUF 错误。`.part` + 原子改名不会留这种状态。
     *
     * ## 磁盘上只有一份
     *
     * 宿主只转发 FD、不留副本，所以最终代价就是这里复制的那一次。
     */
    override fun onModelFileReceived(
        role: String,
        fd: ParcelFileDescriptor,
        displayName: String,
        sizeBytes: Long,
    ) {
        if (role != ModelStore.ROLE_BASE_LM && role != ModelStore.ROLE_ACOUSTIC) {
            logLine("收到未知槽位的文件：$role（已忽略）")
            return
        }
        if (nativeReady) {
            // 引擎正拿着旧文件在跑。换文件得先释放 —— 界面上按钮已经灰了，
            // 走到这儿说明是宿主的竞态，直接拒绝比让 native 崩在别处好
            logLine("导入被拒：引擎正在运行")
            return
        }

        importingRole = role
        val label = if (role == ModelStore.ROLE_ACOUSTIC) "声学模型" else "主干模型"
        logLine("开始导入${label}：${displayName.ifBlank { "未命名" }}（${ModelStore.human(sizeBytes)}）")
        notifySchemaChanged()

        val dst = ModelStore.importedFile(this, role)
        val tmp = File(dst.absolutePath + ".part")
        val ok = try {
            tmp.delete()
            // AutoCloseInputStream 会关掉 fd；基类还会兜底 close 一次（重复 close 安全）
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
                tmp.outputStream().use { sink ->
                    val buf = ByteArray(512 * 1024)
                    var total = 0L
                    var lastPush = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        total += n
                        // 复制进度：靠 help 里的"正在复制…"太干，日志每 200 MB 记一行
                        if (total - lastPush > 200L * 1024 * 1024) {
                            lastPush = total
                            logLine("导入${label}：${ModelStore.human(total)}")
                        }
                    }
                    total
                }
            }
            if (dst.exists()) dst.delete()
            tmp.renameTo(dst)
        } catch (t: Throwable) {
            Log.w(TAG, "导入失败", t)
            tmp.delete()
            false
        }

        importingRole = ""
        if (ok) {
            dlNotice = ""
            logLine("导入完成：${label} · ${ModelStore.human(dst.length())} → ${dst.name}")
            refreshDeviceStats()
        } else {
            dlNotice = "导入${label}失败，换一个文件试试"
            logLine("导入失败：$role")
        }
        notifySchemaChanged()
    }

    /**
     * 「清除」：删掉自己私有目录里那份。
     *
     * 宿主删不了 —— 那是别的包的目录。引擎跑着的时候界面上按钮已经灰了，
     * 这里再兜一次：正在用的文件被删，native 之后读到就是崩溃。
     */
    override fun onModelDeleteRequested(role: String) {
        if (nativeReady) {
            dlNotice = "引擎正在运行，先「释放模型」再清除"
            logLine("清除被拒：引擎正在运行（$role）")
            notifySchemaChanged()
            return
        }
        val f = ModelStore.importedFile(this, role)
        val freed = if (f.isFile) f.length() else 0L
        val ok = !f.exists() || f.delete()
        logLine(if (ok) "已清除导入的文件：$role（${ModelStore.human(freed)}）"
        else "清除失败：$role")
        dlNotice = if (ok) "已清除导入的文件（${ModelStore.human(freed)}）" else "清除失败"
        notifySchemaChanged()
    }

    // ======================================================== 下载

    /**
     * 下载必须**另开线程**，不能占着基类的 worker：
     * worker 是单线程的，几十分钟的下载会把 `dl_cancel` 这个 action 一起堵死，
     * 用户就永远取消不了。
     */
    private fun startDownload(mirror: String) {
        if (downloading) return
        // 版本 × 镜像必须是真实存在的组合。界面只会画存在的那些，走到这儿
        // 的组合说明是"切了版本、按钮还是旧的"这类竞态 —— 说清楚比静默失败好
        val src = ModelStore.source(versionKey, mirror)
        if (src == null) {
            dlNotice = "这套组合没有下载源：${ModelStore.version(versionKey).label} × $mirror"
            logLine("下载中止：$dlNotice")
            notifySchemaChanged()
            return
        }
        // 记住用户上次选的是哪个镜像，"主按钮"高亮靠它
        mirrorKey = mirror
        prefs.edit().putString(KEY_MIRROR, mirror).apply()

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
        logLine("开始下载 ${src.label} · ${ModelStore.human(src.totalBytes)}")
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
                    ModelStore.isVersionReady(this, src.version) ->
                        "下载完成（${ModelStore.human(src.totalBytes)}）。" +
                            "现在可以点「加载模型」，或者直接点「$ACTION_REASONING」（会先加载再合成）。"
                    else -> "下载中断，再点一次同一个镜像可以接着下"
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
        private const val KEY_VERSION = "version"
        private const val KEY_MIRROR = "mirror"
        private const val KEY_CFG = "cfg"
        private const val KEY_STEPS = "steps"
        private const val KEY_VOICE = "voice"

        /**
         * 宿主那个「合成」按钮在**本插件里**显示成什么。
         *
         * 宿主默认叫「合成」（`ActionLabels.kt` 里那张表，插件改不了它），
         * 但 v3 起插件可以用 [buildActionLabels] 覆盖文字。
         *
         * 之所以叫「开始推理」而不是「合成语音」：它**只生成、不播放**
         * （播放在旁边的「播放」）。带"语音"两个字容易让人以为点完就有声。
         *
         * 只在这里写一次 —— 下面那些指路文案（"到参数页点…"）全部插值引用它，
         * 以后改名不会漏掉某一句，出现"照着文案找按钮却找不到"。
         */
        private const val ACTION_REASONING = "开始推理"

        /**
         * 最外层页签组的 key（`TABS_START`）。
         *
         * [notifySelectTab] 靠它定位"翻哪一组"，**必须非空、且和 schema 里一字不差** ——
         * 拼错了的表现是跳转静默无效，不报错。
         */
        private const val GROUP_TABS = "tabs_main"

        /** 「结果」页的 key。合成成功后自动翻到这里 */
        private const val TAB_RESULT = "tab_result"

        /** 推理历史留几条。只放内存，关掉进程就没了（见 [Record] 的说明） */
        private const val HISTORY_KEEP = 5

        /** 推理进度推送间隔：轮询线程 400 ms 一次，这里再宽松一点 */
        private const val INFER_PUSH_INTERVAL_MS = 600L

        /**
         * 合成完成后隔多久翻到「结果」页。
         *
         * 这 250 ms 不是给动画留的，是给 `onResult` 留的：基类在本方法（
         * `synthesizeBlocking`）返回之后才把结果交给宿主，宿主在那里面落盘、
         * 让播放条可用。翻得太早会看到空播放条。
         */
        private const val TAB_SELECT_DELAY_MS = 250L

        /** 参考音频上限：只要 5~10 秒，给足余地但不让用户把几百 MB 的音频塞进来 */
        private const val MAX_REFERENCE_BYTES = 64L * 1024 * 1024

        /** 内存里最多留多少行日志（再多也就是滚动噪声） */
        private const val LOG_KEEP = 120

        // ---- 要打补丁的 key：**必须和 schema 里那个 UiItem.key 一模一样** ----
        // 对不上宿主就静默忽略（它不去猜"你大概想改哪个"），所以只写在这个常量里，
        // 别在 schema 和补丁两处各写一遍字面量。
        private const val KEY_LOG = "log_body"
        private const val KEY_DL_PROGRESS = "dl_progress"
        private const val KEY_INFER_PROGRESS = "infer_progress"
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
