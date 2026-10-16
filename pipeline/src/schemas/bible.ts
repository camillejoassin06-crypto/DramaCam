import { z } from "zod";

export const beatSchema = z
  .object({
    episode: z.number().int().positive(),
    summary: z.string().min(20).max(600),
    /** Promesse de suite : c'est le moteur du binge en format vertical. */
    cliffhanger: z.string().min(10).max(300),
  })
  .strict();

export const bibleSchema = z
  .object({
    seriesTitle: z.string().min(1).max(80),
    beats: z.array(beatSchema).min(1),
  })
  .strict()
  .superRefine((bible, ctx) => {
    const expected = bible.beats.map((_, index) => index + 1);
    const actual = bible.beats.map((beat) => beat.episode);
    if (actual.join(",") !== expected.join(",")) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["beats"],
        message: `beats non contigus a partir de 1 (recu : ${actual.join(",")})`,
      });
    }
  });

export type Beat = z.infer<typeof beatSchema>;
export type Bible = z.infer<typeof bibleSchema>;
