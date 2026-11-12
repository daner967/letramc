package com.letramc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.kyant.taglib.TagLib

class MainActivity : Activity() {

    private var audioUri: Uri? = null
    private var busy = false

    private lateinit var pickButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var lyricsBox: EditText
    private lateinit var keepTimes: CheckBox
    private lateinit var transcribeButton: Button
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var modelSpinner: Spinner
    private lateinit var langSpinner: Spinner
    private lateinit var status: TextView

    private val models = listOf(
        "Base (rápido, ~140 MB)" to "ggml-base.bin",
        "Small (mejor calidad, ~470 MB)" to "ggml-small.bin",
    )
    private val languages = listOf(
        "Detectar idioma" to "auto",
        "Español" to "es",
        "English" to "en",
    )

    // Marcas de tiempo al inicio de línea, por ejemplo: [00:12.30]
    private val timeTag = Regex("""^(?:[ \t]*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\][ \t]*)+""", RegexOption.MULTILINE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        pickButton = Button(this).apply {
            text = "Elegir MP3"
            setOnClickListener { pickAudio() }
        }
        fileLabel = TextView(this).apply {
            text = "Ningún archivo elegido"
            setPadding(0, dp(8), 0, dp(8))
        }
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                models.map { it.first }
            )
        }
        langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                languages.map { it.first }
            )
        }
        transcribeButton = Button(this).apply {
            text = "Transcribir la letra del audio"
            setOnClickListener { confirmTranscribe() }
        }
        lyricsBox = EditText(this).apply {
            hint = "Aquí aparece la letra del MP3.\nPega o edita la letra.\nCon tiempos: [00:12.30] línea"
            minLines = 12
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        keepTimes = CheckBox(this).apply {
            text = "Conservar tiempos [mm:ss.xx] (si lo desmarcas, se guarda sin tiempos)"
            isChecked = true
        }
        saveButton = Button(this).apply {
            text = "Guardar letra en el MP3"
            setOnClickListener { saveLyrics() }
        }
        deleteButton = Button(this).apply {
            text = "Borrar letra del MP3"
            setOnClickListener { confirmDelete() }
        }
        status = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
        }

        root.addView(pickButton)
        root.addView(fileLabel)
        root.addView(TextView(this).apply { text = "Modelo de transcripción" })
        root.addView(modelSpinner)
        root.addView(TextView(this).apply { text = "Idioma de la canción" })
        root.addView(langSpinner)
        root.addView(transcribeButton)
        root.addView(lyricsBox)
        root.addView(keepTimes)
        root.addView(saveButton)
        root.addView(deleteButton)
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun setBusy(b: Boolean) {
        busy = b
        listOf(pickButton, transcribeButton, saveButton, deleteButton).forEach { it.isEnabled = !b }
        if (b) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun pickAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        audioUri = uri
        fileLabel.text = displayName(uri)
        try {
            val existing = readLyrics(uri)
            lyricsBox.setText(existing ?: "")
            status.text = if (existing.isNullOrBlank()) {
                "Este MP3 no tiene letra."
            } else {
                "Letra encontrada (${describe(existing)})."
            }
        } catch (e: Exception) {
            status.text = "No se pudo leer el archivo: ${e.message}"
        }
    }

    // ---------- Transcripción ----------

    private fun confirmTranscribe() {
        if (busy) return
        val uri = audioUri
        if (uri == null) {
            status.text = "Primero elige un MP3."
            return
        }
        if (lyricsBox.text.isNotBlank()) {
            AlertDialog.Builder(this)
                .setMessage("¿Reemplazar el texto actual por la transcripción?")
                .setPositiveButton("Reemplazar") { _, _ -> runTranscription(uri) }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            runTranscription(uri)
        }
    }

    private fun runTranscription(uri: Uri) {
        val modelName = models[modelSpinner.selectedItemPosition].second
        val lang = languages[langSpinner.selectedItemPosition].second
        setBusy(true)
        status.text = "Preparando… Mantén la app abierta y la pantalla encendida."

        Thread {
            try {
                val modelFile = ModelStore.ensure(this, modelName) { p ->
                    setStatus("Descargando modelo (solo la primera vez)… $p%")
                }
                setStatus("Decodificando audio…")
                val samples = AudioDecoder.decode(this, uri) { p ->
                    setStatus("Decodificando audio… $p%")
                }
                if (samples.isEmpty()) throw IllegalStateException("No se pudo leer el audio.")

                setStatus("Cargando modelo…")
                val lib = WhisperLib()
                val ctx = lib.nativeInit(modelFile.absolutePath)
                if (ctx == 0L) {
                    modelFile.delete()
                    throw IllegalStateException("No se pudo cargar el modelo. Se borró; vuelve a intentarlo para descargarlo de nuevo.")
                }
                try {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    val ret = lib.nativeTranscribe(
                        ctx, samples, lang, threads,
                        ProgressListener { p -> setStatus("Transcribiendo… $p%") }
                    )
                    if (ret != 0) throw IllegalStateException("Whisper falló (código $ret).")
                    val result = WhisperLib.formatSegments(lib.nativeResult(ctx))
                    runOnUiThread {
                        lyricsBox.setText(result)
                        status.text = if (result.isBlank()) {
                            "No se detectó letra en el audio."
                        } else {
                            "Listo. Revisa y corrige la letra, y luego guárdala."
                        }
                    }
                } finally {
                    lib.nativeFree(ctx)
                }
            } catch (e: Throwable) {
                setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }.start()
    }

    // ---------- Guardar y borrar ----------

    private fun saveLyrics() {
        val uri = audioUri
        if (uri == null) {
            status.text = "Primero elige un MP3."
            return
        }
        var text = lyricsBox.text.toString().trim()
        if (text.isEmpty()) {
            status.text = "La letra está vacía. Usa «Borrar letra» si quieres quitarla."
            return
        }
        if (!keepTimes.isChecked) {
            text = timeTag.replace(text, "")
            lyricsBox.setText(text)
        }
        try {
            val ok = writeLyrics(uri, text)
            status.text = if (ok) {
                "Letra guardada (${describe(text)})."
            } else {
                "No se pudo guardar. Prueba elegir el archivo desde el explorador de archivos (Descargas o Almacenamiento interno), no desde la categoría «Audio»."
            }
        } catch (e: Exception) {
            status.text = "No se pudo guardar: ${e.message}"
        }
    }

    private fun confirmDelete() {
        val uri = audioUri
        if (uri == null) {
            status.text = "Primero elige un MP3."
            return
        }
        AlertDialog.Builder(this)
            .setMessage("¿Borrar la letra de este MP3?")
            .setPositiveButton("Borrar") { _, _ ->
                try {
                    val ok = writeLyrics(uri, null)
                    if (ok) lyricsBox.setText("")
                    status.text = if (ok) "Letra borrada." else "No se pudo borrar la letra."
                } catch (e: Exception) {
                    status.text = "No se pudo borrar: ${e.message}"
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun readLyrics(uri: Uri): String? {
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val metadata = TagLib.getMetadata(pfd.dup().detachFd(), false)
            return metadata?.propertyMap?.get("LYRICS")?.firstOrNull()
        }
        return null
    }

    // text = null borra la letra existente
    private fun writeLyrics(uri: Uri, text: String?): Boolean {
        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            val metadata = TagLib.getMetadata(pfd.dup().detachFd(), false) ?: return false
            val map = metadata.propertyMap
            if (text.isNullOrBlank()) {
                map.remove("LYRICS")
            } else {
                map["LYRICS"] = arrayOf(text)
            }
            return TagLib.savePropertyMap(pfd.dup().detachFd(), map)
        }
        return false
    }

    private fun describe(text: String) =
        if (timeTag.containsMatchIn(text)) "con tiempos" else "sin tiempos"

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment ?: "archivo"
    }

    companion object {
        private const val REQ_PICK = 1
    }
}
