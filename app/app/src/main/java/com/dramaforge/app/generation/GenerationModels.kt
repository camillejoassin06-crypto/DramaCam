package com.dramaforge.app.generation

/**
 * Formes attendues des reponses du modele.
 *
 * Ces classes servent de schema aux sorties structurees du SDK Anthropic : le
 * schema JSON est derive par reflexion, donc chaque propriete est `var` et
 * possede une valeur par defaut (sinon Jackson ne peut pas instancier la
 * classe) et les noms de champs doivent survivre a R8 (cf. proguard-rules.pro).
 *
 * Les contraintes semantiques ne sont PAS exprimables dans le schema : elles
 * sont verifiees par [validate], en miroir des schemas zod de
 * pipeline/src/schemas/.
 */

class Character {
    var name: String = ""
    var role: String = ""
    var voiceId: String = ""

    /** Description physique figee, reinjectee mot pour mot dans les prompts d'image. */
    var appearance: String = ""
}

class Concept {
    var id: String = ""
    var title: String = ""
    var logline: String = ""
    var genres: List<String> = emptyList()
    var tone: String = ""
    var language: String = "fr"
    var characters: List<Character> = emptyList()
}

class Beat {
    var episode: Int = 0
    var summary: String = ""
    var cliffhanger: String = ""
}

class Bible {
    var seriesTitle: String = ""
    var beats: List<Beat> = emptyList()
}

class Shot {
    var index: Int = 0
    var speaker: String = ""
    var line: String = ""
    var imagePrompt: String = ""
    var durationSeconds: Double = 0.0
}

class EpisodeScript {
    var number: Int = 0
    var title: String = ""
    var shots: List<Shot> = emptyList()
    var cliffhanger: String = ""
}

class QualityVerdict {
    var originalContent: Boolean = false
    var characterConsistency: Boolean = false
    var safeForTargetRating: Boolean = false
    var concerns: List<String> = emptyList()
}

/** Constantes alignees sur pipeline/src/config.ts. */
object GenerationLimits {
    const val SHOTS_PER_EPISODE = 8
    const val EPISODE_MIN_SECONDS = 120
    const val EPISODE_MAX_SECONDS = 195
    const val EPISODES_PER_SERIES = 50
    const val NARRATOR = "NARRATEUR"

    /** 1 generation + 2 regenerations, comme le pipeline. */
    const val MAX_ATTEMPTS = 3
}

/** Resultat d'une validation : la liste des problemes, vide si tout va bien. */
@JvmInline
value class Problems(val messages: List<String>) {
    val isValid: Boolean get() = messages.isEmpty()
    override fun toString(): String = messages.joinToString(" ; ")
}

fun Concept.validate(): Problems {
    val problems = mutableListOf<String>()
    if (!id.matches(Regex("^[a-z0-9]+(-[a-z0-9]+)*$"))) {
        problems += "identifiant non kebab-case : \"$id\""
    }
    if (title.isBlank() || title.length > 80) problems += "titre absent ou trop long"
    if (logline.isBlank() || logline.length > 300) problems += "logline absent ou trop long"
    if (genres.isEmpty() || genres.size > 4) problems += "il faut 1 a 4 genres"
    if (characters.size < 2 || characters.size > 6) {
        problems += "il faut 2 a 6 personnages, recu ${characters.size}"
    }
    val names = characters.map { it.name.lowercase() }
    if (names.size != names.toSet().size) problems += "noms de personnages en doublon"
    val voices = characters.map { it.voiceId }.filter { it.isNotBlank() }
    if (voices.size != voices.toSet().size) problems += "deux personnages partagent la meme voix"
    characters.filter { it.appearance.length < 20 }.forEach {
        problems += "appearance trop courte pour ${it.name}"
    }
    return Problems(problems)
}

fun Bible.validate(expectedEpisodes: Int): Problems {
    val problems = mutableListOf<String>()
    if (beats.size != expectedEpisodes) {
        problems += "${beats.size} beats pour $expectedEpisodes episodes"
    }
    val expected = beats.indices.map { it + 1 }
    if (beats.map { it.episode } != expected) {
        problems += "beats non contigus a partir de 1"
    }
    beats.filter { it.summary.isBlank() || it.cliffhanger.isBlank() }.forEach {
        problems += "beat ${it.episode} incomplet"
    }
    return Problems(problems)
}

fun EpisodeScript.validate(concept: Concept, expectedNumber: Int): Problems {
    val problems = mutableListOf<String>()
    if (number != expectedNumber) problems += "numero $number attendu $expectedNumber"
    if (title.isBlank()) problems += "titre absent"
    if (shots.size != GenerationLimits.SHOTS_PER_EPISODE) {
        problems += "${shots.size} plans au lieu de ${GenerationLimits.SHOTS_PER_EPISODE}"
    }
    val expected = shots.indices.map { it + 1 }
    if (shots.map { it.index } != expected) problems += "plans non contigus"

    val total = shots.sumOf { it.durationSeconds }
    if (total < GenerationLimits.EPISODE_MIN_SECONDS || total > GenerationLimits.EPISODE_MAX_SECONDS) {
        problems += "duree ${total.toInt()} s hors cible " +
            "${GenerationLimits.EPISODE_MIN_SECONDS}-${GenerationLimits.EPISODE_MAX_SECONDS} s"
    }

    val known = buildSet {
        add(GenerationLimits.NARRATOR)
        concept.characters.forEach { add(it.name) }
    }
    val unknown = shots.map { it.speaker }.filter { it !in known }.distinct()
    if (unknown.isNotEmpty()) problems += "locuteurs inconnus : ${unknown.joinToString(", ")}"

    shots.filter { it.line.isBlank() }.forEach { problems += "plan ${it.index} sans replique" }
    return Problems(problems)
}

/** Motifs bloquants d'un verdict de sureté. Vide si l'episode est publiable. */
fun QualityVerdict.blockers(): List<String> = buildList {
    if (!originalContent) add("originalite")
    if (!characterConsistency) add("coherence des personnages")
    if (!safeForTargetRating) add("surete du contenu")
}
