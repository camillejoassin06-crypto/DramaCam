package com.dramaforge.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dramaforge.app.generation.GenerationLimits
import com.dramaforge.app.generation.ScriptGenerator

/**
 * Preferences de generation.
 *
 * La cle API est stockee dans des EncryptedSharedPreferences (chiffrees par une
 * cle du Keystore materiel). Elle n'est JAMAIS incluse dans l'APK, ni envoyee
 * ailleurs qu'a api.anthropic.com : c'est l'utilisateur qui la saisit, sur son
 * propre appareil, avec sa propre cle.
 *
 * Limite a connaitre : un chiffrement local ne protege pas d'un appareil roote
 * ni d'un acces physique prolonge. Pour un usage personnel sur 2-3 appareils,
 * c'est le niveau attendu ; ce n'est pas un coffre-fort.
 */
class SettingsStore(context: Context) {

    // Paresseux et non eager : l'acces au Keystore peut echouer (appareil
    // exotique, donnees applicatives restaurees depuis une autre machine) et
    // cela ne doit pas faire planter l'ecran entier.
    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "dramaforge-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        context.getSharedPreferences("dramaforge-generation", Context.MODE_PRIVATE)

    /** Chaine vide si la cle est absente ou si le Keystore est inutilisable. */
    val apiKey: String
        get() = runCatching { secure.getString(KEY_API, "").orEmpty() }.getOrDefault("")

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * Enregistre la cle. Retourne l'echec plutot que de l'avaler : sans cela,
     * l'utilisateur croirait sa cle enregistree alors qu'elle est perdue.
     * `commit()` et non `apply()` pour que l'echec soit connu tout de suite.
     */
    fun saveApiKey(value: String): Result<Unit> = runCatching {
        val saved = secure.edit().putString(KEY_API, value.trim()).commit()
        check(saved) { "ecriture refusee par le stockage chiffre" }
    }

    /** Efface la cle sans toucher aux autres reglages. */
    fun clearApiKey(): Result<Unit> = runCatching {
        secure.edit().remove(KEY_API).commit()
        Unit
    }

    var model: String
        get() = plain.getString(KEY_MODEL, ScriptGenerator.MODELS.first())
            ?: ScriptGenerator.MODELS.first()
        set(value) = plain.edit().putString(KEY_MODEL, value).apply()

    /** Levier de cout principal : un effort plus bas consomme nettement moins. */
    var effort: String
        get() = plain.getString(KEY_EFFORT, "medium") ?: "medium"
        set(value) = plain.edit().putString(KEY_EFFORT, value).apply()

    var dailyEnabled: Boolean
        get() = plain.getBoolean(KEY_DAILY, false)
        set(value) = plain.edit().putBoolean(KEY_DAILY, value).apply()

    /** Revue d'originalite et de surete de chaque episode : double le nombre d'appels. */
    var safetyReviewEnabled: Boolean
        get() = plain.getBoolean(KEY_REVIEW, true)
        set(value) = plain.edit().putBoolean(KEY_REVIEW, value).apply()

    var episodesPerSeries: Int
        get() = plain.getInt(KEY_EPISODES, GenerationLimits.EPISODES_PER_SERIES)
        set(value) = plain.edit()
            .putInt(KEY_EPISODES, value.coerceIn(1, GenerationLimits.EPISODES_PER_SERIES))
            .apply()

    /** Dernier message d'etat de la generation, affiche dans les Parametres. */
    var lastStatus: String
        get() = plain.getString(KEY_STATUS, "").orEmpty()
        set(value) = plain.edit().putString(KEY_STATUS, value).apply()

    private companion object {
        const val KEY_API = "anthropic_api_key"
        const val KEY_MODEL = "model"
        const val KEY_EFFORT = "effort"
        const val KEY_DAILY = "daily_enabled"
        const val KEY_REVIEW = "safety_review"
        const val KEY_EPISODES = "episodes_per_series"
        const val KEY_STATUS = "last_status"
    }
}
