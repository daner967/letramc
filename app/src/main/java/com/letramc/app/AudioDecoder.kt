package com.letramc.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

// Decodifica un audio a mono, 16 kHz, float (lo que necesita Whisper).
object AudioDecoder {

    private const val TARGET_RATE = 16000

    private class FloatList {
        var data = FloatArray(1 shl 20)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    // Reducción de frecuencia promediando (sirve de filtro suave)
    private class Resampler(srcRate: Int, private val out: FloatList) {
        private val ratio = srcRate.toDouble() / TARGET_RATE
        private var acc = 0.0
        private var weight = 0.0
        private var cellEnd = ratio
        private var index = 0L

        fun push(s: Float) {
            val end = index + 1.0
            var segStart = index.toDouble()
            while (segStart < end - 1e-9) {
                val segEnd = minOf(end, cellEnd)
                val w = segEnd - segStart
                acc += s * w
                weight += w
                segStart = segEnd
                if (segEnd >= cellEnd - 1e-9) {
                    out.add(if (weight > 0.0) (acc / weight).toFloat() else 0f)
                    acc = 0.0
                    weight = 0.0
                    cellEnd += ratio
                }
            }
            index++
        }

        fun finish() {
            if (weight > 0.0) out.add((acc / weight).toFloat())
        }
    }

    fun decode(context: Context, uri: Uri, onProgress: (Int) -> Unit): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) {
                throw IllegalStateException("El archivo no tiene pista de audio.")
            }
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val out = FloatList()
            var resampler: Resampler? = null
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastPercent = -1

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = c.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        if (resampler == null) resampler = Resampler(rate, out)
                        val frames = shorts.remaining() / channels
                        for (f in 0 until frames) {
                            var sum = 0
                            for (ch in 0 until channels) sum += shorts.get().toInt()
                            resampler.push(sum / (channels * 32768f))
                        }
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 100)
                            if (p != lastPercent) {
                                lastPercent = p
                                onProgress(p)
                            }
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = c.outputFormat
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            resampler?.finish()
            return out.toArray()
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }
}
