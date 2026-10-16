import { STYLE_LOCK } from "../config.ts";
import type { Character } from "../schemas/concept.ts";

/**
 * Interface ImageProvider. 8 plans par episode.
 * Ajouter un fournisseur = ajouter un fichier ici ; ne pas modifier l'interface.
 */

export interface ImageRequest {
  prompt: string;
  outPath: string;
  width: number;
  height: number;
  /** Graine deterministe : meme plan regenere = meme image. */
  seed: number;
}

export interface ImageResult {
  outPath: string;
}

export interface ImageProvider {
  readonly name: string;
  isAvailable(): Promise<boolean>;
  generate(request: ImageRequest): Promise<ImageResult>;
}

/**
 * Assemble le prompt final : cadrage du scenariste + `appearance` MOT POUR MOT
 * des personnages a l'image + STYLE_LOCK immuable.
 *
 * L'ordre et la litteralite comptent : c'est ce qui garde un personnage
 * identique d'un episode a l'autre.
 */
export function buildImagePrompt(shotPrompt: string, characters: readonly Character[]): string {
  const present = characters.filter((character) =>
    new RegExp(`\\b${escapeRegExp(character.name)}\\b`, "i").test(shotPrompt),
  );
  const appearances = present.map((character) => `${character.name}: ${character.appearance}`);
  return [shotPrompt, ...appearances, STYLE_LOCK].join(". ");
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export { LocalSdProvider } from "./local-sd.ts";
export { AiStudioProvider } from "./ai-studio.ts";
export { PlaceholderImageProvider } from "./placeholder.ts";
