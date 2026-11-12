package com.letramc.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ModelStore {

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun modelFile(context: Context, name: String) = File(File(context.filesDir, "models"), name)

    // Devuelve el modelo; si no está, lo descarga (la primera vez).
    fun ensure(context: Context, name: String, onProgress: (Int) -> Unit): File {
        val dest = modelFile(context, name)
        if (dest.exists() && dest.length() > 10_000_000L) return dest

        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val conn = URL(BASE_URL + name).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                throw IOException("La descarga falló (HTTP ${conn.responseCode}).")
            }
            val total = conn.contentLengthLong
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("No se pudo guardar el modelo.")
        return dest
    }
}
