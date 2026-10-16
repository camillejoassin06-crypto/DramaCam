import { spawn, type ChildProcess } from "node:child_process";

/**
 * Lancement de processus externes (ffmpeg, ffprobe, edge-tts, claude).
 *
 * Sous Windows ces commandes sont souvent des shims `.cmd`, que Node refuse de
 * lancer sans shell. On passe donc par le shell, mais avec une ligne de
 * commande construite et citee par nous : `shell: true` combine a un tableau
 * d'arguments ne les echappe pas (DEP0190) et casse tout chemin contenant un
 * espace.
 */

const WINDOWS = process.platform === "win32";

/** Cite un argument pour cmd.exe. Les guillemets internes se doublent. */
export function quoteForCmd(argument: string): string {
  if (argument === "") return '""';
  if (!/[\s"^&|<>()%!]/.test(argument)) return argument;
  return `"${argument.split('"').join('""')}"`;
}

export interface SpawnOptions {
  /** Ouvre stdin en pipe pour y ecrire le prompt. */
  pipeStdin?: boolean;
}

export function spawnCommand(
  command: string,
  args: readonly string[],
  options: SpawnOptions = {},
): ChildProcess {
  const stdio = [options.pipeStdin ? "pipe" : "ignore", "pipe", "pipe"] as const;
  return WINDOWS
    ? spawn([quoteForCmd(command), ...args.map(quoteForCmd)].join(" "), {
        stdio: [...stdio],
        shell: true,
      })
    : spawn(command, [...args], { stdio: [...stdio], shell: false });
}

export interface RunResult {
  stdout: string;
  stderr: string;
}

export async function run(command: string, args: readonly string[]): Promise<RunResult> {
  return await new Promise<RunResult>((resolve, reject) => {
    const child = spawnCommand(command, args);
    let stdout = "";
    let stderr = "";
    child.stdout?.setEncoding("utf8");
    child.stderr?.setEncoding("utf8");
    child.stdout?.on("data", (chunk: string) => (stdout += chunk));
    child.stderr?.on("data", (chunk: string) => (stderr += chunk));
    child.on("error", (error) => reject(new Error(`${command} indisponible : ${error.message}`)));
    child.on("close", (code) => {
      if (code === 0) resolve({ stdout, stderr });
      else reject(new Error(`${command} a echoue (code ${code}) : ${stderr.trim().slice(-500)}`));
    });
  });
}

/** Vrai si la commande repond a `-version`. */
export async function isAvailable(command: string): Promise<boolean> {
  try {
    await run(command, ["-version"]);
    return true;
  } catch {
    return false;
  }
}
