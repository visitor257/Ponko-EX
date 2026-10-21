// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
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

    /** 上次通知宿主重画的时刻；进度回调很密，不节流会把整页刷得很卡 */
    @Volatile private var lastNotifyAt = 0L

    private fun notifyThrottled() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifyAt < 700) return
        lastNotifyAt = now
        notifySchemaChanged()
    }

    // ---- 设置项（宿主不持久化，所以值由插件自己存）----
    @Volatile private var sourceKey = ModelStore.SOURCES[0].key
    @Volatile private var cfg = 2.0f
    @Volatile private var timesteps = 10
    @Volatile private var voiceDesign = ""

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        // 这里读一次就够：之后所有地方都读缓存字段（同步方法不能做 IO）
        prefs.let {
            sourceKey = it.getString(KEY_SOURCE, sourceKey) ?: sourceKey
            cfg = it.getFloat(KEY_CFG, cfg).coerceIn(0.5f, 4.0f)
            timesteps = it.getInt(KEY_STEPS, timesteps).coerceIn(4, 32)
            voiceDesign = it.getString(KEY_VOICE, "") ?: ""
        }
    }

    // ======================================================== 清单

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "voxcpm2",
        displayName = "VoxCPM2 语音合成",
        versionName = "0.1.0",
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

        val chosen = pickUsableSource()
            ?: throw PluginException.modelInvalid(
                "权重还没下载。请先在插件界面点「下载权重」（约 3.5 GB），" +
                    "或在设置里换成精简源（约 2.8 GB）。"
            )

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
            throw PluginException.modelInvalid(
                "加载失败：${VoxCpm2Bridge.nativeLastError().ifBlank { "未知原因" }}"
            )
        }

        sampleRate = VoxCpm2Bridge.nativeSampleRate().takeIf { it > 0 } ?: 48_000
        loadedBytes = chosen.totalBytes
        loadedSourceKey = chosen.key
        loadedThreads = threads
        nativeReady = true

        Log.i(TAG, "引擎就绪：${chosen.key} / ${ModelStore.human(loadedBytes)} / ${threads} 线程 / $sampleRate Hz")
        return EngineInfo(
            sampleRate = sampleRate,
            channelCount = 1,
            modelBytes = loadedBytes,
            loadElapsedMs = SystemClock.elapsedRealtime() - t0,
        )
    }

    override fun closeEngineBlocking() {
        // 幂等：宿主停用插件、页面销毁、进程回收都会调
        runCatching { VoxCpm2Bridge.nativeFree() }
            .onFailure { Log.w(TAG, "nativeFree 失败", it) }
        nativeReady = false
        loadedBytes = 0L
        loadedSourceKey = ""
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

            out.stage(Stage.LOADING, "解码参考音频")
            val wav = File(cache, "ref_${System.currentTimeMillis()}.wav")
            val ok = AudioUtil.toReferenceWav(raw.absolutePath, wav)
            raw.delete()
            if (!ok) {
                wav.delete()
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

        if (out.isCancelled) throw PluginException(ErrCode.CANCELLED, "已取消")

        if (!ok || !outFile.isFile || outFile.length() <= 44) {
            val nativeErr = VoxCpm2Bridge.nativeLastError()
            Log.e(TAG, "生成失败：$nativeErr")
            throw PluginException(
                ErrCode.PLUGIN_INTERNAL,
                "生成失败：${nativeErr.ifBlank { "引擎没有产出音频" }}",
                retryable = true,
            )
        }

        out.stage(Stage.AUDIO_DECODE, "封装 WAV")
        val seconds = AudioUtil.wavDurationSeconds(outFile)

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
     * 宿主渲染的设置页。
     *
     * ⚠ 这个方法跑在**宿主的 Binder 线程**上，必须快。这里只做几次 `File.length()`，
     * 没有网络、没有解码、不加锁。
     */
    override fun buildSettingsSchema(): UiSchema {
        val src = ModelStore.sourceAt(ModelStore.sourceIndex(sourceKey))
        val ready = ModelStore.isSourceReady(this, src)
        val installed = ModelStore.installedBytes(this)
        val freeGb = ModelStore.usableSpace(this) / 1e9

        val items = mutableListOf<UiItem>()

        // ---- 只读状态 ----
        items += UiItem(
            key = "st_engine", type = UiType.STATUS, label = "引擎",
            value = if (nativeReady) "已就绪 · ${sampleRate / 1000} kHz" else "未加载",
        )
        items += UiItem(
            key = "st_weights", type = UiType.STATUS, label = "权重",
            value = if (installed > 0) "已下载 ${ModelStore.human(installed)}" else "未下载",
        )
        items += UiItem(
            key = "st_disk", type = UiType.STATUS, label = "可用空间",
            value = String.format("%.1f GB", freeGb),
        )
        if (dlNotice.isNotEmpty()) {
            items += UiItem(key = "st_notice", type = UiType.STATUS, label = "提示", value = dlNotice)
        }

        // ---- 下载 ----
        items += UiItem(key = "g_dl", type = UiType.GROUP_START, label = "权重")

        items += UiItem(
            key = "source", type = UiType.CHOICE, label = "下载源",
            help = if (ready) "当前源已就绪" else src.note,
            value = src.key,
            options = ModelStore.SOURCES.map { UiOption(it.key, it.label) },
            enabled = !downloading,
        )

        items += UiItem(
            key = "dl_progress", type = UiType.PROGRESS, label = "下载进度",
            help = if (downloading) dlDetail
            else "${ModelStore.human(src.totalBytes)} · 支持断点续传，中断了再点一次接着下",
            value = "%.1f".format(if (downloading) dlPercent else if (ready) 100.0 else 0.0),
        )

        items += UiItem(
            key = "dl_start", type = UiType.ACTION,
            label = when {
                downloading -> "下载中…"
                ready -> "重新下载（覆盖现有文件）"
                installed > 0 -> "继续下载"
                else -> "下载权重"
            },
            help = "从魔搭下载，单个文件最大 1.7 GB",
            enabled = !downloading, primary = !ready,
        )

        if (downloading) {
            items += UiItem(key = "dl_cancel", type = UiType.ACTION, label = "取消下载")
        }

        if (installed > 0) {
            items += UiItem(
                key = "dl_delete", type = UiType.ACTION, label = "删除已下载权重",
                help = "会释放 ${ModelStore.human(installed)}；正在使用引擎时请先在卡片上停用插件",
                enabled = !downloading,
            )
        }

        items += UiItem(key = "g_dl_end", type = UiType.GROUP_END)

        // ---- 默认参数 ----
        items += UiItem(key = "g_param", type = UiType.GROUP_START, label = "默认参数")

        items += UiItem(
            key = "voice", type = UiType.TEXTAREA, label = "语音设计描述",
            help = "只在宿主没给描述时生效。写在这里的内容会被拼成 \"(描述)正文\"，" +
                "相当于凭空造一个音色；留空则用模型默认音色",
            value = voiceDesign,
        )

        items += UiItem(
            key = "cfg", type = UiType.SLIDER, label = "CFG 引导强度",
            help = "越大越贴文本、越小越自由。2.0 是官方建议值",
            value = "%.1f".format(cfg), min = 1.0, max = 4.0, step = 0.1,
        )

        items += UiItem(
            key = "steps", type = UiType.SLIDER, label = "扩散步数",
            help = "10 起步；15~20 更细腻但更慢（本来就很慢了，不建议调高）",
            value = timesteps.toString(), min = 4.0, max = 24.0, step = 1.0,
        )

        items += UiItem(key = "g_param_end", type = UiType.GROUP_END)

        // ---- 说明与许可 ----
        items += UiItem(key = "sep_help", type = UiType.SECTION, label = "说明")
        items += UiItem(
            key = "help", type = UiType.INFO,
            label = "首次使用要先下载权重（约 3.5 GB）。下载完成后引擎会在下次启用或试听时加载。\n\n" +
                "**手机纯 CPU 推理，非常慢**：5 秒语音大约要等 1~2 分钟，生成过程中会有进度，随时可以取消。" +
                "建议 8 GB 内存以上的机型。\n\n" +
                "参考音频要求 5~10 秒、干净的单一说话人；任意格式都可以，插件会自己解码并转成 16 kHz 单声道。",
        )
        items += UiItem(
            key = "license", type = UiType.INFO,
            label = "**许可**：本插件 MIT。推理引擎 llama.cpp-omni / ggml 为 MIT，" +
                "版权归 The ggml authors；模型 VoxCPM2 为 Apache-2.0，版权归 OpenBMB。" +
                "权重由你在此处自行下载，不随插件分发。\n\n" +
                "**声音使用**：用他人音频做音色克隆可能侵犯声音权益（《民法典》第 1023 条），" +
                "请只克隆自己或已获授权的音色；对外发布合成音频还需按深度合成相关规定做标识。",
        )

        return UiSchema(intro = INTRO, items = items)
    }

    override fun onSettingChanged(key: String, value: String) {
        when (key) {
            "source" -> {
                sourceKey = value
                prefs.edit().putString(KEY_SOURCE, value).apply()
                dlNotice = ""
                notifySchemaChanged()
            }
            "voice" -> {
                voiceDesign = value
                prefs.edit().putString(KEY_VOICE, value).apply()
            }
            "cfg" -> {
                cfg = value.toFloatOrNull()?.coerceIn(1f, 4f) ?: cfg
                prefs.edit().putFloat(KEY_CFG, cfg).apply()
            }
            "steps" -> {
                timesteps = value.toIntOrNull()?.coerceIn(4, 24) ?: timesteps
                prefs.edit().putInt(KEY_STEPS, timesteps).apply()
            }
        }
    }

    override fun onActionInvoked(key: String) {
        when (key) {
            "dl_start" -> startDownload()
            "dl_cancel" -> {
                dlCancel.set(true)
                dlNotice = "正在取消…"
                notifySchemaChanged()
            }
            "dl_delete" -> {
                if (nativeReady) {
                    dlNotice = "引擎正在使用权重，请先在插件卡片上停用，再回来删除"
                } else {
                    val freed = ModelStore.deleteAll(this)
                    dlNotice = "已删除权重，释放 ${ModelStore.human(freed)}"
                    dlPercent = 0.0
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
            notifySchemaChanged()
            return
        }

        downloading = true
        dlCancel.set(false)
        dlPercent = 0.0
        dlDetail = "准备中…"
        dlNotice = ""
        notifySchemaChanged()

        val t = Thread({
            var failed: String? = null
            var done = 0L
            try {
                for (spec in src.files()) {
                    if (dlCancel.get()) break
                    val base = done
                    val r = ModelStore.download(this, src, spec, dlCancel) { got, total ->
                        val overall = ((base + got).toDouble() / src.totalBytes * 100.0)
                        dlPercent = overall.coerceIn(0.0, 100.0)
                        dlDetail = "${spec.label}：${ModelStore.human(got)} / ${ModelStore.human(total)}" +
                            "（总进度 ${"%.0f".format(overall)}%）"
                        notifyThrottled()
                    }
                    if (r.isFailure) {
                        failed = r.exceptionOrNull()?.message ?: "下载失败"
                        break
                    }
                    done += spec.bytes
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
                        "返回上一页再进来，或直接点试听，引擎会自动加载。"
                    else -> "下载中断，再点一次「继续下载」可以接着下"
                }
                Log.i(TAG, "下载结束：$dlNotice")
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

        private const val INTRO =
            "**VoxCPM2**（OpenBMB，2B）本地语音合成：30 种语言、9 种中文方言，" +
                "支持音色设计与零样本克隆，48 kHz 输出。\n\n" +
                "全部在本机运行，不联网（除了下载权重那一次）。"
    }
}
