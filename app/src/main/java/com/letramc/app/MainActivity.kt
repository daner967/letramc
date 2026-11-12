package com.letramc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kyant.taglib.TagLib

class MainActivity : Activity() {

    private var audioUri: Uri? = null
    private lateinit var fileLabel: TextView
    private lateinit var lyricsBox: EditText
    private lateinit var keepTimes: CheckBox
    private lateinit var status: TextView

    // Marcas de tiempo al inicio de línea, por ejemplo: [00:12.30]
    private val timeTag = Regex("""^(?:[ \t]*\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\][ \t]*)+""", RegexOption.MULTILINE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val pickButton = Button(this).apply {
            text = "Elegir MP3"
            setOnClickListener { pickAudio() }
        }
        fileLabel = TextView(this).apply {
            text = "Ningún archivo elegido"
            setPadding(0, dp(8), 0, dp(8))
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
        val saveButton = Button(this).apply {
            text = "Guardar letra en el MP3"
            setOnClickListener { saveLyrics() }
        }
        val deleteButton = Button(this).apply {
            text = "Borrar letra del MP3"
            setOnClickListener { confirmDelete() }
        }
        status = TextView(this).apply {
            setPadding(0, dp(12), 0, 0)
        }

        root.addView(pickButton)
        root.addView(fileLabel)
        root.addView(lyricsBox)
        root.addView(keepTimes)
        root.addView(saveButton)
        root.addView(deleteButton)
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
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
