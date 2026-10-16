import fs from "node:fs/promises";
import path from "node:path";
import { probeDurationSeconds } from "../ffmpeg.ts";
import { isAvailable } from "../proc.ts";
import { estimateDurationSeconds, type TtsProvider, type TtsRequest, type TtsResult } from "./index.ts";

/**
 * POINT D'INTEGRATION — repli quand edge-tts est indisponible (reseau coupe,
 * quota, voix retiree). Attend une instance Kokoro locale exposant
 * POST /v1/audio/speech (compatible OpenAI), par defaut sur le port 8880.
 * Local uniquement : aucun service facture a l'usage.
 */
export class KokoroProvider implements TtsProvider {
  readonly name = "kokoro";

  constructor(private readonly baseUrl = process.env.KOKORO_URL ?? "http://127.0.0.1:8880") {}

  async isAvailable(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/health`, {
        signal: AbortSignal.timeout(1500),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  async synthesize(request: TtsRequest): Promise<TtsResult> {
    const response = await fetch(`${this.baseUrl}/v1/audio/speech`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        model: "kokoro",
        voice: request.voiceId,
        input: request.text,
        response_format: "mp3",
      }),
    });
    if (!response.ok) {
      throw new Error(`Kokoro ${response.status} : ${(await response.text()).slice(0, 200)}`);
    }

    await fs.mkdir(path.dirname(request.outPath), { recursive: true });
    await fs.writeFile(request.outPath, Buffer.from(await response.arrayBuffer()));

    const durationSeconds = (await isAvailable("ffprobe"))
      ? await probeDurationSeconds(request.outPath)
      : estimateDurationSeconds(request.text);

    return { outPath: request.outPath, durationSeconds };
  }
}
