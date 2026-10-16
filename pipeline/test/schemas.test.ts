import assert from "node:assert/strict";
import test from "node:test";
import { SHOTS_PER_EPISODE } from "../src/config.ts";
import { bibleSchema } from "../src/schemas/bible.ts";
import { catalogSchema, episodeEntrySchema } from "../src/schemas/catalog.ts";
import { conceptSchema } from "../src/schemas/concept.ts";
import { checkSpeakers, episodeScriptSchema, NARRATOR } from "../src/schemas/episode.ts";
import { DRY_RUN_CONCEPT, dryRunBible, dryRunScript } from "../src/fixtures.ts";

const validEpisode = {
  number: 1,
  title: "Episode un",
  durationSeconds: 160,
  hlsUrl: "https://media.example-placeholder.dev/series/x/ep-001/master.m3u8",
  sizeBytes: 0,
};

test("une entree d'episode valide passe", () => {
  assert.equal(episodeEntrySchema.parse(validEpisode).number, 1);
});

test("PERSONAL_MODE : un champ de prix fait echouer le catalogue", () => {
  const result = episodeEntrySchema.safeParse({ ...validEpisode, priceCoins: 30 });
  assert.equal(result.success, false);
});

test("PERSONAL_MODE : un champ de verrou fait echouer le catalogue", () => {
  const result = episodeEntrySchema.safeParse({ ...validEpisode, isFree: false });
  assert.equal(result.success, false);
});

test("les numeros d'episodes doivent etre contigus a partir de 1", () => {
  const catalog = {
    schemaVersion: 1 as const,
    generatedAt: new Date().toISOString(),
    excludedTerms: [],
    series: [
      {
        id: "serie-test",
        title: "Serie test",
        logline: "Une ligne.",
        genres: ["Mystere"],
        language: "fr" as const,
        coverUrl: "https://media.example-placeholder.dev/series/x/cover.jpg",
        publishedAt: new Date().toISOString(),
        totalBytes: 0,
        episodes: [validEpisode, { ...validEpisode, number: 3 }],
      },
    ],
  };
  const result = catalogSchema.safeParse(catalog);
  assert.equal(result.success, false);
  assert.match(JSON.stringify(result.error?.issues), /non contigus/);
});

test("deux series ne peuvent pas partager un identifiant", () => {
  const series = {
    id: "serie-test",
    title: "Serie test",
    logline: "Une ligne.",
    genres: ["Mystere"],
    language: "fr" as const,
    coverUrl: "https://media.example-placeholder.dev/series/x/cover.jpg",
    publishedAt: new Date().toISOString(),
    totalBytes: 0,
    episodes: [validEpisode],
  };
  const result = catalogSchema.safeParse({
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    excludedTerms: [],
    series: [series, series],
  });
  assert.equal(result.success, false);
  assert.match(JSON.stringify(result.error?.issues), /doublon/);
});

test("le concept de dry-run est valide", () => {
  assert.doesNotThrow(() => conceptSchema.parse(DRY_RUN_CONCEPT));
});

test("deux personnages ne peuvent pas partager une voix", () => {
  const first = DRY_RUN_CONCEPT.characters[0]!;
  const second = DRY_RUN_CONCEPT.characters[1]!;
  const result = conceptSchema.safeParse({
    ...DRY_RUN_CONCEPT,
    characters: [first, { ...second, voiceId: first.voiceId }],
  });
  assert.equal(result.success, false);
  assert.match(JSON.stringify(result.error?.issues), /voix/);
});

test("la bible de dry-run respecte la numerotation", () => {
  const bible = bibleSchema.parse(dryRunBible(5));
  assert.equal(bible.beats.length, 5);
  assert.deepEqual(
    bible.beats.map((beat) => beat.episode),
    [1, 2, 3, 4, 5],
  );
});

test("un beat hors sequence est refuse", () => {
  const bible = dryRunBible(3);
  bible.beats[1]!.episode = 7;
  assert.equal(bibleSchema.safeParse(bible).success, false);
});

test("le script de dry-run tient la cible de duree et le nombre de plans", () => {
  const script = episodeScriptSchema.parse(dryRunScript(1));
  assert.equal(script.shots.length, SHOTS_PER_EPISODE);
});

test("un episode avec un plan de moins est refuse", () => {
  const script = dryRunScript(1);
  const result = episodeScriptSchema.safeParse({ ...script, shots: script.shots.slice(1) });
  assert.equal(result.success, false);
});

test("une duree totale hors cible est refusee", () => {
  const script = dryRunScript(1);
  const shots = script.shots.map((shot) => ({ ...shot, durationSeconds: 7 }));
  const result = episodeScriptSchema.safeParse({ ...script, shots });
  assert.equal(result.success, false);
  assert.match(JSON.stringify(result.error?.issues), /hors cible/);
});

test("checkSpeakers accepte le narrateur et les personnages du concept", () => {
  assert.deepEqual(checkSpeakers(dryRunScript(1), DRY_RUN_CONCEPT), []);
});

test("checkSpeakers signale un locuteur inconnu, une seule fois", () => {
  const script = dryRunScript(1);
  script.shots[0]!.speaker = "Inconnu";
  script.shots[1]!.speaker = "Inconnu";
  assert.deepEqual(checkSpeakers(script, DRY_RUN_CONCEPT), ["Inconnu"]);
});

test("le narrateur est toujours un locuteur valide", () => {
  const script = dryRunScript(1);
  for (const shot of script.shots) shot.speaker = NARRATOR;
  assert.deepEqual(checkSpeakers(script, DRY_RUN_CONCEPT), []);
});
