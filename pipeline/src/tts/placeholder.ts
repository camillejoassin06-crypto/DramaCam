import fs from "node:fs/promises";
import path from "node:path";
import { estimateDurationSeconds, type TtsProvider, type TtsRequest, type TtsResult } from "./index.ts";

/**
 * Fournisseur de dry-run : n'ecrit aucun audio reel et ne depend ni d'edge-tts
 * ni de ffmpeg. La duree est estimee a partir du texte.
 */
export class PlaceholderTtsProvider implements TtsProvider {
  readonly name = "placeholder";

  async isAvailable(): Promise<boolean> {
    return true;
  }

  async synthesize(request: TtsRequest): Promise<TtsResult> {
    const outPath = `${request.outPath}.txt`;
    await fs.mkdir(path.dirname(outPath), { recursive: true });
    await fs.writeFile(outPath, `[${request.voiceId}] ${request.text}\n`, "utf8");
    return { outPath, durationSeconds: estimateDurationSeconds(request.text) };
  }
}
