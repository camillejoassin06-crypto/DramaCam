package com.jedrama.fork.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.*

/**
 * Cache disque pour les images gÃ©nÃ©rÃ©es (Pollinations ou hors ligne).
 * Ã‰vite de re-tÃ©lÃ©charger ou de re-dessiner une image dÃ©jÃ  produite pour un
 * plan donnÃ© (mÃªme concept + mÃªme Ã©pisode + mÃªme numÃ©ro de plan).
 */
class ImageCache(private val context: Context) {

    private val root = File(context.cacheDir, "image-cache").apply { mkdirs() }

    /** ClÃ© basÃ©e sur le prompt + seed pour identifier une image de maniÃ¨re unique. */
    fun keyFor(prompt: String, seed: Int): String {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val digest = md5.digest("$prompt|$seed".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** RÃ©cupÃ¨re une image du cache, null si absente. */
    fun get(prompt: String, seed: Int): File? {
        val file = File(root, "${keyFor(prompt, seed)}.jpg")
        return if (file.exists() && file.length() > 0) file else null
    }

    /** Stocke une image dans le cache. */
    suspend fun put(prompt: String, seed: Int, bitmap: Bitmap): File {
        val file = File(root, "${keyFor(prompt, seed)}.jpg")
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    /** Efface tout le cache (utile pour libÃ©rer de l'espace). */
    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
    }

    /** Taille totale du cache en octets. */
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val MAX_CACHE_ENTRIES = 500
    }
}
