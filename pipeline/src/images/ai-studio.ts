import fs from "node:fs/promises";
import path from "node:path";
import type { ImageProvider, ImageRequest, ImageResult } from "./index.ts";

/**
 * POINT D'INTEGRATION — repli sans GPU local.
 *
 * ATTENTION COUT : n'utiliser que l'offre GRATUITE d'AI Studio, avec une cle
 * personnelle sans facturation activee. Option A interdit tout service facture
 * a l'usage (cf. CLAUDE.md). Si l'offre gratuite disparait, retirer ce
 * fournisseur plutot que d'activer un plan payant.
 */
export class AiStudioProvider implements ImageProvider {
  readonly name = "ai-studio";

  constructor(
    private readonly apiKey = process.env.AI_STUDIO_API_KEY,
    private readonly model = process.env.AI_STUDIO_IMAGE_MODEL ?? "imagen-3.0-generate-002",
  ) {}

  async isAvailable(): Promise<boolean> {
    return Boolean(this.apiKey);
  }

  async generate(request: ImageRequest): Promise<ImageResult> {
    if (!this.apiKey) {
      throw new Error("AI_STUDIO_API_KEY absente (GitHub Secrets, jamais dans le repo)");
    }

    const endpoint =
      `https://generativelanguage.googleapis.com/v1beta/models/${this.model}:predict`;
    const response = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": this.apiKey },
      body: JSON.stringify({
        instances: [{ prompt: request.prompt }],
        parameters: { sampleCount: 1, aspectRatio: "9:16", seed: request.seed },
      }),
    });
    if (!response.ok) {
      throw new Error(`AI Studio ${response.status} : ${(await response.text()).slice(0, 200)}`);
    }

    const payload = (await response.json()) as {
      predictions?: { bytesBase64Encoded?: string }[];
    };
    const encoded = payload.predictions?.[0]?.bytesBase64Encoded;
    if (!encoded) throw new Error("AI Studio : reponse sans image");

    await fs.mkdir(path.dirname(request.outPath), { recursive: true });
    await fs.writeFile(request.outPath, Buffer.from(encoded, "base64"));
    return { outPath: request.outPath };
  }
}
