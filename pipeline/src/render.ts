import fs from "node:fs/promises";
import path from "node:path";
import { VIDEO_HEIGHT, VIDEO_WIDTH } from "./config.ts";
import { probeDurationSeconds } from "./ffmpeg.ts";
import { run } from "./proc.ts";

/** Montage vertical 1080x1920 : Ken Burns sur images fixes + voix + musique duckee. */

export interface ShotAsset {
  imagePath: string;
  /** Audio de la voix. Absent en dry-run sans TTS reel. */
  audioPath: string | undefined;
  durationSeconds: number;
}

export interface RenderOptions {
  shots: readonly ShotAsset[];
  outPath: string;
  workDir: string;
  /** Piste musicale bouclee et duckee sous la voix. Optionnelle. */
  musicPath?: string | undefined;
  fps?: number;
}

const FPS = 30;
/** Sur-echantillonnage avant zoompan : evite l'escalier sur les bords. */
const OVERSCAN = 1.25;

export async function renderEpisode(options: RenderOptions): Promise<{ outPath: string; durationSeconds: number }> {
  const fps = options.fps ?? FPS;
  await fs.mkdir(options.workDir, { recursive: true });
  await fs.mkdir(path.dirname(options.outPath), { recursive: true });

  const clips: string[] = [];
  for (const [index, shot] of options.shots.entries()) {
    const clipPath = path.join(options.workDir, `clip-${String(index + 1).padStart(2, "0")}.mp4`);
    await renderClip(shot, clipPath, fps, index);
    clips.push(clipPath);
  }

  const listPath = path.join(options.workDir, "clips.txt");
  await fs.writeFile(
    listPath,
    clips.map((clip) => `file '${concatPath(clip)}'`).join("\n"),
    "utf8",
  );

  await concat(listPath, options.outPath, options.musicPath);
  return { outPath: options.outPath, durationSeconds: await probeDurationSeconds(options.outPath) };
}

/**
 * Chemin tel que l'attend le demuxeur concat de FFmpeg : separateurs POSIX et
 * apostrophes echappees, sinon un chemin Windows casse la liste.
 */
function concatPath(file: string): string {
  return file.split(path.sep).join("/").split("'").join("'\\''");
}

/** Un plan = une image fixe animee (zoom lent alterne) + sa voix. */
async function renderClip(shot: ShotAsset, outPath: string, fps: number, index: number): Promise<void> {
  const frames = Math.max(1, Math.round(shot.durationSeconds * fps));
  const zoomIn = index % 2 === 0;
  const zoomExpr = zoomIn
    ? `min(zoom+0.0009,${OVERSCAN})`
    : `if(lte(zoom,1.0),${OVERSCAN},max(zoom-0.0009,1.0))`;

  const scaleWidth = Math.round(VIDEO_WIDTH * OVERSCAN);
  const scaleHeight = Math.round(VIDEO_HEIGHT * OVERSCAN);
  const videoFilter =
    `[0:v]scale=${scaleWidth}:${scaleHeight}:force_original_aspect_ratio=increase,` +
    `crop=${scaleWidth}:${scaleHeight},` +
    `zoompan=z='${zoomExpr}':d=${frames}:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=${VIDEO_WIDTH}x${VIDEO_HEIGHT}:fps=${fps},` +
    `format=yuv420p[v]`;

  const args = ["-y", "-loop", "1", "-i", shot.imagePath];
  if (shot.audioPath) args.push("-i", shot.audioPath);
  else args.push("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000");

  args.push(
    "-filter_complex", videoFilter,
    "-map", "[v]",
    "-map", "1:a",
    "-t", shot.durationSeconds.toFixed(3),
    "-r", String(fps),
    "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
    "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
    outPath,
  );

  await run("ffmpeg", args);
}

async function concat(listPath: string, outPath: string, musicPath: string | undefined): Promise<void> {
  const args = ["-y", "-f", "concat", "-safe", "0", "-i", listPath];

  if (!musicPath) {
    args.push("-c", "copy", outPath);
    await run("ffmpeg", args);
    return;
  }

  // La musique est compressee par la voix en chaine laterale (ducking), puis
  // remelangee sous elle.
  args.push(
    "-stream_loop", "-1", "-i", musicPath,
    "-filter_complex",
    "[1:a]volume=0.30[music];" +
      "[music][0:a]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=400[ducked];" +
      "[0:a][ducked]amix=inputs=2:duration=first:dropout_transition=0,alimiter=limit=0.95[aout]",
    "-map", "0:v", "-map", "[aout]",
    "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-shortest",
    outPath,
  );
  await run("ffmpeg", args);
}
