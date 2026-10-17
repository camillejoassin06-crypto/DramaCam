package com.jedrama.fork.generation

import org.json.JSONException
import org.json.JSONObject

/**
 * Ecriture d'une serie (concept, bible, episodes, revue) avec le modele de texte
 * choisi dans les Parametres.
 *
 * Nombre d'appels : 2 par serie + 1 par episode (+1 avec la revue de surete).
 *
 * Les prompts demandent un objet JSON ; la reponse est parsee puis validee, et
 * une sortie invalide consomme une tentative (1 generation + 2 regenerations),
 * le motif de rejet etant reinjecte dans la tentative suivante.
 */
class ScriptGenerator(
    private val model: TextModel,
    private val prompts: Prompts,
) {

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

    /** Revue d'originalite et de surete, avant le rendu. */
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
                // Une reponse illisible consomme une tentative ; une cle refusee
                // ou un quota epuise remonte tout de suite.
                if (!error.outputProblem) throw error
                lastReason = error.message.orEmpty()
            }
        }
        throw GenerationException(
            "$label : ${GenerationLimits.MAX_ATTEMPTS} tentatives epuisees. Dernier motif : $lastReason",
            retryable = false,
        )
    }

    private fun ask(prompt: String): JSONObject = parseJsonObject(model.complete(prompt))

    companion object {
        /**
         * Voix abstraites : le moteur de synthese Android les traduit en hauteur
         * et debit (cf. media/Speech.kt). Le nom indique au modele quel timbre
         * convient a quel personnage.
         */
        val VOICE_IDS = listOf(
            "femme-claire",
            "femme-grave",
            "homme-clair",
            "homme-grave",
            "voix-jeune",
            "voix-agee",
        )

        /** Isole l'objet JSON, meme entoure d'un bloc de code ou d'un preambule. */
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
                    outputProblem = true,
                )
            }
            return try {
                JSONObject(text.substring(start, end + 1))
            } catch (error: JSONException) {
                throw GenerationException(
                    "JSON illisible : ${error.message}",
                    retryable = true,
                    cause = error,
                    outputProblem = true,
                )
            }
        }
    }
}

