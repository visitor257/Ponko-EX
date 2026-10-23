// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权重清单 + 断点续传下载 + 本地导入。
 *
 * 权重**不进 APK**（2.8~3.6 GB，塞进去没法发版），首次使用时下到插件自己的
 * 外部私有目录：`Android/data/com.litertchat.plugin.voxcpm2/files/models/`；
 * 用户也可以从本地直接导入（落到 `files/imported/`，见 [importDir]）。
 *
 * ## 两个维度：版本 × 镜像
 *
 * 以前这里是「两套源」平铺成一个下拉，用户看不出"精度"和"从哪下"是两件事。
 * 现在分开：
 *
 * · **版本**只有两个 —— [STANDARD]（BaseLM Q8_0）和 [COMPACT]（BaseLM Q4_K_M）。
 *   Acoustic 两个版本都是 F16，所以差别只在 BaseLM 那 0.7 GB 和音质。
 * · **镜像**三个 —— HuggingFace 直连、hf-mirror、魔搭。
 *
 * ⚠ **不是所有组合都存在**：标准版的 Q8_0 只有魔搭上有仓库
 * （`DennisHuang/VoxCPM2-GGUF`），HF 上搜不到对应仓库（hf-mirror 拉这个路径
 * 返回 404，而 hf-mirror 只代理 HF 上真有的东西 —— 所以这不是"我没试"，
 * 是那条路不存在）。[sourcesFor] 会按版本过滤，界面上只出现真的能下完的组合：
 * 摆一个点下去 404 的按钮比不摆更糟。
 *
 * ## 许可
 *
 * VoxCPM2 官方权重是 Apache-2.0。标准版那个仓库**明确标了 apache-2.0**；
 * 精简版是第三方从同一份权重转的 GGUF，仓库没填许可字段 —— 所以默认标准版，
 * 想省 0.7 GB 再自己切，界面上把这件事写清楚，不藏着。
 */
object ModelStore {

    /** 一个权重文件 */
    data class Spec(
        val fileName: String,
        /** `R.string.*` 里的标签 —— 这里存 id 不存字符串，取的时候才按语言解析 */
        val labelRes: Int,
        val bytes: Long,
    )

    /** 一个量化版本：两个文件凑成一套 */
    data class Version(
        val key: String,
        val labelRes: Int,
        val baseLm: Spec,
        val acoustic: Spec,
        val note: String,
    ) {
        val totalBytes: Long get() = baseLm.bytes + acoustic.bytes
        fun files(): List<Spec> = listOf(baseLm, acoustic)
    }

    /** 一个下载镜像 */
    data class Mirror(
        val key: String,
        val labelRes: Int,
        val note: String,
    )

    /** 版本 × 镜像 = 一个真的能下的目标 */
    data class Source(
        val key: String,
        val version: Version,
        val mirror: Mirror,
        val baseUrl: String,
    ) {
        val totalBytes: Long get() = version.totalBytes
        val note: String get() = version.note
        fun files(): List<Spec> = version.files()
    }

    /** 两个文件各自的角色名（也是 `UiType.MODEL_SLOT` 的 key） */
    const val ROLE_BASE_LM = "base_lm"
    const val ROLE_ACOUSTIC = "acoustic"

    private val ACOUSTIC_F16 = Spec("VoxCPM2-Acoustic-F16.gguf", R.string.spec_acoustic, 1_825_096_352L)

    /**
     * 标准版：BaseLM Q8_0。
     *
     * 和官方 Android Demo 相比 BaseLM 精度更高（Q8 对 Q4_K_M），音质略好，
     * 多占 0.7 GB。这个仓库在魔搭上**明确标了 apache-2.0**，归属清楚。
     */
    val STANDARD = Version(
        key = "standard",
        labelRes = R.string.model_standard,
        baseLm = Spec("VoxCPM2-BaseLM-Q8_0.gguf", R.string.spec_base_q8, 1_727_309_920L),
        acoustic = ACOUSTIC_F16,
        note = "Q8_0 · 3.55 GB · 音质略好，多占 0.7 GB；仓库声明 apache-2.0",
    )

    /**
     * 精简版：BaseLM Q4_K_M —— **独立测试版 voxcpm2_android 用的就是这个**。
     *
     * 就是官方 Android Demo 的那套文件，2.83 GB。仓库没填 license 字段，
     * 所以不设成默认（上游权重本身仍是 Apache-2.0）。
     */
    val COMPACT = Version(
        key = "compact",
        labelRes = R.string.model_compact,
        baseLm = Spec("VoxCPM2-BaseLM-Q4_K_M.gguf", R.string.spec_base_q4, 1_002_374_880L),
        acoustic = ACOUSTIC_F16,
        note = "Q4_K_M · 2.83 GB · 手机内存吃紧时选它；第三方转换件，仓库未声明许可",
    )

    private val MIRROR_HF = Mirror(
        key = "hf",
        labelRes = R.string.mirror_hf,
        note = "huggingface.co 直连，国内通常打不开",
    )
    private val MIRROR_HF_MIRROR = Mirror(
        key = "hfmirror",
        labelRes = R.string.mirror_hfmirror,
        note = "hf-mirror.com，HF 的国内镜像",
    )
    private val MIRROR_MODELSCOPE = Mirror(
        key = "modelscope",
        labelRes = R.string.mirror_modelscope,
        note = "modelscope.cn，国内直连",
    )

    val VERSIONS: List<Version> = listOf(STANDARD, COMPACT)
    val MIRRORS: List<Mirror> = listOf(MIRROR_HF_MIRROR, MIRROR_HF, MIRROR_MODELSCOPE)

    /**
     * 真的存在的组合。
     *
     * huggingface.co 上只有 `tc-mb/MiniCPM-V-Apps-gguf`（精简版那两个文件），
     * 标准版的 Q8_0 找不到对应仓库 —— 所以标准版只挂魔搭。
     */
    val SOURCES: List<Source> = listOf(
        Source("compact@hfmirror", COMPACT, MIRROR_HF_MIRROR,
            "https://hf-mirror.com/tc-mb/MiniCPM-V-Apps-gguf/resolve/main/"),
        Source("compact@hf", COMPACT, MIRROR_HF,
            "https://huggingface.co/tc-mb/MiniCPM-V-Apps-gguf/resolve/main/"),
        Source("compact@modelscope", COMPACT, MIRROR_MODELSCOPE,
            "https://modelscope.cn/models/zerg13/MiniCPM-V-Apps-gguf/resolve/master/"),
        Source("standard@modelscope", STANDARD, MIRROR_MODELSCOPE,
            "https://modelscope.cn/models/DennisHuang/VoxCPM2-GGUF/resolve/master/"),
    )

    /** 某个版本能用的镜像（顺序跟着 [SOURCES]） */
    fun sourcesFor(versionKey: String?): List<Source> =
        SOURCES.filter { it.version.key == versionKey }

    fun source(versionKey: String?, mirrorKey: String?): Source? =
        SOURCES.firstOrNull { it.version.key == versionKey && it.mirror.key == mirrorKey }

    fun sourceByKey(key: String?): Source? = SOURCES.firstOrNull { it.key == key }

    /** 版本 key → 版本；认不出来就退回标准版 */
    fun version(key: String?): Version =
        VERSIONS.firstOrNull { it.key == key } ?: STANDARD

    // ==================== 目录 ====================

    fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    /**
     * 用户从本地导入的文件落这儿。
     *
     * 放 `filesDir`（内部存储）而不是外部私有目录：用户挑的可能就是外部存储上
     * 的那份，内部目录不会被系统清理，也不会被别的 App 看到。
     */
    fun importDir(ctx: Context): File = File(ctx.filesDir, "imported").apply { mkdirs() }

    fun importedFile(ctx: Context, role: String): File =
        File(importDir(ctx), if (role == ROLE_ACOUSTIC) "acoustic.gguf" else "base_lm.gguf")

    fun fileFor(ctx: Context, spec: Spec): File = File(modelDir(ctx), spec.fileName)

    // ==================== 状态 ====================

    /**
     * 文件是否可用。
     *
     * 判据是「大小接近标称值 **且** 没有 .part 残留」—— 只看大小会把
     * 下到一半断掉的文件当成好的，加载时报一个莫名其妙的 GGUF 错误。
     */
    fun isReady(ctx: Context, spec: Spec): Boolean {
        val f = fileFor(ctx, spec)
        return f.isFile && f.length() > spec.bytes * 95 / 100 &&
            !File(f.absolutePath + ".part").exists()
    }

    /** 一套版本的两个文件都在（不含本地导入的那份） */
    fun isVersionReady(ctx: Context, v: Version): Boolean =
        v.files().all { isReady(ctx, it) }

    /**
     * 这一套现在能不能用 —— **本地导入优先**。
     *
     * 导入的是完整文件，比下载的优先级高：用户特意从本地导进来，说明他想用那个
     * （可能是自己转的量化，或者从电脑拷进来的）。
     *
     * [Ready.origin] 要显示在界面上：否则"我明明导入了，怎么还在用下载的"
     * 会变成一次排查。
     */
    data class Ready(
        val baseLm: File,
        val acoustic: File,
        /**
         * 这套权重从哪来：`"imported"` 或版本的 key。
         *
         * 存**标记**而不是现成的文案：这段文字会显示在界面上，得跟着语言变。
         * 翻译在插件服务里做（见 `originLabel()`），这里只管"是哪一套"。
         */
        val origin: String,
        val bytes: Long,
    )

    fun ready(ctx: Context, versionKey: String?): Ready? {
        val importedBase = importedFile(ctx, ROLE_BASE_LM)
        val importedAcoustic = importedFile(ctx, ROLE_ACOUSTIC)
        if (importedBase.isFile && importedAcoustic.isFile &&
            importedBase.length() > 0 && importedAcoustic.length() > 0
        ) {
            return Ready(
                importedBase, importedAcoustic, "imported",
                importedBase.length() + importedAcoustic.length(),
            )
        }
        // 下载的哪一套都行：用户可能先用精简版跑通，再切标准版但还没下完
        VERSIONS.forEach { v ->
            if (isVersionReady(ctx, v)) {
                return Ready(fileFor(ctx, v.baseLm), fileFor(ctx, v.acoustic),
                    v.key, v.totalBytes)
            }
        }
        return null
    }

    /** 已占用的空间：下载的 + 导入的 */
    fun installedBytes(ctx: Context): Long {
        var sum = VERSIONS.flatMap { it.files() }.distinctBy { it.fileName }
            .sumOf { fileFor(ctx, it).let { f -> if (f.isFile) f.length() else 0L } }
        listOf(ROLE_BASE_LM, ROLE_ACOUSTIC).forEach { role ->
            val f = importedFile(ctx, role)
            if (f.isFile) sum += f.length()
        }
        return sum
    }

    fun usableSpace(ctx: Context): Long = modelDir(ctx).usableSpace

    /** 删掉所有已下载权重（不动本地导入的 —— 那个由 MODEL_SLOT 的「清除」管） */
    fun deleteAll(ctx: Context): Long {
        var freed = 0L
        VERSIONS.flatMap { it.files() }.distinctBy { it.fileName }.forEach { spec ->
            listOf(fileFor(ctx, spec), File(fileFor(ctx, spec).absolutePath + ".part")).forEach {
                if (it.isFile) {
                    freed += it.length()
                    it.delete()
                }
            }
        }
        return freed
    }

    // ==================== 下载 ====================

    /**
     * 下载一个文件，支持从 `.part` 续传。阻塞到底，必须在后台线程调用。
     */
    fun download(
        ctx: Context,
        src: Source,
        spec: Spec,
        cancelled: AtomicBoolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Result<File> {
        val target = fileFor(ctx, spec)
        val part = File(target.absolutePath + ".part")
        val url = URL(src.baseUrl + spec.fileName)

        if (isReady(ctx, spec)) return Result.success(target)

        var existing = if (part.isFile) part.length() else 0L
        if (target.isFile && !part.exists()) {
            // 上一版留下的完整文件但判据没过 → 当作续传起点
            target.renameTo(part)
            existing = part.length()
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PonkoVoxCPM2/0.2")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = conn.responseCode
            val append: Boolean
            var total: Long
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    total = parseTotal(conn.getHeaderField("Content-Range"), spec.bytes)
                }
                HttpURLConnection.HTTP_OK -> {
                    // 服务端不理 Range：只能从头来
                    append = false
                    existing = 0L
                    total = conn.contentLengthLong.takeIf { it > 0 } ?: spec.bytes
                }
                else -> return Result.failure(IllegalStateException("HTTP $code (${url.host})"))
            }

            var downloaded = existing
            onProgress(downloaded, total)

            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(existing) else raf.setLength(0)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastTick = 0L
                    while (true) {
                        if (cancelled.get()) return Result.failure(InterruptedException("cancelled"))
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 400) {
                            lastTick = now
                            onProgress(downloaded, total)
                        }
                    }
                }
            }

            if (total > 0 && part.length() < total) {
                return Result.failure(
                    IllegalStateException("connection lost (${part.length()}/$total bytes)")
                )
            }

            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(target.length(), target.length())
            return Result.success(target)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseTotal(contentRange: String?, fallback: Long): Long {
        val slash = contentRange?.lastIndexOf('/') ?: -1
        if (slash >= 0 && slash < contentRange!!.length - 1) {
            contentRange.substring(slash + 1).trim().toLongOrNull()?.let { if (it > 0) return it }
        }
        return fallback
    }

    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1e6)
        bytes >= 1_000L -> String.format("%.0f KB", bytes / 1e3)
        else -> "$bytes B"
    }
}
