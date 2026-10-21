// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 权重清单 + 断点续传下载。
 *
 * 权重**不进 APK**（3.3 GB 起步，塞进去没法发版），首次使用时下到插件自己的
 * 外部私有目录：`Android/data/com.litertchat.plugin.voxcpm2/files/models/`。
 *
 * ## 为什么给两个源
 *
 * VoxCPM2 官方权重是 Apache-2.0，但**第三方转的 GGUF 不一定会声明许可**。
 * 所以默认用声明了 apache-2.0 的那个仓库；想省 0.5 GB 再切到另一个，
 * 界面上会把这件事写清楚，不藏着。
 */
object ModelStore {

    /** 一个权重文件 */
    data class Spec(
        val fileName: String,
        val label: String,
        val bytes: Long,
    )

    /** 一套可用的组合：仓库 + 两个文件 */
    data class Source(
        val key: String,
        val label: String,
        val baseUrl: String,
        val baseLm: Spec,
        val acoustic: Spec,
        val note: String,
    ) {
        val totalBytes: Long get() = baseLm.bytes + acoustic.bytes
        fun files(): List<Spec> = listOf(baseLm, acoustic)
    }

    /**
     * 默认源。`DennisHuang/VoxCPM2-GGUF` 在魔搭上**明确标了 apache-2.0**，
     * 和官方权重同一许可，出了问题归属清楚。
     */
    private val LICENSED = Source(
        key = "licensed",
        label = "标准源（Q8_0，许可明确）",
        baseUrl = "https://modelscope.cn/models/DennisHuang/VoxCPM2-GGUF/resolve/master/",
        baseLm = Spec("VoxCPM2-BaseLM-Q8_0.gguf", "BaseLM Q8_0", 1_727_309_920L),
        acoustic = Spec("VoxCPM2-Acoustic-F16.gguf", "Acoustic F16", 1_825_096_352L),
        note = "仓库声明 apache-2.0；BaseLM 精度更高，音质略好，多占 0.7 GB",
    )

    /**
     * 省流量源：官方 Android Demo 用的那套 Q4_K_M。
     * 仓库**没有填 license 字段**，所以不设成默认。
     */
    private val COMPACT = Source(
        key = "compact",
        label = "精简源（Q4_K_M，省 0.7 GB）",
        baseUrl = "https://modelscope.cn/models/zerg13/MiniCPM-V-Apps-gguf/resolve/master/",
        baseLm = Spec("VoxCPM2-BaseLM-Q4_K_M.gguf", "BaseLM Q4_K_M", 1_002_374_880L),
        acoustic = Spec("VoxCPM2-Acoustic-F16.gguf", "Acoustic F16", 1_825_096_352L),
        note = "第三方 GGUF 转换件，仓库未声明许可；上游权重仍是 apache-2.0",
    )

    val SOURCES: List<Source> = listOf(LICENSED, COMPACT)

    fun sourceAt(index: Int): Source = SOURCES[index.coerceIn(0, SOURCES.size - 1)]

    fun sourceIndex(key: String?): Int {
        val i = SOURCES.indexOfFirst { it.key == key }
        return if (i >= 0) i else 0
    }

    fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun fileFor(ctx: Context, spec: Spec): File = File(modelDir(ctx), spec.fileName)

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

    fun readyCount(ctx: Context): Int = SOURCES.maxOf { src ->
        src.files().count { isReady(ctx, it) }
    }

    fun installedBytes(ctx: Context): Long =
        SOURCES.flatMap { it.files() }.distinctBy { it.fileName }
            .sumOf { fileFor(ctx, it).let { f -> if (f.isFile) f.length() else 0L } }

    /** 一套源里的两个文件都在，才算这套能用 */
    fun isSourceReady(ctx: Context, src: Source): Boolean =
        src.files().all { isReady(ctx, it) }

    fun usableSpace(ctx: Context): Long = modelDir(ctx).usableSpace

    /** 删掉所有已下载权重（两个源共用 Acoustic，一起清） */
    fun deleteAll(ctx: Context): Long {
        var freed = 0L
        SOURCES.flatMap { it.files() }.distinctBy { it.fileName }.forEach { spec ->
            listOf(fileFor(ctx, spec), File(fileFor(ctx, spec).absolutePath + ".part")).forEach {
                if (it.isFile) {
                    freed += it.length()
                    it.delete()
                }
            }
        }
        return freed
    }

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
                setRequestProperty("User-Agent", "PonkoVoxCPM2/0.1")
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
                else -> return Result.failure(IllegalStateException("HTTP $code（${url.host}）"))
            }

            var downloaded = existing
            onProgress(downloaded, total)

            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(existing) else raf.setLength(0)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var lastTick = 0L
                    while (true) {
                        if (cancelled.get()) return Result.failure(InterruptedException("已取消"))
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
                    IllegalStateException("连接中断（${part.length()}/$total 字节），可再次点击继续")
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
