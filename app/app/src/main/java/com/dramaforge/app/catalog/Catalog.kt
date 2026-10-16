package com.dramaforge.app.catalog

/**
 * Miroir Kotlin de catalog/catalog.json — l'unique contrat app <-> contenu.
 *
 * PERSONAL_MODE : aucun champ de prix, de verrou ou d'etat de deverrouillage.
 * Le format lui-meme les interdit, donc l'app n'a rien a decider.
 *
 * Toute evolution du schema se fait dans le meme commit que catalog/schema.json
 * et pipeline/src/schemas/catalog.ts (cf. CLAUDE.md).
 */
data class Catalog(
    val schemaVersion: Int,
    val generatedAt: String,
    val series: List<Series>,
)

data class Series(
    val id: String,
    val title: String,
    val logline: String,
    val genres: List<String>,
    val language: String,
    val badges: List<Badge>,
    val coverUrl: String,
    /** ISO-8601. Le plus recent en premier sur l'ecran Home. */
    val publishedAt: String,
    val episodes: List<Episode>,
)

data class Episode(
    val number: Int,
    val title: String,
    val durationSeconds: Int,
    val hlsUrl: String,
    val subtitlesUrl: String?,
)

enum class Badge { NEW, EXCLUSIVE }

/**
 * Donnees de developpement, calquees sur la serie d'exemple de
 * catalog/catalog.json. A remplacer par StaticCatalogRepository (telechargement
 * de catalog.json + cache Room) : c'est l'objet du brief suivant.
 */
object SampleCatalog {
    private const val BASE =
        "https://media.example-placeholder.dev/series/le-train-de-minuit"

    val series: List<Series> = listOf(
        Series(
            id = "le-train-de-minuit",
            title = "Le Train de Minuit",
            logline =
                "Chaque nuit je prends le dernier train, et chaque nuit un passager " +
                    "de plus connait mon nom.",
            genres = listOf("Mystere", "Suspense"),
            language = "fr",
            badges = listOf(Badge.NEW),
            coverUrl = "$BASE/cover.png",
            publishedAt = "2026-10-13T03:00:00.000Z",
            episodes = listOf(
                episode(1, "Le wagon qui ne figure sur aucun plan", 150),
                episode(2, "Celle qui connait mon nom", 162),
                episode(3, "Le billet date de demain", 174),
            ),
        ),
    )

    private fun episode(number: Int, title: String, durationSeconds: Int): Episode {
        val folder = "ep-%03d".format(number)
        return Episode(
            number = number,
            title = title,
            durationSeconds = durationSeconds,
            hlsUrl = "$BASE/$folder/master.m3u8",
            subtitlesUrl = "$BASE/$folder/fr.vtt",
        )
    }
}
