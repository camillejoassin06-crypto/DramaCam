package com.dramaforge.app.generation

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import org.json.JSONException
import org.json.JSONObject

/**
 * Generation de texte via l'API Anthropic, avec la cle fournie par
 * l'utilisateur dans les Parametres.
 *
 * ATTENTION COUT : chaque appel est facture sur la cle de l'utilisateur. Le
 * nombre d'appels d'une serie complete est de 2 + 2 par episode (script +
 * revue de surete), soit ~102 appels pour 50 episodes.
 *
 * Les prompts demandent explicitement un objet JSON ; la reponse est parsee
 * puis validee, et une sortie invalide consomme une tentative. C'est le meme
 * contrat que le pipeline TypeScript (zod), volontairement : un seul modele
 * mental pour les deux implementations.
 */
class ScriptGenerator(
    private val apiKey: String,
    private val model: String,
    private val effort: String,
    private val prompts: Prompts,
) {

    /**
     * Erreur fonctionnelle, avec un message affichable tel quel a l'utilisateur.
     *
     * `retryable` distingue deux familles :
     * - une sortie mal formee ou invalide (vrai) : une nouvelle tentative avec
     *   le motif de rejet corrige souvent le probleme ;
     * - une cle refusee, une requete invalide (faux) : reessayer ne fera que
     *   refacturer le meme echec.
     */
    class GenerationException(
        message: String,
        val retryable: Boolean = false,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    }

    fun concept(episodeCount: Int): Concept = attempt("concept") { hint ->
        val prompt = prompts.render(
            "concept",
            mapOf(
                "LANGUAGE" to "fr",
                "EPISODE_COUNT" to episodeCount,
                "EXCLUDED_TERMS" to prompts.excludedTerms.joinToString(", "),
                "VOICE_IDS" to VOICE_IDS.joinToString(", "),
            ),
        )
        val concept = DraftJson.toConcept(ask(prompt + hint))
        concept to concept.validate()
    }

    fun bible(concept: Concept, episodeCount: Int): Bible = attempt("bible") { hint ->
        val prompt = prompts.render(
            "bible",
            mapOf(
                "CONCEPT_JSON" to DraftJson.of(concept).toString(2),
                "EPISODE_COUNT" to episodeCount,
            ),
        )
        val bible = DraftJson.toBible(ask(prompt + hint))
        bible to bible.validate(episodeCount)
    }

    fun episode(concept: Concept, beat: Beat, previous: Beat?): EpisodeScript =
        attempt("episode ${beat.episode}") { hint ->
            val prompt = prompts.render(
                "episode",
                mapOf(
                    "CONCEPT_JSON" to DraftJson.of(concept).toString(2),
                    "BEAT_JSON" to DraftJson.of(beat).toString(2),
                    "PREVIOUS_BEAT_JSON" to
                        (previous?.let { DraftJson.of(it).toString(2) } ?: "aucun (premier episode)"),
                    "EPISODE_NUMBER" to beat.episode,
                    "SHOTS_PER_EPISODE" to GenerationLimits.SHOTS_PER_EPISODE,
                    "EPISODE_MIN_SECONDS" to GenerationLimits.EPISODE_MIN_SECONDS,
                    "EPISODE_MAX_SECONDS" to GenerationLimits.EPISODE_MAX_SECONDS,
                    "NARRATOR" to GenerationLimits.NARRATOR,
                ),
            )
            val script = DraftJson.toScript(ask(prompt + hint))
            script to script.validate(concept, beat.episode)
        }

    /** Revue d'originalite et de surete, exigee avant toute publication. */
    fun review(concept: Concept, script: EpisodeScript): QualityVerdict {
        val prompt = prompts.render(
            "quality",
            mapOf(
                "CONCEPT_JSON" to DraftJson.of(concept).toString(2),
                "SCRIPT_JSON" to DraftJson.of(script).toString(2),
            ),
        )
        val json = ask(prompt)
        return QualityVerdict().apply {
            originalContent = json.optBoolean("originalContent", false)
            characterConsistency = json.optBoolean("characterConsistency", false)
            safeForTargetRating = json.optBoolean("safeForTargetRating", false)
            val array = json.optJSONArray("concerns")
            concerns = if (array == null) emptyList() else List(array.length()) { array.optString(it) }
        }
    }

    /**
     * Boucle de tentatives : le motif de rejet precis est reinjecte dans la
     * tentative suivante, ce qui corrige beaucoup plus souvent qu'une simple
     * repetition de la demande.
     */
    private fun <T> attempt(label: String, block: (hint: String) -> Pair<T, Problems>): T {
        var lastReason = ""
        repeat(GenerationLimits.MAX_ATTEMPTS) { index ->
            val hint = if (index == 0) {
                ""
            } else {
                "\n\nLa tentative precedente a ete rejetee : $lastReason" +
                    "\nCorrige exactement ce point et renvoie uniquement le JSON."
            }
            try {
                val (value, problems) = block(hint)
                if (problems.isValid) return value
                lastReason = problems.toString()
            } catch (error: GenerationException) {
                // Une reponse illisible (prose, JSON tronque) doit consommer une
                // tentative comme une sortie invalide, pas interrompre la serie.
                // Une cle refusee, elle, ne se corrige pas en reessayant.
                if (!error.retryable) throw error
                lastReason = error.message.orEmpty()
            }
        }
        throw GenerationException(
            "$label : ${GenerationLimits.MAX_ATTEMPTS} tentatives epuisees. Dernier motif : $lastReason",
            retryable = false,
        )
    }

    private fun ask(prompt: String): JSONObject {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .addUserMessage(prompt)

        // La reflexion adaptative est active par defaut sur les modeles
        // recents ; l'effort est le principal levier de cout. Attention :
        // Haiku 4.5 ne connait pas ce parametre et renvoie une 400 si on
        // l'envoie — d'ou le filtre plutot qu'un envoi systematique.
        if (model in MODELS_WITH_EFFORT) {
            builder.outputConfig(OutputConfig.builder().effort(effortValue()).build())
        }

        val params = builder.build()

        val message = try {
            client.messages().create(params)
        } catch (error: UnauthorizedException) {
            throw GenerationException(
                "Cle API refusee (401). Verifiez-la dans les Parametres.",
                retryable = false,
                cause = error,
            )
        } catch (error: RateLimitException) {
            throw GenerationException(
                "Limite de debit atteinte (429). Reessayez plus tard.",
                retryable = true,
                cause = error,
            )
        } catch (error: AnthropicIoException) {
            throw GenerationException(
                "Reseau indisponible : ${error.message}",
                retryable = true,
                cause = error,
            )
        } catch (error: AnthropicServiceException) {
            // Une 400 vient d'une requete mal formee : la reessayer la refacture
            // sans rien changer.
            throw GenerationException(
                "Erreur de l'API : ${error.message}",
                retryable = false,
                cause = error,
            )
        }

        // Le motif d'arret explique les reponses illisibles bien mieux qu'un
        // echec de parsing : sans cette lecture, une troncature ressemble a un
        // modele qui ne sait pas repondre en JSON.
        val stopReason = message.stopReason().map { it.toString() }.orElse("")
        if (stopReason.contains("max_tokens", ignoreCase = true)) {
            throw GenerationException(
                "Reponse tronquee a $MAX_TOKENS tokens. Baissez l'effort ou le nombre " +
                    "d'episodes par serie.",
                retryable = true,
            )
        }
        if (stopReason.contains("refusal", ignoreCase = true)) {
            val detail = message.stopDetails()
                .map { " (${it})" }
                .orElse("")
            throw GenerationException(
                "Le modele a refuse de repondre$detail. Reformulez le prompt concerne.",
                retryable = false,
            )
        }

        val text = message.content()
            .mapNotNull { block -> block.text().orElse(null) }
            .joinToString("") { it.text() }

        if (text.isBlank()) {
            throw GenerationException(
                "Reponse vide du modele (motif d'arret : ${stopReason.ifBlank { "inconnu" }}).",
                retryable = true,
            )
        }
        return parseJsonObject(text)
    }

    /**
     * Appel minimal pour valider la cle et la joignabilite de l'API.
     *
     * Sert au bouton « Tester la cle » : sans lui, la seule facon de savoir
     * pourquoi une generation echoue serait de lancer une serie entiere.
     * Quelques tokens, donc quelques centiemes de centime.
     */
    fun verifyKey(): Result<String> = runCatching {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16L)
            .addUserMessage("Repondez uniquement par: OK")
            .build()
        val message = client.messages().create(params)
        val reply = message.content()
            .mapNotNull { block -> block.text().orElse(null) }
            .joinToString("") { it.text() }
            .trim()
        "$model : ${reply.ifBlank { "reponse vide" }}"
    }.recoverCatching { error ->
        throw when (error) {
            is UnauthorizedException ->
                GenerationException("Cle refusee (401). Verifiez la cle collee.", false, error)
            is RateLimitException ->
                GenerationException("Limite de debit (429). La cle est valide.", true, error)
            is AnthropicIoException ->
                GenerationException("Pas de reseau : ${error.message}", true, error)
            is AnthropicServiceException ->
                GenerationException("API : ${error.message}", false, error)
            else ->
                GenerationException(error.message ?: error.javaClass.simpleName, false, error)
        }
    }

    private fun effortValue(): OutputConfig.Effort = when (effort.lowercase()) {
        "low" -> OutputConfig.Effort.LOW
        "medium" -> OutputConfig.Effort.MEDIUM
        "xhigh" -> OutputConfig.Effort.XHIGH
        "max" -> OutputConfig.Effort.MAX
        else -> OutputConfig.Effort.HIGH
    }

    companion object {
        /**
         * La bible de 50 beats plus les tokens de reflexion depassaient les
         * 16 000 initiaux : la reponse etait tronquee, donc illisible, a chaque
         * tentative. Le SDK Java etend le timeout HTTP jusqu'a 10 minutes pour
         * un appel non streame, ce qui laisse la place.
         */
        const val MAX_TOKENS = 32_000L

        /** Voix edge-tts, alignees sur pipeline/src/schemas/concept.ts. */
        val VOICE_IDS = listOf(
            "fr-FR-DeniseNeural",
            "fr-FR-EloiseNeural",
            "fr-FR-HenriNeural",
            "fr-FR-RemyMultilingualNeural",
            "fr-FR-VivienneMultilingualNeural",
        )

        const val MODEL_OPUS = "claude-opus-5"
        const val MODEL_SONNET = "claude-sonnet-5"
        const val MODEL_HAIKU = "claude-haiku-4-5"

        /** Du plus capable au moins cher. */
        val MODELS = listOf(MODEL_OPUS, MODEL_SONNET, MODEL_HAIKU)

        /** Haiku 4.5 n'accepte pas `effort` : le reglage y est sans effet. */
        val MODELS_WITH_EFFORT = setOf(MODEL_OPUS, MODEL_SONNET)

        val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

        /**
         * Isole l'objet JSON, meme entoure d'un bloc de code ou d'un preambule.
         *
         * Les echecs sont marques reessayables : une reponse en prose ou un JSON
         * tronque se corrige tres souvent a la tentative suivante, une fois le
         * motif de rejet reinjecte dans le prompt.
         */
        fun parseJsonObject(raw: String): JSONObject {
            var text = raw.trim()
            val fenced = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$").find(text)
            if (fenced != null) text = fenced.groupValues[1].trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start == -1 || end <= start) {
                throw GenerationException(
                    "Aucun objet JSON dans la reponse : ${text.take(160)}",
                    retryable = true,
                )
            }
            return try {
                JSONObject(text.substring(start, end + 1))
            } catch (error: JSONException) {
                // Accolades presentes mais contenu casse (guillemet non ferme,
                // virgule finale) : org.json leve ici, et sans ce filet
                // l'exception echappait a la boucle de tentatives.
                throw GenerationException(
                    "JSON illisible : ${error.message}",
                    retryable = true,
                    cause = error,
                )
            }
        }
    }
}
