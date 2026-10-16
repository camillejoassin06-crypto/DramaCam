import { z } from "zod";
import { slug } from "./catalog.ts";

/** Voix edge-tts retenues. Une voix est attachee a un personnage pour toute la serie. */
export const VOICE_IDS = [
  "fr-FR-DeniseNeural",
  "fr-FR-EloiseNeural",
  "fr-FR-HenriNeural",
  "fr-FR-RemyMultilingualNeural",
  "fr-FR-VivienneMultilingualNeural",
] as const;

export const characterSchema = z
  .object({
    name: z.string().min(1).max(40),
    role: z.string().min(1).max(120),
    voiceId: z.enum(VOICE_IDS),
    /**
     * Description physique figee. Reinjectee MOT POUR MOT dans chaque prompt
     * d'image : c'est le seul garant de la coherence du personnage.
     */
    appearance: z.string().min(20).max(400),
  })
  .strict();

export const conceptSchema = z
  .object({
    id: slug,
    title: z.string().min(1).max(80),
    logline: z.string().min(1).max(300),
    genres: z.array(z.string().min(1)).min(1).max(4),
    tone: z.string().min(1).max(200),
    language: z.enum(["fr", "en"]),
    characters: z.array(characterSchema).min(2).max(6),
  })
  .strict()
  .superRefine((concept, ctx) => {
    const names = concept.characters.map((character) => character.name.toLowerCase());
    if (new Set(names).size !== names.length) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["characters"],
        message: "noms de personnages en doublon",
      });
    }
    const voices = concept.characters.map((character) => character.voiceId);
    if (new Set(voices).size !== voices.length) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["characters"],
        message: "deux personnages partagent la meme voix",
      });
    }
  });

export type Character = z.infer<typeof characterSchema>;
export type Concept = z.infer<typeof conceptSchema>;
