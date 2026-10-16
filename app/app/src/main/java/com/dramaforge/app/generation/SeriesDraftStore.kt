package com.dramaforge.app.generation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Une serie en cours ou terminee, telle qu'elle est persistee.
 *
 * Les episodes sont ecrits un par un : si le job est interrompu (limite de duree
 * d'execution, processus tue, reseau coupe), la reprise repart du dernier
 * episode ecrit au lieu de tout regenerer — et donc de tout refacturer.
 */
class SeriesDraft(
    val concept: Concept,
    val bible: Bible,
    val episodes: MutableList<EpisodeScript> = mutableListOf(),
    val issues: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
) {
    val id: String get() = concept.id
    val isComplete: Boolean get() = completedAt != null
    val nextEpisodeNumber: Int get() = episodes.size + 1
    val targetEpisodeCount: Int get() = bible.beats.size
}

class SeriesDraftStore(context: Context) {

    private val dir = File(context.filesDir, "drafts").apply { mkdirs() }

    private fun fileFor(id: String) = File(dir, "$id.json")

    fun save(draft: SeriesDraft) {
        val json = JSONObject().apply {
            put("createdAt", draft.createdAt)
            draft.completedAt?.let { put("completedAt", it) }
            put("concept", DraftJson.of(draft.concept))
            put("bible", DraftJson.of(draft.bible))
            put("episodes", JSONArray(draft.episodes.map { DraftJson.of(it) }))
            put("issues", JSONArray(draft.issues))
        }
        // Ecriture via un fichier temporaire : une interruption au milieu d'un
        // write laisserait sinon un JSON tronque, donc une serie illisible.
        val target = fileFor(draft.id)
        val temp = File(dir, "${draft.id}.json.tmp")
        temp.writeText(json.toString(2))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    fun load(id: String): SeriesDraft? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching { parse(JSONObject(file.readText())) }.getOrNull()
    }

    /** Series les plus recentes d'abord. */
    fun all(): List<SeriesDraft> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file -> runCatching { parse(JSONObject(file.readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    /** La serie inachevee a reprendre, s'il y en a une. */
    fun pending(): SeriesDraft? = all().firstOrNull { !it.isComplete }

    fun delete(id: String): Boolean = fileFor(id).delete()

    private fun parse(json: JSONObject): SeriesDraft {
        val episodes = json.optJSONArray("episodes")
        val issues = json.optJSONArray("issues")
        return SeriesDraft(
            concept = DraftJson.toConcept(json.getJSONObject("concept")),
            bible = DraftJson.toBible(json.getJSONObject("bible")),
            episodes = MutableList(episodes?.length() ?: 0) {
                DraftJson.toScript(episodes!!.getJSONObject(it))
            },
            issues = MutableList(issues?.length() ?: 0) { issues!!.optString(it) },
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            completedAt = if (json.has("completedAt")) json.getLong("completedAt") else null,
        )
    }
}
