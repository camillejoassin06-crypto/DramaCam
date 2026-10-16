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

export interface PipelineEnv {
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
  return {
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
