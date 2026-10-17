package com.jedrama.fork.generation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Une serie en cours ou terminee, telle qu'elle est persistee.
 *
 * Chaque etape est ecrite des qu'elle est finie (script d'un episode, puis son
 * rendu video) : si le job est interrompu (batterie, processus tue, reseau),
 * la reprise repart de la ou elle s'est arretee au lieu de tout refaire â€” et
 * donc de tout refacturer.
 */
class SeriesDraft(
    val concept: Concept,
    val bible: Bible,
    val episodes: MutableList<EpisodeScript> = mutableListOf(),
    val issues: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    /** Episodes rendus en video : numero -> duree en secondes. */
    val rendered: MutableMap<Int, Int> = mutableMapOf(),
    var hasCover: Boolean = false,
) {
    val id: String get() = concept.id
    val isComplete: Boolean get() = completedAt != null
    val nextEpisodeNumber: Int get() = episodes.size + 1
    val targetEpisodeCount: Int get() = bible.beats.size

    /** Prochain episode a rendre (script deja ecrit ou non), null si tout est rendu. */
    val nextToRender: Int? get() = (1..targetEpisodeCount).firstOrNull { it !in rendered }

    fun script(number: Int): EpisodeScript? = episodes.firstOrNull { it.number == number }
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
            put("rendered", JSONObject().apply { draft.rendered.forEach { (n, d) -> put(n.toString(), d) } })
            put("hasCover", draft.hasCover)
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

    fun exists(id: String): Boolean = fileFor(id).exists()

    /** Supprime le script et toutes les videos de la serie. */
    fun delete(id: String): Boolean {
        File(libraryRoot, id).deleteRecursively()
        File(renderCache, id).deleteRecursively()
        return fileFor(id).delete()
    }

    private val libraryRoot = File(context.filesDir, "library")
    private val renderCache = File(context.cacheDir, "render")

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
            rendered = json.optJSONObject("rendered")?.let { map ->
                map.keys().asSequence().associate { it.toInt() to map.getInt(it) }.toMutableMap()
            } ?: mutableMapOf(),
            hasCover = json.optBoolean("hasCover", false),
        )
    }
}

