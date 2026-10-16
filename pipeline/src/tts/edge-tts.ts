import fs from "node:fs/promises";
import path from "node:path";
import { probeDurationSeconds } from "../ffmpeg.ts";
import { isAvailable, run } from "../proc.ts";
import { estimateDurationSeconds, type TtsProvider, type TtsRequest, type TtsResult } from "./index.ts";

/**
 * Fournisseur par defaut : CLI `edge-tts` (gratuit, voix fr-FR-*).
 * Installation : `pip install edge-tts` (cf. scripts/install-edge-tts.md).
 */
export class EdgeTtsProvider implements TtsProvider {
  readonly name = "edge-tts";

  async isAvailable(): Promise<boolean> {
    try {
      await run("edge-tts", ["--list-voices"]);
      return true;
    } catch {
      return false;
    }
  }

  async synthesize(request: TtsRequest): Promise<TtsResult> {
    await fs.mkdir(path.dirname(request.outPath), { recursive: true });
    await run("edge-tts", [
      "--voice", request.voiceId,
      "--text", request.text,
      "--write-media", request.outPath,
    ]);

    const durationSeconds = (await isAvailable("ffprobe"))
      ? await probeDurationSeconds(request.outPath)
      : estimateDurationSeconds(request.text);

    return { outPath: request.outPath, durationSeconds };
  }
}
