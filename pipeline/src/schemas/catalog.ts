import { z } from "zod";

/**
 * Miroir zod de catalog/schema.json. Les deux fichiers changent dans le meme
 * commit que StaticCatalogRepository (cf. CLAUDE.md).
 *
 * PERSONAL_MODE : aucun champ de prix, de verrou ou de monetisation. `.strict()`
 * garantit qu'un tel champ fasse echouer la validation au lieu d'etre ignore.
 */

export const slug = z
  .string()
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, "identifiant kebab-case attendu");

export const episodeEntrySchema = z
  .object({
    number: z.number().int().positive(),
    title: z.string().min(1).max(120),
    /** Cible produit 120-195 s ; les bornes larges laissent passer un ecart a signaler en QA. */
    durationSeconds: z.number().int().min(60).max(300),
    hlsUrl: z.string().url(),
    subtitlesUrl: z.string().url().optional(),
    thumbnailUrl: z.string().url().optional(),
    sizeBytes: z.number().int().nonnegative(),
  })
  .strict();

export const seriesEntrySchema = z
  .object({
    id: slug,
    title: z.string().min(1).max(80),
    logline: z.string().min(1).max(300),
    genres: z.array(z.string().min(1)).min(1).max(4),
    language: z.enum(["fr", "en"]),
    badges: z.array(z.enum(["NOUVEAU", "EXCLUSIVE"])).optional(),
    coverUrl: z.string().url(),
    publishedAt: z.string().datetime(),
    /** Poids total sur R2 : sert a la purge au-dela de PURGE_THRESHOLD_BYTES. */
    totalBytes: z.number().int().nonnegative(),
    episodes: z.array(episodeEntrySchema).min(1),
  })
  .strict()
  .superRefine((series, ctx) => {
    const expected = series.episodes.map((_, index) => index + 1);
    const actual = series.episodes.map((episode) => episode.number);
    if (actual.join(",") !== expected.join(",")) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["episodes"],
        message: `numeros d'episodes non contigus a partir de 1 (recu : ${actual.join(",")})`,
      });
    }
  });

export const catalogSchema = z
  .object({
    schemaVersion: z.literal(1),
    generatedAt: z.string().datetime(),
    /** Termes de marques existantes interdits dans les titres generes. */
    excludedTerms: z.array(z.string().min(2)),
    series: z.array(seriesEntrySchema),
  })
  .strict()
  .superRefine((catalog, ctx) => {
    const seen = new Set<string>();
    for (const [index, series] of catalog.series.entries()) {
      if (seen.has(series.id)) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["series", index, "id"],
          message: `identifiant de serie en doublon : ${series.id}`,
        });
      }
      seen.add(series.id);
    }
  });

export type EpisodeEntry = z.infer<typeof episodeEntrySchema>;
export type SeriesEntry = z.infer<typeof seriesEntrySchema>;
export type Catalog = z.infer<typeof catalogSchema>;

/** Retourne les termes exclus presents dans un titre, en comparaison insensible a la casse. */
export function findExcludedTerms(title: string, excludedTerms: readonly string[]): string[] {
  const haystack = title.toLowerCase();
  return excludedTerms.filter((term) => haystack.includes(term.toLowerCase()));
}
