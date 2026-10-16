import { z } from "zod";
import { EPISODE_MAX_SECONDS, EPISODE_MIN_SECONDS, SHOTS_PER_EPISODE } from "../config.ts";
import type { Concept } from "./concept.ts";

/** Locuteur reserve au narrateur hors dialogue. */
export const NARRATOR = "NARRATEUR";

export const shotSchema = z
  .object({
    index: z.number().int().min(1).max(SHOTS_PER_EPISODE),
    /** Nom d'un personnage du concept, ou NARRATEUR. Verifie par checkSpeakers(). */
    speaker: z.string().min(1).max(40),
    /** Texte lu par le TTS. Sert aussi de source aux sous-titres .vtt. */
    line: z.string().min(1).max(400),
    /** Prompt d'image SANS le STYLE_LOCK ni l'appearance : le pipeline les concatene. */
    imagePrompt: z.string().min(20).max(500),
    durationSeconds: z.number().min(6).max(45),
  })
  .strict();

export const episodeScriptSchema = z
  .object({
    number: z.number().int().positive(),
    title: z.string().min(1).max(120),
    shots: z.array(shotSchema).length(SHOTS_PER_EPISODE),
    cliffhanger: z.string().min(10).max(300),
  })
  .strict()
  .superRefine((script, ctx) => {
    const expected = script.shots.map((_, index) => index + 1);
    const actual = script.shots.map((shot) => shot.index);
    if (actual.join(",") !== expected.join(",")) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["shots"],
        message: `plans non contigus de 1 a ${SHOTS_PER_EPISODE} (recu : ${actual.join(",")})`,
      });
    }

    const total = script.shots.reduce((sum, shot) => sum + shot.durationSeconds, 0);
    if (total < EPISODE_MIN_SECONDS || total > EPISODE_MAX_SECONDS) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["shots"],
        message: `duree planifiee ${Math.round(total)} s hors cible ${EPISODE_MIN_SECONDS}-${EPISODE_MAX_SECONDS} s`,
      });
    }
  });

export type Shot = z.infer<typeof shotSchema>;
export type EpisodeScript = z.infer<typeof episodeScriptSchema>;

/**
 * Verifie que chaque locuteur existe dans le concept. Hors schema zod : la
 * contrainte depend du concept, pas de la forme du JSON.
 */
export function checkSpeakers(script: EpisodeScript, concept: Concept): string[] {
  const known = new Set<string>([NARRATOR, ...concept.characters.map((c) => c.name)]);
  const unknown = script.shots
    .map((shot) => shot.speaker)
    .filter((speaker) => !known.has(speaker));
  return [...new Set(unknown)];
}
