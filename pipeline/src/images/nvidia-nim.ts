import fs from "node:fs/promises";
import path from "node:path";
import { hasFfmpeg } from "../ffmpeg.ts";
import { run } from "../proc.ts";
import type { ImageProvider, ImageRequest, ImageResult } from "./index.ts";

/**
 * POINT D'INTEGRATION — API hebergee NVIDIA NIM (build.nvidia.com).
 *
 * ATTENTION COUT : n'utiliser que l'acces GRATUIT du programme developpeur
 * NVIDIA (cle `nvapi-...` personnelle, sans moyen de paiement associe).
 * Option A interdit tout service facture a l'usage (cf. CLAUDE.md). Si l'acces
 * gratuit disparait, retirer ce fournisseur plutot que de passer a NVIDIA AI
 * Enterprise.
 *
 * Modele par defaut : FLUX.1-schnell (licence Apache 2.0, 4 pas). La cle vit
 * dans GitHub Secrets (`NVIDIA_API_KEY`), jamais dans le repo ni l'APK.
 */

export const NIM_BASE_URL = "https://ai.api.nvidia.com/v1/genai";
export const NIM_DEFAULT_MODEL = "black-forest-labs/flux.1-schnell";

/** FLUX sur NIM : cotes multiples de 64, entre 768 et 1344. */
const NIM_MIN_SIDE = 768;
const NIM_MAX_SIDE = 1344;
const NIM_STEP = 64;

/** 1 essai + 2 reprises sur 429 / 5xx (quota gratuit limite en debit). */
const MAX_HTTP_ATTEMPTS = 3;

type Fetch = typeof fetch;
type Sleep = (ms: number) => Promise<void>;

/**
 * Ramene la cible (1080x1920) a la cote la plus proche acceptee par NIM, en
 * conservant le ratio. Le rendu FFmpeg remet ensuite a l'echelle et recadre.
 */
export function nimDimensions(width: number, height: number): { width: number; height: number } {
  const snap = (value: number): number =>
    Math.min(NIM_MAX_SIDE, Math.max(NIM_MIN_SIDE, Math.round(value / NIM_STEP) * NIM_STEP));
  if (height >= width) {
    return { width: snap((NIM_MAX_SIDE * width) / height), height: NIM_MAX_SIDE };
  }
  return { width: NIM_MAX_SIDE, height: snap((NIM_MAX_SIDE * height) / width) };
}

/**
 * Extrait l'image de la reponse. Les modeles NIM n'ont pas tous le meme format :
 * FLUX renvoie `artifacts[].base64`, Stable Diffusion 3 renvoie `image`.
 */
export function extractNimImage(payload: unknown): Buffer {
  const body = payload as {
    artifacts?: { base64?: string; finishReason?: string }[];
    image?: string;
  };
  const artifact = body.artifacts?.[0];
  if (artifact?.finishReason && artifact.finishReason !== "SUCCESS") {
    throw new Error(`NVIDIA NIM : image refusee (${artifact.finishReason})`);
  }
  const encoded = artifact?.base64 ?? body.image;
  if (!encoded) throw new Error("NVIDIA NIM : reponse sans image");
  return Buffer.from(encoded.replace(/^data:image\/\w+;base64,/, ""), "base64");
}

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
export const isPng = (bytes: Buffer): boolean => bytes.subarray(0, 4).equals(PNG_SIGNATURE);

/** Le modele refuse le champ `seed` (cas de FLUX.1-schnell, verifie le 2026-11-22). */
class SeedRejectedError extends Error {}

export class NvidiaNimProvider implements ImageProvider {
  readonly name = "nvidia-nim";

  /**
   * FLUX.1-schnell sur NIM rejette `seed` (422 extra_forbidden) : on le retire
   * apres le premier refus. Consequence : avec ce modele, un plan regenere ne
   * redonne PAS la meme image. Seuls l'`appearance` mot pour mot et le
   * STYLE_LOCK tiennent alors la coherence des personnages.
   */
  private seedSupported = true;

  constructor(
    private readonly apiKey = process.env.NVIDIA_API_KEY,
    private readonly model = process.env.NIM_IMAGE_MODEL || NIM_DEFAULT_MODEL,
    private readonly fetchImpl: Fetch = fetch,
    private readonly sleep: Sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  ) {}

  async isAvailable(): Promise<boolean> {
    return Boolean(this.apiKey);
  }

  async generate(request: ImageRequest): Promise<ImageResult> {
    if (!this.apiKey) {
      throw new Error("NVIDIA_API_KEY absente (GitHub Secrets, jamais dans le repo)");
    }

    const { width, height } = nimDimensions(request.width, request.height);
    const body: Record<string, unknown> = {
      prompt: request.prompt,
      width,
      height,
      steps: 4,
      cfg_scale: 0,
      mode: "base",
      samples: 1,
    };
    if (this.seedSupported) body.seed = request.seed;

    let payload: unknown;
    try {
      payload = await this.post(body);
    } catch (error) {
      if (!(error instanceof SeedRejectedError)) throw error;
      this.seedSupported = false;
      delete body.seed;
      payload = await this.post(body);
    }
    const bytes = extractNimImage(payload);

    await fs.mkdir(path.dirname(request.outPath), { recursive: true });
    await this.writeImage(bytes, request.outPath);
    return { outPath: request.outPath };
  }

  private async post(body: Record<string, unknown>): Promise<unknown> {
    const endpoint = `${NIM_BASE_URL}/${this.model}`;
    for (let attempt = 1; ; attempt += 1) {
      const response = await this.fetchImpl(endpoint, {
        method: "POST",
        headers: {
          authorization: `Bearer ${this.apiKey}`,
          accept: "application/json",
          "content-type": "application/json",
        },
        body: JSON.stringify(body),
      });
      if (response.ok) return response.json();

      const retryable = response.status === 429 || response.status >= 500;
      if (!retryable || attempt >= MAX_HTTP_ATTEMPTS) {
        // Le corps d'erreur ne contient jamais la cle : on peut le journaliser.
        const detail = await response.text();
        if (response.status === 422 && /extra_forbidden[^}]*"seed"/.test(detail)) {
          throw new SeedRejectedError();
        }
        throw new Error(`NVIDIA NIM ${response.status} : ${detail.slice(0, 200)}`);
      }
      const retryAfter = Number.parseFloat(response.headers.get("retry-after") ?? "");
      await this.sleep(Number.isFinite(retryAfter) ? retryAfter * 1000 : 2000 * attempt);
    }
  }

  /**
   * Le pipeline attend des `.png`, FLUX renvoie souvent du JPEG. FFmpeg choisit
   * son decodeur d'images d'apres l'extension : on convertit donc quand il le
   * faut, plutot que de confier un JPEG deguise au montage.
   */
  private async writeImage(bytes: Buffer, outPath: string): Promise<void> {
    const wantsPng = outPath.toLowerCase().endsWith(".png");
    if (!wantsPng || isPng(bytes) || !(await hasFfmpeg())) {
      await fs.writeFile(outPath, bytes);
      return;
    }
    const rawPath = `${outPath}.nim-src`;
    await fs.writeFile(rawPath, bytes);
    try {
      await run("ffmpeg", ["-y", "-v", "error", "-i", rawPath, "-frames:v", "1", "-f", "image2", "-c:v", "png", outPath]);
    } finally {
      await fs.rm(rawPath, { force: true });
    }
  }
}
