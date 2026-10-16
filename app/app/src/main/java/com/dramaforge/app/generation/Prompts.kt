package com.dramaforge.app.generation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chargement des prompts et du catalogue depuis les assets.
 *
 * Ces fichiers ne sont pas ecrits ici : ils sont copies depuis
 * pipeline/src/prompts/ et catalog/ au moment du build (cf. build.gradle.kts).
 * Modifier un prompt se fait donc dans le pipeline, et l'app suit.
 */
class Prompts(private val context: Context) {

    private val cache = mutableMapOf<String, String>()

    fun template(name: String): String = cache.getOrPut(name) {
        context.assets.open("prompts/$name.md").bufferedReader().use { it.readText() }
    }

    /**
     * Termes de marques interdits dans les titres generes, lus dans le
     * catalogue embarque : meme liste que celle du pipeline.
     */
    val excludedTerms: List<String> by lazy {
        val raw = context.assets.open("catalog/catalog.json").bufferedReader().use { it.readText() }
        val array = JSONObject(raw).optJSONArray("excludedTerms") ?: JSONArray()
        List(array.length()) { array.getString(it) }
    }

    fun render(name: String, vars: Map<String, Any>): String =
        renderTemplate(template(name), vars)

    companion object {
        private val TOKEN = Regex("""\{\{(\w+)}}""")

        /** Remplace les jetons {{NOM}}. Jette si un jeton reste non resolu. */
        fun renderTemplate(template: String, vars: Map<String, Any>): String {
            val rendered = TOKEN.replace(template) { match ->
                val key = match.groupValues[1]
                vars[key]?.toString() ?: match.value
            }
            val leftovers = TOKEN.findAll(rendered).map { it.groupValues[1] }.distinct().toList()
            require(leftovers.isEmpty()) {
                "variables de prompt non resolues : ${leftovers.joinToString(", ")}"
            }
            return rendered
        }
    }
}

/**
 * Serialisation des sorties du modele, pour les reinjecter dans le prompt
 * suivant et pour les persister. org.json est fourni par Android : pas de
 * dependance supplementaire.
 */
object DraftJson {

    fun of(concept: Concept): JSONObject = JSONObject().apply {
        put("id", concept.id)
        put("title", concept.title)
        put("logline", concept.logline)
        put("genres", JSONArray(concept.genres))
        put("tone", concept.tone)
        put("language", concept.language)
        put("characters", JSONArray(concept.characters.map { character ->
            JSONObject().apply {
                put("name", character.name)
                put("role", character.role)
                put("voiceId", character.voiceId)
                put("appearance", character.appearance)
            }
        }))
    }

    fun of(beat: Beat): JSONObject = JSONObject().apply {
        put("episode", beat.episode)
        put("summary", beat.summary)
        put("cliffhanger", beat.cliffhanger)
    }

    fun of(bible: Bible): JSONObject = JSONObject().apply {
        put("seriesTitle", bible.seriesTitle)
        put("beats", JSONArray(bible.beats.map { of(it) }))
    }

    fun of(script: EpisodeScript): JSONObject = JSONObject().apply {
        put("number", script.number)
        put("title", script.title)
        put("cliffhanger", script.cliffhanger)
        put("shots", JSONArray(script.shots.map { shot ->
            JSONObject().apply {
                put("index", shot.index)
                put("speaker", shot.speaker)
                put("line", shot.line)
                put("imagePrompt", shot.imagePrompt)
                put("durationSeconds", shot.durationSeconds)
            }
        }))
    }

    fun toConcept(json: JSONObject): Concept = Concept().apply {
        id = json.optString("id")
        title = json.optString("title")
        logline = json.optString("logline")
        genres = json.optJSONArray("genres").strings()
        tone = json.optString("tone")
        language = json.optString("language", "fr")
        characters = json.optJSONArray("characters").objects().map { item ->
            Character().apply {
                name = item.optString("name")
                role = item.optString("role")
                voiceId = item.optString("voiceId")
                appearance = item.optString("appearance")
            }
        }
    }

    fun toBible(json: JSONObject): Bible = Bible().apply {
        seriesTitle = json.optString("seriesTitle")
        beats = json.optJSONArray("beats").objects().map { item ->
            Beat().apply {
                episode = item.optInt("episode")
                summary = item.optString("summary")
                cliffhanger = item.optString("cliffhanger")
            }
        }
    }

    fun toScript(json: JSONObject): EpisodeScript = EpisodeScript().apply {
        number = json.optInt("number")
        title = json.optString("title")
        cliffhanger = json.optString("cliffhanger")
        shots = json.optJSONArray("shots").objects().map { item ->
            Shot().apply {
                index = item.optInt("index")
                speaker = item.optString("speaker")
                line = item.optString("line")
                imagePrompt = item.optString("imagePrompt")
                durationSeconds = item.optDouble("durationSeconds", 0.0)
            }
        }
    }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }
}
