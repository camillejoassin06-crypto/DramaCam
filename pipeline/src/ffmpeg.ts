import { isAvailable, run } from "./proc.ts";

/**
 * Point d'integration FFmpeg. Absent de la machine ? renderEpisode et
 * packageHls sont sautes et l'absence est journalisee dans le quality-report.
 */

export const hasFfmpeg = (): Promise<boolean> => isAvailable("ffmpeg");

export async function probeDurationSeconds(file: string): Promise<number> {
  const { stdout } = await run("ffprobe", [
    "-v", "error",
    "-show_entries", "format=duration",
    "-of", "default=noprint_wrappers=1:nokey=1",
    file,
  ]);
  const seconds = Number.parseFloat(stdout.trim());
  if (!Number.isFinite(seconds)) throw new Error(`duree illisible pour ${file}`);
  return seconds;
}
