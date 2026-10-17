package com.jedrama.fork.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/** Fournisseurs d'images proposes dans les Parametres. */
enum class ImageProvider { POLLINATIONS, OFFLINE }

/**
 * Images des plans.
 *
 * - POLLINATIONS : generation par IA, gratuite et sans cle (image.pollinations.ai).
 *   En anonyme (constate en 10/2026) : modele impose par le service quel que soit
 *   `model`, image plafonnee a 576x1024 et logo en bas a droite. On recadre
 *   donc le bas de l'image avant de la mettre au format. L'acces anonyme est
 *   limite a environ une image toutes les 15 s : on espace les requetes, et
 *   toute erreur retombe sur l'image hors ligne plutot que d'arreter l'episode.
 * - OFFLINE : visuel abstrait dessine sur le telephone (degrades, halos), sans
 *   reseau ni IA. Toujours disponible.
 */
class Images(private val provider: ImageProvider, private val width: Int, private val height: Int) {

    private var lastRemoteCall = 0L

    /** Ecrit un JPEG dans [out]. Rend vrai si l'image vient de l'IA. */
    suspend fun shot(prompt: String, seed: Int, out: File, imageCache: ImageCache? = null): Boolean {
        out.parentFile?.mkdirs()

        // Verification du cache disque avant tout telechargement distant
        imageCache?.let { cache ->
            val cached = cache.get(prompt, seed)
            if (cached != null && cached.exists()) {
                cached.copyTo(out, overwrite = true)
                return false
            }
        }

        if (provider == ImageProvider.POLLINATIONS) {
            repeat(REMOTE_ATTEMPTS) { attempt ->
                try {
                    remote(prompt, seed + attempt, out)
                    imageCache?.put(prompt, seed, BitmapFactory.decodeFile(out.path))
                    return true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Image hors ligne en dernier recours, ci-dessous.
                }
            }
        }
        offline(seed, out)
        return false
    }

    /** Couverture : image de la serie, titre incruste. */
    suspend fun cover(prompt: String, title: String, seed: Int, out: File) {
        shot(prompt, seed, out)
        val base = BitmapFactory.decodeFile(out.path)?.copy(Bitmap.Config.ARGB_8888, true)
            ?: return offline(seed, out)
        val canvas = Canvas(base)
        val shade = Paint().apply {
            shader = LinearGradient(0f, base.height * 0.55f, 0f, base.height.toFloat(),
                Color.TRANSPARENT, Color.argb(230, 14, 11, 22), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, base.width.toFloat(), base.height.toFloat(), shade)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = base.width * 0.085f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setShadowLayer(12f, 0f, 4f, Color.BLACK)
        }
        val margin = base.width * 0.07f
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, (base.width - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(margin, base.height * 0.86f - layout.height)
        layout.draw(canvas)
        canvas.restore()
        save(base, out)
    }

    private suspend fun remote(prompt: String, seed: Int, out: File) {
        val wait = REMOTE_SPACING_MS - (System.currentTimeMillis() - lastRemoteCall)
        if (wait > 0) delay(wait)
        lastRemoteCall = System.currentTimeMillis()

        val encoded = URLEncoder.encode(prompt.take(MAX_PROMPT_CHARS), "UTF-8").replace("+", "%20")
        val url = URL(
            "https://image.pollinations.ai/prompt/$encoded" +
                "?width=$width&height=$height&seed=$seed&nologo=true&safe=true&model=flux",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 180_000
        }
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("image illisible")
            // Logo du service en bas a droite, puis recadrage au format exact :
            // FFmpeg attend des dimensions stables.
            save(fit(withoutWatermark(bitmap)), out)
        } finally {
            connection.disconnect()
        }
    }

    /** Retire la bande basse (8 %) et autant en largeur, centre : ratio conserve. */
    private fun withoutWatermark(source: Bitmap): Bitmap {
        val height = (source.height * (1 - WATERMARK_BAND)).toInt()
        val width = (source.width * (1 - WATERMARK_BAND)).toInt()
        return Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, height)
    }

    private fun fit(source: Bitmap): Bitmap {
        if (source.width == width && source.height == height) return source
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val scaled = Bitmap.createScaledBitmap(
            source, (source.width * scale).toInt().coerceAtLeast(width),
            (source.height * scale).toInt().coerceAtLeast(height), true,
        )
        return Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height)
    }

    /** Visuel abstrait dans la direction artistique (nuit violette, halos dores). */
    fun offline(seed: Int, out: File) {
        val random = Random(seed)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val hue = PALETTE_HUES[random.nextInt(PALETTE_HUES.size)] + random.nextInt(-15, 15)
        val top = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.22f))
        val bottom = Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.65f, 0.07f))
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, width * 0.3f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(14) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val radius = width * (0.05f + random.nextFloat() * 0.35f)
            val warm = random.nextFloat() < 0.4f
            val color = if (warm) Color.argb(70, 255, 213, 79) else Color.argb(60, 160, 110, 255)
            glow.shader = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius, glow)
        }
        canvas.drawPaint(Paint().apply {
            shader = RadialGradient(width / 2f, height / 2f, height * 0.75f,
                Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
        })
        save(bitmap, out)
    }

    private fun save(bitmap: Bitmap, out: File) {
        out.parentFile?.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    companion object {
        private const val REMOTE_ATTEMPTS = 2
        private const val REMOTE_SPACING_MS = 16_000L
        private const val MAX_PROMPT_CHARS = 900
        private const val WATERMARK_BAND = 0.08f
        private val PALETTE_HUES = listOf(265, 280, 300, 230, 330)

        /**
         * STYLE_LOCK â€” immuable : c'est ce qui garde le meme rendu d'un plan et
         * d'un episode a l'autre. La description physique de chaque personnage
         * present est reinjectee mot pour mot.
         */
        const val STYLE_LOCK =
            "cinematic vertical still, soft volumetric light, shallow depth of field, " +
                "muted violet and gold palette, 35mm film grain, photorealistic, " +
                "no text, no watermark, no logo"
    }
}

