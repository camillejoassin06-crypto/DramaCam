import type { ImageProvider, ImageRequest, ImageResult } from "./index.ts";

/**
 * POINT D'INTEGRATION — ComfyUI local (Stable Diffusion sur GPU personnel).
 * Cout nul, aucune donnee ne quitte la machine.
 *
 * Non branche : l'API ComfyUI (`POST /prompt`) attend un graphe de workflow
 * propre a l'installation, puis l'image se recupere via `GET /history`.
 * Renseigner ce graphe avant d'activer IMAGE_PROVIDER=local-sd.
 */
export class LocalSdProvider implements ImageProvider {
  readonly name = "local-sd";

  constructor(private readonly baseUrl = process.env.COMFYUI_URL ?? "http://127.0.0.1:8188") {}

  async isAvailable(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/system_stats`, {
        signal: AbortSignal.timeout(1500),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  async generate(_request: ImageRequest): Promise<ImageResult> {
    throw new Error(
      `LocalSdProvider (${this.baseUrl}) : graphe ComfyUI non renseigne. ` +
        "Brancher le workflow attendu par POST /prompt, ou basculer sur " +
        "IMAGE_PROVIDER=placeholder / ai-studio.",
    );
  }
}
