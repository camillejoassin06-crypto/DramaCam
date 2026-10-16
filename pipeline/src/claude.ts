import fs from "node:fs/promises";
import path from "node:path";
import type { z } from "zod";
import { MAX_ATTEMPTS_PER_EPISODE, PROMPTS_DIR } from "./config.ts";
import { spawnCommand } from "./proc.ts";

/**
 * Acces au modele via la CLI `claude -p` (abonnement Claude Code).
 *
 * L'API Anthropic directe est INTERDITE dans ce projet : elle est facturee a
 * l'usage et Option A impose un cout recurrent nul (cf. CLAUDE.md).
 */

export type PromptVars = Record<string, string | number>;

/** Remplace les jetons {{NOM}} du template. Jette si un jeton reste non resolu. */
export function renderPrompt(template: string, vars: PromptVars): string {
  const rendered = template.replace(/\{\{(\w+)\}\}/g, (match, key: string) => {
    const value = vars[key];
    return value === undefined ? match : String(value);
  });
  const leftovers = [...rendered.matchAll(/\{\{(\w+)\}\}/g)].map((match) => match[1]);
  if (leftovers.length > 0) {
    throw new Error(`variables de prompt non resolues : ${[...new Set(leftovers)].join(", ")}`);
  }
  return rendered;
}

export async function loadPrompt(name: string, vars: PromptVars): Promise<string> {
  const template = await fs.readFile(path.join(PROMPTS_DIR, `${name}.md`), "utf8");
  return renderPrompt(template, vars);
}

/** Retire un eventuel bloc de code autour du JSON et isole l'objet racine. */
export function extractJson(raw: string): string {
  let text = raw.trim();
  const fenced = /^```(?:json)?\s*([\s\S]*?)\s*```$/.exec(text);
  if (fenced?.[1]) text = fenced[1].trim();
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start === -1 || end <= start) {
    throw new Error(`aucun objet JSON dans la reponse (${text.slice(0, 120)}...)`);
  }
  return text.slice(start, end + 1);
}

/** Enveloppe de `claude -p --output-format json`. */
const claudeResultShape = /"result"\s*:/;

export async function callClaude(prompt: string): Promise<string> {
  return await new Promise<string>((resolve, reject) => {
    // Le prompt passe par stdin : il depasse regulierement la taille maximale
    // d'une ligne de commande Windows.
    const child = spawnCommand("claude", ["-p", "--output-format", "json"], { pipeStdin: true });
    let stdout = "";
    let stderr = "";
    child.stdout?.setEncoding("utf8");
    child.stderr?.setEncoding("utf8");
    child.stdout?.on("data", (chunk: string) => (stdout += chunk));
    child.stderr?.on("data", (chunk: string) => (stderr += chunk));
    child.on("error", (error) =>
      reject(new Error(`CLI claude introuvable ou non executable : ${error.message}`)),
    );
    child.on("close", (code) => {
      if (code !== 0) {
        reject(new Error(`claude -p a echoue (code ${code}) : ${stderr.trim() || "pas de stderr"}`));
        return;
      }
      if (!claudeResultShape.test(stdout)) {
        resolve(stdout);
        return;
      }
      try {
        const envelope = JSON.parse(stdout) as { result?: unknown; is_error?: boolean };
        if (envelope.is_error) {
          reject(new Error(`claude -p a renvoye une erreur : ${String(envelope.result)}`));
          return;
        }
        resolve(typeof envelope.result === "string" ? envelope.result : JSON.stringify(envelope.result));
      } catch (error) {
        reject(new Error(`enveloppe claude illisible : ${(error as Error).message}`));
      }
    });
    child.stdin?.end(prompt, "utf8");
  });
}

export interface GenerateOptions<T> {
  /** Nom du fichier de prompt dans src/prompts, sans extension. */
  prompt: string;
  vars: PromptVars;
  schema: z.ZodType<T>;
  /** Par defaut MAX_ATTEMPTS_PER_EPISODE (fair-use). */
  maxAttempts?: number;
  /** Appele a chaque tentative ratee, pour alimenter le quality-report. */
  onFailure?: (attempt: number, reason: string) => void;
}

/**
 * Genere une sortie validee par zod. Chaque echec (JSON illisible ou schema non
 * respecte) consomme une tentative ; le motif precis est reinjecte dans la
 * tentative suivante. Jette apres `maxAttempts`.
 */
export async function generateValidated<T>(options: GenerateOptions<T>): Promise<T> {
  const maxAttempts = options.maxAttempts ?? MAX_ATTEMPTS_PER_EPISODE;
  const basePrompt = await loadPrompt(options.prompt, options.vars);
  let lastReason = "";

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const prompt =
      attempt === 1
        ? basePrompt
        : `${basePrompt}\n\nLa tentative precedente a ete rejetee : ${lastReason}\nCorrige exactement ce point et renvoie uniquement le JSON.`;
    try {
      const parsed = options.schema.parse(JSON.parse(extractJson(await callClaude(prompt))));
      return parsed;
    } catch (error) {
      lastReason = summarize(error);
      options.onFailure?.(attempt, lastReason);
    }
  }

  throw new Error(
    `${options.prompt} : ${maxAttempts} tentatives epuisees (fair-use). Dernier motif : ${lastReason}`,
  );
}

function summarize(error: unknown): string {
  if (error && typeof error === "object" && "issues" in error) {
    const issues = (error as z.ZodError).issues
      .slice(0, 5)
      .map((issue) => `${issue.path.join(".") || "racine"} : ${issue.message}`);
    return `schema invalide — ${issues.join(" ; ")}`;
  }
  return (error as Error).message;
}
