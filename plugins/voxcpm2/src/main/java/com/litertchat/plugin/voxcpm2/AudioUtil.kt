// Ponko-EX · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.plugin.voxcpm2

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 把宿主交过来的任意格式参考音频（wav / mp3 / m4a / flac…）转成原生侧唯一认识的东西：
 * **16 kHz 单声道 16-bit PCM WAV**。
 *
 * 输入是 [FileDescriptor] 而不是路径 —— 宿主和插件互相读不到对方的目录，
 * 跨进程只能传 FD（见插件手册第 8 节）。
 */
object AudioUtil {

    const val TARGET_SAMPLE_RATE = 16000

    private data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun toReferenceWav(fd: FileDescriptor, out: File): Boolean {
        val pcm = decodeToMono(fd) ?: return false
        if (pcm.samples.isEmpty()) return false
        val resampled = resample(pcm.samples, pcm.sampleRate, TARGET_SAMPLE_RATE)
        return writeWav(out, resampled, TARGET_SAMPLE_RATE)
    }

    /** 读 44 字节 WAV 头的时长；不是 WAV 或读不出来返回 0 */
    fun wavDurationSeconds(file: File): Double = try {
        val head = ByteArray(44)
        file.inputStream().use { it.read(head) }
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = bb.getInt(24)
        val channels = bb.getShort(22).toInt().coerceAtLeast(1)
        val bits = bb.getShort(34).toInt().coerceAtLeast(16)
        val dataBytes = (file.length() - 44).coerceAtLeast(0)
        dataBytes.toDouble() / (sampleRate * channels * (bits / 8))
    } catch (t: Throwable) {
        0.0
    }

    private fun decodeToMono(fd: FileDescriptor): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(fd)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)

            var inputDone = false
            var outputDone = false
            var guard = 0

            while (!outputDone && guard++ < 500_000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outIdx >= 0) {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(chunk)
                                out.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return null

            val frames: Int
            val samples: FloatArray
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                frames = fb.remaining() / channels
                samples = FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until channels) sum += fb.get(i * channels + c)
                    sum / channels
                }
            } else {
                val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                frames = sb.remaining() / channels
                samples = FloatArray(frames) { i ->
                    var sum = 0f
                    for (c in 0 until channels) sum += sb.get(i * channels + c) / 32768f
                    sum / channels
                }
            }
            return Pcm(samples, sampleRate)
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    /** 线性插值重采样。参考音频只有几秒，够用且没有依赖 */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val step = from.toDouble() / to.toDouble()
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }

    private fun writeWav(file: File, pcm: FloatArray, sampleRate: Int): Boolean {
        val dataBytes = pcm.size * 2
        return try {
            FileOutputStream(file).use { fos ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray())
                header.putInt(36 + dataBytes)
                header.put("WAVE".toByteArray())
                header.put("fmt ".toByteArray())
                header.putInt(16)
                header.putShort(1)
                header.putShort(1)
                header.putInt(sampleRate)
                header.putInt(sampleRate * 2)
                header.putShort(2)
                header.putShort(16)
                header.put("data".toByteArray())
                header.putInt(dataBytes)
                fos.write(header.array())

                val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (v in pcm) {
                    val clamped = v.coerceIn(-1f, 1f)
                    body.putShort((clamped * 32767f).roundToInt().toShort())
                }
                fos.write(body.array())
                fos.flush()
            }
            true
        } catch (t: Throwable) {
            false
        }
    }
}
