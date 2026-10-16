#!/usr/bin/env tsx
import fs from "node:fs/promises";
import { CATALOG_PATH, CATALOG_SCHEMA_PATH, EPISODE_MAX_SECONDS, EPISODE_MIN_SECONDS } from "./config.ts";
import { formatBytes } from "./fsutil.ts";
import { catalogSchema, findExcludedTerms } from "./schemas/catalog.ts";
import { z } from "zod";

/**
 * Verification du contrat app<->contenu. Rapide (~1 s) : a lancer avant tout
 * commit touchant catalog/.
 *
 * Usage : npm run catalog:validate [-- chemin/vers/catalog.json]
 */

async function main(): Promise<void> {
  const target = process.argv[2] ?? CATALOG_PATH;
  const problems: string[] = [];
  const warnings: string[] = [];

  // Le schema JSON doit rester lisible : c'est le contrat publie.
  try {
    JSON.parse(await fs.readFile(CATALOG_SCHEMA_PATH, "utf8"));
  } catch (error) {
    problems.push(`catalog/schema.json illisible : ${(error as Error).message}`);
  }

  const raw = await fs.readFile(target, "utf8");
  const parsed = catalogSchema.safeParse(JSON.parse(raw));

  if (!parsed.success) {
    for (const issue of parsed.error.issues) {
      problems.push(`${issue.path.join(".") || "racine"} : ${issue.message}`);
    }
  } else {
    const catalog = parsed.data;
    let episodeCount = 0;

    for (const series of catalog.series) {
      const hits = findExcludedTerms(`${series.title} ${series.logline}`, catalog.excludedTerms);
      if (hits.length > 0) {
        problems.push(`${series.id} : termes exclus presents (${hits.join(", ")})`);
      }

      episodeCount += series.episodes.length;

      for (const episode of series.episodes) {
        if (
          episode.durationSeconds < EPISODE_MIN_SECONDS ||
          episode.durationSeconds > EPISODE_MAX_SECONDS
        ) {
          warnings.push(
            `${series.id} ep.${episode.number} : ${episode.durationSeconds} s hors cible ` +
              `${EPISODE_MIN_SECONDS}-${EPISODE_MAX_SECONDS} s`,
          );
        }
        if (!episode.subtitlesUrl) {
          warnings.push(`${series.id} ep.${episode.number} : aucun sous-titre .vtt`);
        }
      }
    }

    const totalBytes = catalog.series.reduce((sum, series) => sum + series.totalBytes, 0);
    console.log(
      `${target}\n  ${catalog.series.length} serie(s), ${episodeCount} episode(s), ${formatBytes(totalBytes)} sur R2`,
    );
  }

  for (const warning of warnings) console.warn(`  avertissement : ${warning}`);

  if (problems.length > 0) {
    console.error(`\n${problems.length} erreur(s) :`);
    for (const problem of problems) console.error(`  - ${problem}`);
    process.exitCode = 1;
    return;
  }

  console.log(`  catalogue valide${warnings.length > 0 ? ` (${warnings.length} avertissement(s))` : ""}`);
}

main().catch((error: unknown) => {
  console.error(error instanceof z.ZodError ? error.issues : (error as Error).message);
  process.exitCode = 1;
});
