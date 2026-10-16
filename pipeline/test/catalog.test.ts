import assert from "node:assert/strict";
import test from "node:test";
import { PURGE_THRESHOLD_BYTES } from "../src/config.ts";
import {
  assertOriginalTitle,
  contentTypeFor,
  readCatalog,
  seriesToPurge,
  upsertSeries,
} from "../src/publish.ts";
import { findExcludedTerms, type Catalog, type SeriesEntry } from "../src/schemas/catalog.ts";
import { buildMaster, VARIANT_480P, VARIANT_720P } from "../src/hls.ts";
import { buildImagePrompt } from "../src/images/index.ts";
import { STYLE_LOCK } from "../src/config.ts";
import { DRY_RUN_CONCEPT } from "../src/fixtures.ts";
import { parseArgs } from "../src/generate-weekly.ts";

const GIGABYTE = 1024 ** 3;

function series(id: string, publishedAt: string, totalBytes: number): SeriesEntry {
  return {
    id,
    title: id,
    logline: "Une ligne.",
    genres: ["Mystere"],
    language: "fr",
    coverUrl: "https://media.example-placeholder.dev/series/x/cover.jpg",
    publishedAt,
    totalBytes,
    episodes: [
      {
        number: 1,
        title: "Episode un",
        durationSeconds: 160,
        hlsUrl: "https://media.example-placeholder.dev/series/x/ep-001/master.m3u8",
        sizeBytes: totalBytes,
      },
    ],
  };
}

function catalogWith(entries: SeriesEntry[], excludedTerms: string[] = []): Catalog {
  return {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    excludedTerms,
    series: entries,
  };
}

test("le catalogue du depot est valide", async () => {
  const catalog = await readCatalog();
  assert.equal(catalog.schemaVersion, 1);
  assert.ok(catalog.series.length >= 1);
});

test("le catalogue du depot ne contient aucun terme exclu", async () => {
  const catalog = await readCatalog();
  for (const entry of catalog.series) {
    assert.deepEqual(findExcludedTerms(entry.title, catalog.excludedTerms), []);
    assert.deepEqual(findExcludedTerms(entry.logline, catalog.excludedTerms), []);
  }
});

test("la detection de termes exclus ignore la casse", () => {
  assert.deepEqual(findExcludedTerms("Le DramaWave de minuit", ["dramawave"]), ["dramawave"]);
});

test("un titre reprenant une marque listee est refuse", () => {
  const catalog = catalogWith([], ["dramawave"]);
  assert.throws(() => assertOriginalTitle(catalog, "DramaWave Nuit"), /termes exclus/);
  assert.doesNotThrow(() => assertOriginalTitle(catalog, "Le Train de Minuit"));
});

test("upsertSeries remplace une serie existante et trie du plus recent au plus ancien", () => {
  const catalog = catalogWith([
    series("ancienne", "2026-01-01T00:00:00.000Z", 1),
    series("recente", "2026-06-01T00:00:00.000Z", 1),
  ]);
  const updated = upsertSeries(catalog, series("ancienne", "2026-12-01T00:00:00.000Z", 2));
  assert.equal(updated.series.length, 2);
  assert.deepEqual(
    updated.series.map((entry) => entry.id),
    ["ancienne", "recente"],
  );
  assert.equal(updated.series[0]?.totalBytes, 2);
});

test("aucune purge sous le seuil de 9 Go", () => {
  const catalog = catalogWith([series("a", "2026-01-01T00:00:00.000Z", GIGABYTE)]);
  assert.deepEqual(seriesToPurge(catalog, GIGABYTE, "b"), []);
});

test("la purge retire les series les plus anciennes jusqu'a repasser sous le seuil", () => {
  const catalog = catalogWith([
    series("a", "2026-01-01T00:00:00.000Z", 4 * GIGABYTE),
    series("b", "2026-02-01T00:00:00.000Z", 4 * GIGABYTE),
    series("c", "2026-03-01T00:00:00.000Z", 4 * GIGABYTE),
  ]);
  const purge = seriesToPurge(catalog, 0, "c");
  assert.deepEqual(
    purge.map((entry) => entry.id),
    ["a"],
  );
  const remaining =
    catalog.series
      .filter((entry) => !purge.some((removed) => removed.id === entry.id))
      .reduce((sum, entry) => sum + entry.totalBytes, 0);
  assert.ok(remaining <= PURGE_THRESHOLD_BYTES);
});

test("la serie qu'on vient de publier n'est jamais purgee", () => {
  const catalog = catalogWith([
    series("neuve", "2026-01-01T00:00:00.000Z", 12 * GIGABYTE),
  ]);
  assert.deepEqual(seriesToPurge(catalog, 0, "neuve"), []);
});

test("les types MIME HLS sont corrects", () => {
  assert.equal(contentTypeFor("master.m3u8"), "application/vnd.apple.mpegurl");
  assert.equal(contentTypeFor("480p_001.ts"), "video/mp2t");
  assert.equal(contentTypeFor("fr.vtt"), "text/vtt");
  assert.equal(contentTypeFor("cover.jpg"), "image/jpeg");
  assert.equal(contentTypeFor("inconnu.bin"), "application/octet-stream");
});

test("la playlist maitre liste chaque variante avec sa resolution", () => {
  const master = buildMaster([VARIANT_480P, VARIANT_720P]);
  assert.match(master, /^#EXTM3U/);
  assert.match(master, /RESOLUTION=480x854/);
  assert.match(master, /RESOLUTION=720x1280/);
  assert.match(master, /^480p\.m3u8$/m);
});

test("le prompt d'image reinjecte l'appearance mot pour mot et finit par le STYLE_LOCK", () => {
  const alma = DRY_RUN_CONCEPT.characters[0]!;
  const prompt = buildImagePrompt(`${alma.name} on an empty platform`, DRY_RUN_CONCEPT.characters);
  assert.ok(prompt.includes(alma.appearance));
  assert.ok(prompt.endsWith(STYLE_LOCK));
});

test("un personnage absent du plan n'alourdit pas le prompt", () => {
  const victor = DRY_RUN_CONCEPT.characters[1]!;
  const prompt = buildImagePrompt("Alma on an empty platform", DRY_RUN_CONCEPT.characters);
  assert.ok(!prompt.includes(victor.appearance));
});

test("parseArgs lit --dry-run et --episodes", () => {
  assert.deepEqual(parseArgs(["--dry-run", "--episodes", "3"]), { dryRun: true, episodes: 3 });
  assert.deepEqual(parseArgs([]), { dryRun: false, episodes: 50 });
});

test("parseArgs refuse un nombre d'episodes hors bornes", () => {
  assert.throws(() => parseArgs(["--episodes", "0"]), /entre 1 et 50/);
  assert.throws(() => parseArgs(["--episodes", "51"]), /entre 1 et 50/);
  assert.throws(() => parseArgs(["--episodes", "trois"]), /entre 1 et 50/);
});
