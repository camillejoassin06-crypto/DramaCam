import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));

export const REPO_ROOT = path.resolve(here, "..", "..");
export const CATALOG_PATH = path.join(REPO_ROOT, "catalog", "catalog.json");
export const CATALOG_SCHEMA_PATH = path.join(REPO_ROOT, "catalog", "schema.json");
/** Sorties de travail, ignorees par git (cf. .gitignore). */
export const OUT_DIR = path.join(REPO_ROOT, "pipeline", "out");
export const QUALITY_REPORT_PATH = path.join(OUT_DIR, "quality-report.json");
export const PROMPTS_DIR = path.join(here, "prompts");

/** 8 plans par episode (pas 12) : compromis cout GPU / rythme vertical. */
export const SHOTS_PER_EPISODE = 8;

/**
 * Fair-use Claude Code : 1 generation + 2 regenerations maximum par episode.
 * Ne pas augmenter sans accord explicite (cf. CLAUDE.md).
 */
export const MAX_ATTEMPTS_PER_EPISODE = 3;

/** Cible produit : 2:00 a 3:15. La QA bloque hors de ces bornes. */
export const EPISODE_MIN_SECONDS = 120;
export const EPISODE_MAX_SECONDS = 195;

/** Une serie complete = 50 episodes hebdomadaires. */
export const DEFAULT_EPISODE_COUNT = 50;

/** R2 free = 10 Go. On purge la serie la plus ancienne au-dela de 9 Go. */
export const PURGE_THRESHOLD_BYTES = 9 * 1024 ** 3;

/**
 * Vercel Blob (Hobby) = 1 Go, et tout depassement BLOQUE le stockage 30 jours
 * (aucune facture, mais plus aucune video lisible). Une seule serie y vit a la
 * fois, et on refuse d'envoyer une serie qui depasserait ce plafond.
 */
export const BLOB_MAX_SERIES_BYTES = 900 * 1024 ** 2;

/** Rendu vertical. */
export const VIDEO_WIDTH = 1080;
export const VIDEO_HEIGHT = 1920;

/**
 * STYLE_LOCK — IMMUABLE. Toute modification casse la coherence visuelle des
 * series deja publiees. Concatene mot pour mot au champ `appearance` de chaque
 * personnage dans chaque prompt d'image.
 */
export const STYLE_LOCK =
  "cinematic vertical still, soft volumetric light, shallow depth of field, " +
  "muted violet and gold palette, 35mm film grain, photorealistic, " +
  "no text, no watermark, no logo";

export type MediaStorage = "vercel-blob" | "r2";

export interface PipelineEnv {
  /** Ou vivent les videos : Vercel Blob (essai) ou Cloudflare R2. */
  mediaStorage: MediaStorage;
  /** Jeton du store Vercel Blob (GitHub Secrets, jamais dans le repo). */
  blobToken: string | undefined;
  r2AccountId: string | undefined;
  r2AccessKeyId: string | undefined;
  r2SecretAccessKey: string | undefined;
  r2Bucket: string;
  r2PublicBaseUrl: string;
  ttsProvider: string;
  imageProvider: string;
  comfyUiUrl: string;
}

export function readEnv(source: NodeJS.ProcessEnv = process.env): PipelineEnv {
  const storage = source.MEDIA_STORAGE || "r2";
  if (storage !== "vercel-blob" && storage !== "r2") {
    throw new Error(`MEDIA_STORAGE inconnu : ${storage} (attendu : vercel-blob ou r2)`);
  }
  return {
    mediaStorage: storage,
    blobToken: source.BLOB_READ_WRITE_TOKEN || undefined,
    r2AccountId: source.R2_ACCOUNT_ID,
    r2AccessKeyId: source.R2_ACCESS_KEY_ID,
    r2SecretAccessKey: source.R2_SECRET_ACCESS_KEY,
    r2Bucket: source.R2_BUCKET ?? "dramaforge-media",
    r2PublicBaseUrl: source.R2_PUBLIC_BASE_URL ?? "https://media.example-placeholder.dev",
    ttsProvider: source.TTS_PROVIDER ?? "edge-tts",
    imageProvider: source.IMAGE_PROVIDER ?? "placeholder",
    comfyUiUrl: source.COMFYUI_URL ?? "http://127.0.0.1:8188",
  };
}

/**
 * Base publique des medias, ecrite dans catalog.json AVANT l'envoi.
 * Vercel Blob : `https://<storeId>.public.blob.vercel-storage.com`, ou storeId
 * est le 4e segment du jeton `vercel_blob_rw_<storeId>_<secret>` (meme regle
 * que le SDK @vercel/blob ; publish.ts verifie l'URL rendue au premier envoi).
 */
export function mediaPublicBaseUrl(env: PipelineEnv): string {
  if (env.mediaStorage === "r2") return env.r2PublicBaseUrl.replace(/\/$/, "");
  const storeId = env.blobToken?.split("_")[3];
  if (!storeId) {
    throw new Error("BLOB_READ_WRITE_TOKEN absent ou invalide (GitHub Secrets) pour MEDIA_STORAGE=vercel-blob");
  }
  return `https://${storeId.toLowerCase()}.public.blob.vercel-storage.com`;
}
