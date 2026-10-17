package com.jedrama.fork.media

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Voix off avec le moteur de synthese vocale du telephone (Google, Samsung...).
 * Gratuit, hors ligne si la voix francaise est telechargee, aucune cle.
 *
 * Les voix abstraites du concept ("femme-claire", "homme-grave"...) sont
 * traduites en voix du moteur quand il en propose plusieurs, puis en hauteur
 * et debit : meme avec une seule voix installee, chaque personnage reste
 * reconnaissable.
 */
class Speech private constructor(private val tts: TextToSpeech) {

    class SpeechException(message: String) : Exception(message)

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val voices: List<Voice>

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.completeExceptionally(SpeechException("synthese vocale en echec"))
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)
                    ?.completeExceptionally(SpeechException("synthese vocale en echec (code $errorCode)"))
            }
        })
        voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { voice ->
                // fr-FR uniquement (pas fr-CA), installee et hors ligne : une voix
                // listee mais non telechargee fait echouer la synthese.
                voice.locale.language == "fr" && voice.locale.country.equals("FR", ignoreCase = true) &&
                    !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            }
            .sortedBy { it.name }
    }

    /** Ecrit la replique dans [out] (WAV). */
    suspend fun synthesize(text: String, voiceId: String, out: File) {
        val profile = profileOf(voiceId)
        pickVoice(profile)?.let { tts.voice = it } ?: tts.setLanguage(Locale.FRANCE)
        tts.setPitch(profile.pitch)
        tts.setSpeechRate(profile.rate)

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        out.parentFile?.mkdirs()
        val status = tts.synthesizeToFile(text, Bundle(), out, id)
        if (status != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw SpeechException("le moteur de synthese a refuse la replique")
        }
        if (withTimeoutOrNull(120_000) { done.await() } == null) {
            pending.remove(id)
            throw SpeechException("synthese vocale bloquee (plus de 2 minutes) pour : ${text.take(40)}")
        }
        if (!out.exists() || out.length() <= WAV_HEADER) {
            throw SpeechException("fichier audio vide pour : ${text.take(40)}")
        }
    }

    fun shutdown() = tts.shutdown()

    private data class Profile(val female: Boolean?, val pitch: Float, val rate: Float, val slot: Int)

    private fun profileOf(voiceId: String): Profile = when (voiceId) {
        "femme-claire" -> Profile(true, 1.15f, 1.02f, 0)
        "femme-grave" -> Profile(true, 0.95f, 0.95f, 1)
        "homme-clair" -> Profile(false, 1.0f, 1.03f, 0)
        "homme-grave" -> Profile(false, 0.78f, 0.93f, 1)
        "voix-jeune" -> Profile(null, 1.3f, 1.08f, 2)
        "voix-agee" -> Profile(null, 0.85f, 0.85f, 3)
        else -> Profile(null, 1.0f, 0.97f, 4) // narrateur
    }

    /**
     * Les voix Google fr-FR se nomment fr-fr-x-fra/frb/frc/frd ; fra et frc sont
     * feminines, frb et frd masculines. Sur un autre moteur, on se contente de
     * repartir les voix disponibles.
     */
    private fun pickVoice(profile: Profile): Voice? {
        if (voices.isEmpty()) return null
        val gendered = when (profile.female) {
            true -> voices.filter { v -> FEMALE.any { v.name.contains(it) } }
            false -> voices.filter { v -> MALE.any { v.name.contains(it) } }
            null -> emptyList()
        }
        val pool = gendered.ifEmpty { voices }
        return pool[profile.slot % pool.size]
    }

    companion object {
        const val NARRATOR_VOICE = "narrateur"
        private const val WAV_HEADER = 44L
        private val FEMALE = listOf("-x-fra", "-x-frc")
        private val MALE = listOf("-x-frb", "-x-frd")

        /** Initialise le moteur ; leve si aucune voix francaise n'est utilisable. */
        suspend fun create(context: Context): Speech {
            val ready = CompletableDeferred<Int>()
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(context.applicationContext) { status -> ready.complete(status) }
            val status = withTimeoutOrNull(30_000) { ready.await() } ?: run {
                engine.shutdown()
                throw SpeechException("le moteur de synthese vocale ne repond pas")
            }
            if (status != TextToSpeech.SUCCESS) {
                engine.shutdown()
                throw SpeechException("moteur de synthese vocale indisponible")
            }
            val language = engine.setLanguage(Locale.FRANCE)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.shutdown()
                throw SpeechException(
                    "voix francaise absente : installez-la dans Parametres Android > Synthese vocale",
                )
            }
            return Speech(engine)
        }
    }
}

