package com.jedrama.fork.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jedrama.fork.generation.ClaudeTextModel
import com.jedrama.fork.generation.GeminiTextModel
import com.jedrama.fork.generation.GenerationLimits
import com.jedrama.fork.generation.TextModel
import com.jedrama.fork.generation.TextProvider
import com.jedrama.fork.media.ImageProvider
import com.jedrama.fork.media.RenderSettings

/**
 * Reglages de l'app.
 *
 * Les cles API sont dans des EncryptedSharedPreferences (cle maitre du Keystore
 * materiel). Elles ne sont jamais dans l'APK ni dans le depot, et ne partent que
 * vers leur fournisseur (api.anthropic.com ou generativelanguage.googleapis.com).
 */
class SettingsStore(context: Context) {

    // Paresseux : un Keystore inutilisable ne doit pas faire planter l'ecran.
    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "jedrama-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences("jedrama-settings", Context.MODE_PRIVATE)

    // --- Modele de texte ----------------------------------------------------

    var provider: TextProvider
        get() = runCatching { TextProvider.valueOf(plain.getString(KEY_PROVIDER, null) ?: "") }
            .getOrDefault(TextProvider.CLAUDE)
        set(value) = plain.edit().putString(KEY_PROVIDER, value.name).apply()

    fun apiKey(provider: TextProvider = this.provider): String =
        runCatching { secure.getString(keyName(provider), "").orEmpty() }.getOrDefault("")

    fun hasApiKey(provider: TextProvider = this.provider): Boolean = apiKey(provider).isNotBlank()

    /** `commit()` pour que l'echec d'ecriture soit connu tout de suite. */
    fun saveApiKey(value: String, provider: TextProvider = this.provider): Result<Unit> = runCatching {
        val saved = secure.edit().putString(keyName(provider), value.trim()).commit()
        check(saved) { "ecriture refusee par le stockage chiffre" }
    }

    fun clearApiKey(provider: TextProvider = this.provider): Result<Unit> = runCatching {
        secure.edit().remove(keyName(provider)).commit()
        Unit
    }

    var claudeModel: String
        get() = plain.getString(KEY_CLAUDE_MODEL, null)?.takeIf { it in ClaudeTextModel.MODELS }
            ?: ClaudeTextModel.MODELS.first()
        set(value) = plain.edit().putString(KEY_CLAUDE_MODEL, value).apply()

    /** Levier de cout principal de Claude : un effort bas consomme nettement moins. */
    var effort: String
        get() = plain.getString(KEY_EFFORT, "low") ?: "low"
        set(value) = plain.edit().putString(KEY_EFFORT, value).apply()

    var geminiModel: String
        get() = plain.getString(KEY_GEMINI_MODEL, null)?.takeIf { it.isNotBlank() } ?: GeminiTextModel.DEFAULT_MODEL
        set(value) = plain.edit().putString(KEY_GEMINI_MODEL, value.trim()).apply()

    fun textModel(): TextModel = when (provider) {
        TextProvider.CLAUDE -> ClaudeTextModel(apiKey(TextProvider.CLAUDE), claudeModel, effort)
        TextProvider.GEMINI -> GeminiTextModel(apiKey(TextProvider.GEMINI), geminiModel)
    }

    // --- Contenu et cadence -------------------------------------------------

    var imageProvider: ImageProvider
        get() = runCatching { ImageProvider.valueOf(plain.getString(KEY_IMAGES, null) ?: "") }
            .getOrDefault(ImageProvider.POLLINATIONS)
        set(value) = plain.edit().putString(KEY_IMAGES, value.name).apply()

    var episodesPerSeries: Int
        get() = plain.getInt(KEY_EPISODES, DEFAULT_EPISODES_PER_SERIES)
        set(value) = plain.edit()
            .putInt(KEY_EPISODES, value.coerceIn(1, GenerationLimits.EPISODES_PER_SERIES))
            .apply()

    /** Episodes rendus par execution quotidienne : la serie avance chaque jour. */
    var episodesPerDay: Int
        get() = plain.getInt(KEY_PER_DAY, DEFAULT_EPISODES_PER_DAY)
        set(value) = plain.edit().putInt(KEY_PER_DAY, value.coerceIn(1, MAX_EPISODES_PER_DAY)).apply()

    var safetyReviewEnabled: Boolean
        get() = plain.getBoolean(KEY_REVIEW, true)
        set(value) = plain.edit().putBoolean(KEY_REVIEW, value).apply()

    var dailyEnabled: Boolean
        get() = plain.getBoolean(KEY_DAILY, false)
        set(value) = plain.edit().putBoolean(KEY_DAILY, value).apply()

    /** Ne generer que sur secteur : le rendu video sollicite le processeur. */
    var onlyWhileCharging: Boolean
        get() = plain.getBoolean(KEY_CHARGING, true)
        set(value) = plain.edit().putBoolean(KEY_CHARGING, value).apply()

    var onlyOnUnmetered: Boolean
        get() = plain.getBoolean(KEY_WIFI, true)
        set(value) = plain.edit().putBoolean(KEY_WIFI, value).apply()

    var hdRendering: Boolean
        get() = plain.getBoolean(KEY_HD, false)
        set(value) = plain.edit().putBoolean(KEY_HD, value).apply()

    var ambience: Boolean
        get() = plain.getBoolean(KEY_AMBIENCE, true)
        set(value) = plain.edit().putBoolean(KEY_AMBIENCE, value).apply()

    fun renderSettings(): RenderSettings =
        if (hdRendering) RenderSettings(1080, 1920, 30, ambience) else RenderSettings(720, 1280, 25, ambience)

    var lastStatus: String
        get() = plain.getString(KEY_STATUS, "").orEmpty()
        set(value) = plain.edit().putString(KEY_STATUS, value).apply()

    companion object {
        const val DEFAULT_EPISODES_PER_SERIES = 20
        const val DEFAULT_EPISODES_PER_DAY = 5
        const val MAX_EPISODES_PER_DAY = 20

        private fun keyName(provider: TextProvider) = "api_key_${provider.name.lowercase()}"
        private const val KEY_PROVIDER = "text_provider"
        private const val KEY_CLAUDE_MODEL = "claude_model"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_EFFORT = "effort"
        private const val KEY_IMAGES = "image_provider"
        private const val KEY_EPISODES = "episodes_per_series"
        private const val KEY_PER_DAY = "episodes_per_day"
        private const val KEY_REVIEW = "safety_review"
        private const val KEY_DAILY = "daily_enabled"
        private const val KEY_CHARGING = "only_charging"
        private const val KEY_WIFI = "only_unmetered"
        private const val KEY_HD = "hd_rendering"
        private const val KEY_AMBIENCE = "ambience"
        private const val KEY_STATUS = "last_status"
    }
}

