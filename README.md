# LetraMC

App Android para ver, escribir y borrar la letra dentro de archivos MP3.

Etapa 1: leer / pegar / guardar con o sin tiempos / borrar la letra (etiqueta USLT).
Etapa 2: transcribir la letra del audio con Whisper (sin internet; el modelo se descarga una vez).

Formato con tiempos: `[00:12.30] línea`.

## Compilar
Al subir a GitHub (rama `main`), Actions descarga whisper.cpp y compila.
El APK queda en **Releases** (`app-debug.apk`).
