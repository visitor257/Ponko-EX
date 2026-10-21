// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.echo

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.litertchat.plugin.Capability
import com.litertchat.plugin.EngineConfig
import com.litertchat.plugin.EngineInfo
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 示例插件：Echo。
 *
 * **不加载模型、不含 .so、不联网**。`synthesize` 生成一段三音符琶音
 * （C5 → E5 → G5）回传，用来验证：
 *
 * - 宿主能否发现并绑定本插件
 * - API 版本校验是否生效
 * - `onStage` 进度能否到达宿主
 * - **音频 FD 跨进程传回**（这是真实插件必经的路径，提前验证掉）
 * - 取消能否在中途打断
 * - 解绑后连接是否干净
 *
 * 把它改成真正可用的插件，只需要替换 [synthesizeBlocking] 里的音频生成部分。
 */
class EchoPluginService : PonkoPluginService() {

    @Volatile private var loaded = false

    // ---- 界面设置项。存在内存里，直接影响试听生成的琶音 ----
    // 真实插件要把这些落盘（SharedPreferences / 自己的文件），
    // 因为宿主**不持久化任何设置值** —— 每次重画都来这里拿当前值。
    @Volatile private var baseHz = 523f          // C5
    @Volatile private var wave = "sine"
    @Volatile private var decay = true
    @Volatile private var note = ""

    // ---- 模拟下载，用于验证 PROGRESS 控件 + onSchemaChanged ----
    @Volatile private var downloading = false
    @Volatile private var downloadPct = 0.0

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "echo",
        displayName = "Echo 测试插件",
        versionName = "1.0.0",
        author = "visitor257",
        // **TEST 而不是 TTS**。
        //
        // 能力是"这个插件承诺能做什么"。Echo 确实会生成音频，但那是固定的琶音，
        // 不是真的语音 —— 标成 TTS 会让宿主的「朗读回答」路由选到它，
        // 用户会听到一段莫名其妙的音调。
        //
        // 标成 TEST 之后：宿主仍允许在插件界面里试听（那是诊断动作），
        // 但不会把它当成正式的朗读引擎。
        //
        // **AUTOLOAD_ENGINE**：允许宿主在连上时就加载引擎。
        //
        // 这是"加载时机"的声明，和"能做什么"是两回事，所以单独一个常量。
        // 该不该声明取决于加载有多贵：
        // · Echo 的 loadEngineBlocking 只是 sleep 800ms，零内存 → 声明，装上就能试听
        // · 真模型（几 GB、几十秒）→ **不要声明**，让用户自己点「加载引擎」，
        //   否则用户只是切到插件页看一眼连接状态，后台就开始灌几个 G
        capabilities = listOf(Capability.TEST, Capability.AUTOLOAD_ENGINE),
        modelFamilies = emptyList(),
        // 自管模型（这里根本没有模型），不需要宿主传文件
        needsHostModels = false,
        minSdkHint = null,
    )

    override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
        val t0 = SystemClock.elapsedRealtime()
        // 假装加载：真实插件在这里初始化运行时（加载 .so、读权重）。
        // 故意睡着，让宿主那边的「加载中」状态能被看到、被验证。
        Thread.sleep(800)
        loaded = true
        return EngineInfo(
            sampleRate = SAMPLE_RATE,
            channelCount = 1,
            modelBytes = 0L,
            loadElapsedMs = SystemClock.elapsedRealtime() - t0,
        )
    }

    override fun closeEngineBlocking() {
        loaded = false
    }

    /**
     * 插件界面：把当前 12 种控件全用一遍，作为**渲染自检 + 写法范例**。
     *
     * 真实插件（如 VoxCPM2）照这个结构删掉不需要的项就行 ——
     * 控件长什么样、深浅色怎么适配，一行都不用管。
     *
     * 这些设置项**真的生效**：改了之后会影响试听生成的琶音
     * （音高偏移、时长、波形形状），所以可以验证「值确实传到了插件」。
     */
    override fun buildSettingsSchema(): UiSchema {
        val ready = loaded
        return UiSchema(
            intro = "这是个**测试插件**：不提供真实功能，只用来验证插件链路是否通畅。\n\n" +
                "这个页面里的每一项都是**宿主画的**。插件只提供字段，" +
                "样式、深浅色、圆角全部由 Ponko 负责 —— 插件里没有一行布局代码。\n" +
                "上面那个「试听」按钮也是宿主画的（试听 = 宿主发起一次合成再播放）。",
            items = listOf(
                // ---- 只读状态 ----
                UiItem(key = "engine", type = UiType.STATUS,
                    label = "引擎", value = if (ready) "已就绪" else "未加载"),
                UiItem(key = "sample", type = UiType.STATUS,
                    label = "采样率", value = "$SAMPLE_RATE Hz"),

                // ---- 可折叠分组 ----
                UiItem(key = "g_tone", type = UiType.GROUP_START, label = "音色"),

                UiItem(key = "base", type = UiType.SLIDER, label = "基频",
                    help = "琶音的起始音高，越高越尖",
                    value = baseHz.toString(), min = 262.0, max = 1046.0, step = 1.0),

                UiItem(key = "shape", type = UiType.CHOICE, label = "波形",
                    value = wave,
                    options = listOf(
                        UiOption("sine", "正弦（柔和）"),
                        UiOption("tri", "三角（明亮）"),
                        UiOption("square", "方波（尖锐）"),
                    )),

                UiItem(key = "decay", type = UiType.SWITCH, label = "启用衰减包络",
                    help = "关掉的话每个音会一直响到下一音", value = decay.toString()),

                UiItem(key = "g_tone_end", type = UiType.GROUP_END),

                UiItem(key = "g_adv", type = UiType.GROUP_START, label = "高级"),

                // ---- 进度条：模拟一次"下载"，验证长任务时界面不会看起来卡死 ----
                UiItem(key = "prog", type = UiType.PROGRESS, label = "模拟下载",
                    help = if (downloading) "正在下载，共 2.83 GB" else "点下面按钮开始（2 秒跑完）",
                    value = "%.0f".format(downloadPct)),

                UiItem(key = "dl", type = UiType.ACTION,
                    label = if (downloading) "下载中…" else "开始模拟下载",
                    help = "验证进度条 + onSchemaChanged 重画",
                    enabled = !downloading, primary = true),

                // ---- 多行文本 ----
                UiItem(key = "note", type = UiType.TEXTAREA, label = "备注",
                    help = "随便写点东西，重启页面后不会保留（Echo 不落盘）",
                    value = note),

                UiItem(key = "g_adv_end", type = UiType.GROUP_END),

                UiItem(key = "sep", type = UiType.SECTION, label = "其它"),
                UiItem(key = "about", type = UiType.INFO,
                    label = "这个插件不含任何模型，试听时现场算一段琶音。" +
                        "它存在的意义是验证链路，以及当渲染器的测试样本。\n\n" +
                        "注意它声明的能力是「测试」而不是「语音合成」——" +
                        "它生成的是固定音调，不是真的语音，所以不会被当成朗读引擎。"),
            )
        )
    }

    /** 用户改了某项。Echo 把值存在内存里，直接影响试听结果 */
    override fun onSettingChanged(key: String, value: String) {
        Log.i(TAG, "onSettingChanged: $key = $value")
        when (key) {
            "base" -> value.toFloatOrNull()?.let { baseHz = it }
            "shape" -> wave = value
            "decay" -> decay = value.equals("true", ignoreCase = true)
            "note" -> note = value
        }
    }

    /** 用户点了某个 ACTION 项。这里模拟一次下载，验证进度条 + 重画 */
    override fun onActionInvoked(key: String) {
        Log.i(TAG, "onActionInvoked: $key")
        if (key != "dl" || downloading) return
        downloading = true
        downloadPct = 0.0
        notifySchemaChanged()
        try {
            while (downloadPct < 100.0) {
                Thread.sleep(100)
                downloadPct = (downloadPct + 5.0).coerceAtMost(100.0)
                // 每 10% 通知一次重画 —— 节流是必须的：
                // 每次都通知 = 每次一次跨进程调用 + 整页重绘，会把界面刷得很卡
                if (downloadPct.toInt() % 10 == 0) notifySchemaChanged()
            }
        } finally {
            downloading = false
            notifySchemaChanged()
        }
    }

    override fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle {
        if (!loaded) throw PluginException.notReady()
        if (req.text.isBlank()) throw PluginException.invalidRequest("文本为空")

        val t0 = SystemClock.elapsedRealtime()

        out.stage(Stage.TEXT_ENCODE, "分析文本（${req.text.length} 字）")
        // 逐字"处理"，给取消留出打断点
        req.text.forEachIndexed { i, _ ->
            if (i % 8 == 0) {
                out.throwIfCancelled()
                Thread.sleep(30)
            }
        }

        // 三个音，各 0.6 秒；文本越长响得越久（上限 4 拍），让"朗读"有反馈。
        // **基频来自界面设置**，所以调滑块之后试听会立刻变调 —— 这样能验证
        // 「值确实从宿主传到了插件」，而不是"界面画出来了但根本没接上"
        val root = baseHz
        val notes = floatArrayOf(root, root * 1.26f, root * 1.5f)  // 大三和弦
        val repeats = ((req.text.length / 6).coerceIn(1, 4))
        val totalSec = 0.6 * notes.size * repeats

        out.stage(Stage.DECODE, "生成波形 0 / ${"%.1f".format(totalSec)} 秒")
        val samples = ShortArray((totalSec * SAMPLE_RATE).toInt())
        var idx = 0
        for (r in 0 until repeats) {
            for (n in notes) {
                val noteSamples = (0.6 * SAMPLE_RATE).toInt()
                for (i in 0 until noteSamples) {
                    out.throwIfCancelled()
                    val t = i.toDouble() / SAMPLE_RATE
                    // 包络：decay 关掉时用平顶（只剩 5ms 淡入淡出防爆音）
                    val env = if (decay) {
                        exp(-3.0 * i / noteSamples) *
                            if (i < SAMPLE_RATE * 0.005) i / (SAMPLE_RATE * 0.005) else 1.0
                    } else {
                        val fadeIn = if (i < SAMPLE_RATE * 0.005) i / (SAMPLE_RATE * 0.005) else 1.0
                        val fadeOut = if (i > noteSamples - SAMPLE_RATE * 0.005)
                            (noteSamples - i) / (SAMPLE_RATE * 0.005) else 1.0
                        minOf(fadeIn, fadeOut)
                    }
                    val phase = 2 * PI * n * t
                    // 波形也来自设置：正弦 / 三角 / 方波
                    val raw = when (wave) {
                        "tri" -> 2.0 / PI * Math.asin(sin(phase))
                        "square" -> if (sin(phase) >= 0) 1.0 else -1.0
                        else -> sin(phase)
                    }
                    val v = raw * 0.28 * env
                    if (idx < samples.size) samples[idx++] = (v * Short.MAX_VALUE).toInt().toShort()
                }
                val done = idx.toFloat() / samples.size
                out.stage(Stage.DECODE, "生成波形 ${(done * 100).toInt()}%")
            }
        }

        out.stage(Stage.AUDIO_DECODE, "封装 WAV")
        val wav = pcm16ToWav(samples, SAMPLE_RATE, 1)

        // 写到自己 cacheDir → 交 FD 给宿主。
        // ⚠ 插件读不到宿主目录，宿主也读不到插件目录，所以大块音频必须走 FD。
        val dir = File(cacheDir, "echo").apply { mkdirs() }
        val file = File(dir, "echo_${System.currentTimeMillis()}.wav")
        file.writeBytes(wav)

        out.stage(Stage.DONE, "完成")

        return ResultBundle(
            requestId = req.requestId,
            sampleRate = SAMPLE_RATE,
            channelCount = 1,
            durationMs = (samples.size * 1000L / SAMPLE_RATE),
            elapsedMs = SystemClock.elapsedRealtime() - t0,
            audioFormat = PluginConst.FMT_WAV,
            audio = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            inlineAudio = null,
            debugInfo = "echo plugin, repeats=$repeats",
        )
    }

    companion object {
        private const val TAG = "EchoPlugin"
        private const val SAMPLE_RATE = 48_000

        /** 16-bit PCM → WAV（44 字节头）。插件里自备，不引第三方库 */
        fun pcm16ToWav(pcm: ShortArray, sampleRate: Int, channels: Int): ByteArray {
            val dataSize = pcm.size * 2
            val out = java.nio.ByteBuffer.allocate(44 + dataSize)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray())
            out.putInt(36 + dataSize)
            out.put("WAVE".toByteArray())
            out.put("fmt ".toByteArray())
            out.putInt(16)                         // PCM 头长度
            out.putShort(1)                        // format = PCM
            out.putShort(channels.toShort())
            out.putInt(sampleRate)
            out.putInt(sampleRate * channels * 2)  // byte rate
            out.putShort((channels * 2).toShort()) // block align
            out.putShort(16)                       // bits per sample
            out.put("data".toByteArray())
            out.putInt(dataSize)
            pcm.forEach { out.putShort(it) }
            return out.array()
        }
    }
}
