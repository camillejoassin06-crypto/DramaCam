/**
 * Generation des sous-titres .vtt a partir des repliques et de leur duree
 * reelle (mesuree sur l'audio TTS, pas estimee par le modele) : c'est ce qui
 * garantit l'alignement.
 */

export const MAX_CHARS_PER_LINE = 42;
export const MAX_LINES_PER_CUE = 2;
const MAX_CHARS_PER_CUE = MAX_CHARS_PER_LINE * MAX_LINES_PER_CUE;

export interface SubtitleSegment {
  text: string;
  durationSeconds: number;
}

export interface SubtitleCue {
  startSeconds: number;
  endSeconds: number;
  /** Deja decoupe en 1 ou 2 lignes. */
  text: string;
}

/** `00:01:23.456` */
export function formatTimestamp(totalSeconds: number): string {
  const clamped = Math.max(0, totalSeconds);
  const hours = Math.floor(clamped / 3600);
  const minutes = Math.floor((clamped % 3600) / 60);
  const seconds = Math.floor(clamped % 60);
  const milliseconds = Math.round((clamped - Math.floor(clamped)) * 1000);
  const pad = (value: number, size = 2) => String(value).padStart(size, "0");
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}.${pad(milliseconds, 3)}`;
}

/** Coupe sur les espaces, sans jamais tronquer un mot. */
export function wrapLines(text: string, maxChars = MAX_CHARS_PER_LINE): string[] {
  const lines: string[] = [];
  let current = "";
  for (const word of text.split(/\s+/).filter(Boolean)) {
    if (current === "") current = word;
    else if (`${current} ${word}`.length <= maxChars) current = `${current} ${word}`;
    else {
      lines.push(current);
      current = word;
    }
  }
  if (current !== "") lines.push(current);
  return lines;
}

/**
 * Une replique trop longue pour deux lignes est scindee en plusieurs cues,
 * dont la duree est proportionnelle au nombre de caracteres.
 */
export function splitSegment(segment: SubtitleSegment): SubtitleSegment[] {
  const lines = wrapLines(segment.text);
  if (lines.length <= MAX_LINES_PER_CUE) {
    return [{ text: lines.join("\n"), durationSeconds: segment.durationSeconds }];
  }

  const groups: string[] = [];
  for (let index = 0; index < lines.length; index += MAX_LINES_PER_CUE) {
    groups.push(lines.slice(index, index + MAX_LINES_PER_CUE).join("\n"));
  }

  const totalChars = groups.reduce((sum, group) => sum + group.length, 0);
  return groups.map((group) => ({
    text: group,
    durationSeconds: (segment.durationSeconds * group.length) / totalChars,
  }));
}

export function buildCues(segments: readonly SubtitleSegment[]): SubtitleCue[] {
  const cues: SubtitleCue[] = [];
  let cursor = 0;
  for (const segment of segments) {
    for (const piece of splitSegment(segment)) {
      cues.push({
        startSeconds: cursor,
        endSeconds: cursor + piece.durationSeconds,
        text: piece.text,
      });
      cursor += piece.durationSeconds;
    }
  }
  return cues;
}

export function toVtt(cues: readonly SubtitleCue[]): string {
  const blocks = cues.map(
    (cue, index) =>
      `${index + 1}\n${formatTimestamp(cue.startSeconds)} --> ${formatTimestamp(cue.endSeconds)}\n${cue.text}`,
  );
  return `WEBVTT\n\n${blocks.join("\n\n")}\n`;
}

export { MAX_CHARS_PER_CUE };
