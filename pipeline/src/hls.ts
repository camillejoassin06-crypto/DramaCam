import fs from "node:fs/promises";
import path from "node:path";
import { run } from "./proc.ts";
import { dirSizeBytes } from "./fsutil.ts";

/** Empaquetage HLS vertical. 480p par defaut ; 720p seulement s'il reste de la place sur R2. */

export interface HlsVariant {
  name: string;
  width: number;
  height: number;
  videoBitrateKbps: number;
  audioBitrateKbps: number;
}

export const VARIANT_480P: HlsVariant = {
  name: "480p",
  width: 480,
  height: 854,
  videoBitrateKbps: 800,
  audioBitrateKbps: 96,
};

export const VARIANT_720P: HlsVariant = {
  name: "720p",
  width: 720,
  height: 1280,
  videoBitrateKbps: 1800,
  audioBitrateKbps: 128,
};

/** Segments de 4 s : compromis latence de demarrage / nombre de requetes. */
const SEGMENT_SECONDS = 4;

export interface HlsResult {
  masterPath: string;
  outDir: string;
  sizeBytes: number;
}

export async function packageHls(
  inputPath: string,
  outDir: string,
  variants: readonly HlsVariant[] = [VARIANT_480P],
): Promise<HlsResult> {
  await fs.mkdir(outDir, { recursive: true });

  for (const variant of variants) {
    await run("ffmpeg", [
      "-y", "-i", inputPath,
      "-vf", `scale=${variant.width}:${variant.height}`,
      "-c:v", "libx264", "-preset", "veryfast", "-crf", "24",
      "-maxrate", `${variant.videoBitrateKbps}k`,
      "-bufsize", `${variant.videoBitrateKbps * 2}k`,
      // GOP fixe et aligne sur la duree de segment : indispensable pour que les
      // variantes restent commutables sans artefact.
      "-g", String(SEGMENT_SECONDS * 30), "-keyint_min", String(SEGMENT_SECONDS * 30),
      "-sc_threshold", "0",
      "-c:a", "aac", "-b:a", `${variant.audioBitrateKbps}k`,
      "-f", "hls",
      "-hls_time", String(SEGMENT_SECONDS),
      "-hls_playlist_type", "vod",
      "-hls_segment_filename", path.join(outDir, `${variant.name}_%03d.ts`),
      path.join(outDir, `${variant.name}.m3u8`),
    ]);
  }

  const masterPath = path.join(outDir, "master.m3u8");
  await fs.writeFile(masterPath, buildMaster(variants), "utf8");

  return { masterPath, outDir, sizeBytes: await dirSizeBytes(outDir) };
}

export function buildMaster(variants: readonly HlsVariant[]): string {
  const lines = ["#EXTM3U", "#EXT-X-VERSION:3"];
  for (const variant of variants) {
    const bandwidth = (variant.videoBitrateKbps + variant.audioBitrateKbps) * 1000;
    lines.push(
      `#EXT-X-STREAM-INF:BANDWIDTH=${bandwidth},RESOLUTION=${variant.width}x${variant.height}`,
      `${variant.name}.m3u8`,
    );
  }
  return `${lines.join("\n")}\n`;
}
