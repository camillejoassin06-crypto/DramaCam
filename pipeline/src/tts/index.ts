/**
 * Interface TtsProvider — IMMUABLE (figee au Sprint 2).
 * Ajouter un fournisseur = ajouter un fichier dans ce dossier, jamais modifier
 * cette interface : render.ts et generate-weekly.ts en dependent.
 */

export interface TtsRequest {
  text: string;
  /** Identifiant de voix propre au fournisseur (ex. fr-FR-DeniseNeural). */
  voiceId: string;
  /** Chemin du fichier audio a ecrire. */
  outPath: string;
}

export interface TtsResult {
  outPath: string;
  durationSeconds: number;
}

export interface TtsProvider {
  readonly name: string;
  /** Vrai si l'outil sous-jacent est installe et utilisable. */
  isAvailable(): Promise<boolean>;
  synthesize(request: TtsRequest): Promise<TtsResult>;
}

/**
 * Estimation de duree quand ffprobe n'est pas disponible (dry-run).
 * ~14 caracteres par seconde en francais parle a rythme de drama.
 */
export const CHARS_PER_SECOND = 14;

export function estimateDurationSeconds(text: string): number {
  return Math.max(2, Math.round((text.length / CHARS_PER_SECOND) * 10) / 10);
}

export { EdgeTtsProvider } from "./edge-tts.ts";
export { KokoroProvider } from "./kokoro.ts";
export { PlaceholderTtsProvider } from "./placeholder.ts";
