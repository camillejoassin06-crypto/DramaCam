package com.jedrama.fork.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpeg embarque dans l'APK.
 *
 * Le binaire est compile par scripts/build-ffmpeg-android.sh et range dans
 * jniLibs sous le nom libffmpeg.so : c'est le seul emplacement depuis lequel
 * Android 10+ autorise une app a executer un fichier (nativeLibraryDir). On le
 * lance donc comme un ffmpeg de bureau, avec ProcessBuilder.
 */
class Ffmpeg(context: Context) {

    class FfmpegException(message: String) : Exception(message)

    val binary: File = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    val isAvailable: Boolean get() = binary.exists()

    /** Premiere ligne de `ffmpeg -version`, pour l'ecran Parametres. */
    suspend fun version(): String = run(listOf("-version"), quiet = false).lineSequence().first()

    /**
     * Execute ffmpeg et rend sa sortie. Leve avec la fin du journal si le code
     * de retour n'est pas 0 : c'est la seule information utile pour diagnostiquer
     * un filtre ou un fichier refuse.
     */
    suspend fun run(args: List<String>, quiet: Boolean = true): String = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            throw FfmpegException("FFmpeg absent de l'APK (${binary.path}). Recompilez avec scripts/build-ffmpeg-android.sh.")
        }
        val command = buildList {
            add(binary.path)
            if (quiet) addAll(listOf("-hide_banner", "-nostdin", "-loglevel", "error", "-y"))
            addAll(args)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(output) { output.appendLine(line) } }
            }
        }.apply { start() }
        try {
            // Attente par tranches pour qu'une annulation (WorkManager stoppe le
            // job) tue le processus au lieu de le laisser tourner en orphelin.
            // waitFor(timeout) n'existe qu'a partir de l'API 26 : on sonde.
            while (!process.hasExited()) {
                currentCoroutineContext().ensureActive()
                delay(250)
            }
        } catch (error: Throwable) {
            process.destroy()
            throw error
        }
        reader.join(2_000)
        val log = synchronized(output) { output.toString() }
        if (process.exitValue() != 0) {
            throw FfmpegException("ffmpeg a echoue (${process.exitValue()}) : ${log.lines().takeLast(12).joinToString("\n")}")
        }
        log
    }
}

private fun Process.hasExited(): Boolean = try {
    exitValue()
    true
} catch (_: IllegalThreadStateException) {
    false
}

