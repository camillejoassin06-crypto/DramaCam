import assert from "node:assert/strict";
import test from "node:test";
import { BLOB_MAX_SERIES_BYTES, mediaPublicBaseUrl, readEnv } from "../src/config.ts";
import { assertFitsBlob, publishSeries } from "../src/publish.ts";
import type { SeriesEntry } from "../src/schemas/catalog.ts";

const FAKE_TOKEN = "vercel_blob_rw_AbC123xyz_secretpart";

test("R2 reste le stockage par defaut, et une valeur inconnue est refusee", () => {
  assert.equal(readEnv({}).mediaStorage, "r2");
  assert.equal(readEnv({ MEDIA_STORAGE: "vercel-blob" }).mediaStorage, "vercel-blob");
  assert.throws(() => readEnv({ MEDIA_STORAGE: "s3" }), /MEDIA_STORAGE inconnu/);
});

test("la base Vercel Blob se deduit du jeton, comme dans le SDK", () => {
  const env = readEnv({ MEDIA_STORAGE: "vercel-blob", BLOB_READ_WRITE_TOKEN: FAKE_TOKEN });
  assert.equal(mediaPublicBaseUrl(env), "https://abc123xyz.public.blob.vercel-storage.com");
});

test("sans jeton, Vercel Blob echoue explicitement", () => {
  const env = readEnv({ MEDIA_STORAGE: "vercel-blob" });
  assert.throws(() => mediaPublicBaseUrl(env), /BLOB_READ_WRITE_TOKEN/);
});

test("la base R2 perd sa barre finale", () => {
  const env = readEnv({ R2_PUBLIC_BASE_URL: "https://pub-x.r2.dev/" });
  assert.equal(mediaPublicBaseUrl(env), "https://pub-x.r2.dev");
});

test("une serie trop lourde pour Vercel Blob est refusee avant tout envoi", () => {
  assert.doesNotThrow(() => assertFitsBlob(BLOB_MAX_SERIES_BYTES));
  assert.throws(() => assertFitsBlob(BLOB_MAX_SERIES_BYTES + 1), /envoi annule/);
});

test("en dry-run Vercel Blob, le catalogue ne garde que la serie publiee", async () => {
  const series: SeriesEntry = {
    id: "serie-test",
    title: "Serie de test",
    logline: "Une logline suffisamment longue pour passer la validation du schema.",
    genres: ["Mystere"],
    language: "fr",
    badges: ["NOUVEAU"],
    coverUrl: "https://abc123xyz.public.blob.vercel-storage.com/series/serie-test/cover.png",
    publishedAt: new Date().toISOString(),
    totalBytes: 1024,
    episodes: [],
  };
  const env = readEnv({ MEDIA_STORAGE: "vercel-blob", BLOB_READ_WRITE_TOKEN: FAKE_TOKEN });
  const catalog = await publishSeries({ series, mediaDir: ".", env, dryRun: true, log: () => {} });
  assert.deepEqual(catalog.series.map((entry) => entry.id), ["serie-test"]);
});
