import { z } from "zod";

/** Verdict rendu par le modele via prompts/quality.md. */
export const qualityVerdictSchema = z
  .object({
    originalContent: z.boolean(),
    characterConsistency: z.boolean(),
    safeForTargetRating: z.boolean(),
    concerns: z.array(z.string().min(1)),
  })
  .strict();

export const qualityIssueSchema = z
  .object({
    episode: z.number().int().positive().optional(),
    stage: z.enum(["concept", "bible", "script", "tts", "images", "render", "publish", "review"]),
    severity: z.enum(["blocker", "warning"]),
    message: z.string().min(1),
  })
  .strict();

/**
 * Journal ecrit dans pipeline/out/quality-report.json. Une sortie invalide est
 * journalisee ici et le pipeline continue ; seuls les `blocker` empechent la
 * publication.
 */
export const qualityReportSchema = z
  .object({
    generatedAt: z.string().datetime(),
    seriesId: z.string().min(1),
    dryRun: z.boolean(),
    issues: z.array(qualityIssueSchema),
  })
  .strict();

export type QualityVerdict = z.infer<typeof qualityVerdictSchema>;
export type QualityIssue = z.infer<typeof qualityIssueSchema>;
export type QualityReport = z.infer<typeof qualityReportSchema>;

/** Collecteur partage par toutes les etapes du pipeline. */
export class QualityLog {
  private readonly issues: QualityIssue[] = [];

  add(issue: QualityIssue): void {
    this.issues.push(issue);
  }

  get blockers(): QualityIssue[] {
    return this.issues.filter((issue) => issue.severity === "blocker");
  }

  toReport(seriesId: string, dryRun: boolean): QualityReport {
    return {
      generatedAt: new Date().toISOString(),
      seriesId,
      dryRun,
      issues: this.issues,
    };
  }
}
