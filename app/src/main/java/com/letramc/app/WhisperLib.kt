package com.letramc.app

import java.util.Locale

fun interface ProgressListener {
    fun onProgress(percent: Int)
}

class WhisperLib {
    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ctx: Long)
    external fun nativeTranscribe(
        ctx: Long,
        samples: FloatArray,
        lang: String,
        threads: Int,
        listener: ProgressListener
    ): Int

    external fun nativeResult(ctx: Long): ByteArray

    companion object {
        init {
            System.loadLibrary("letramc_whisper")
        }

        // Convierte "centésimas|texto" en "[mm:ss.xx] texto"
        fun formatSegments(raw: ByteArray): String {
            val text = String(raw, Charsets.UTF_8)
            // Líneas que son solo una etiqueta como [Música] o (aplausos)
            val tagOnly = Regex("""^[\[(][^\])]*[\])]$""")
            val lines = ArrayList<String>()
            for (line in text.lineSequence()) {
                val sep = line.indexOf('|')
                if (sep <= 0) continue
                val cs = line.substring(0, sep).toLongOrNull() ?: continue
                val t = line.substring(sep + 1).trim()
                if (t.isEmpty() || tagOnly.matches(t)) continue
                lines.add(
                    String.format(
                        Locale.US, "[%02d:%02d.%02d] %s",
                        cs / 6000, (cs % 6000) / 100, cs % 100, t
                    )
                )
            }
            return lines.joinToString("\n")
        }
    }
}
