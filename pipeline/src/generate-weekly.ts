#!/usr/bin/env tsx
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  DEFAULT_EPISODE_COUNT,
  EPISODE_MAX_SECONDS,
  EPISODE_MIN_SECONDS,
  MAX_ATTEMPTS_PER_EPISODE,
  OUT_DIR,
  QUALITY_REPORT_PATH,
  SHOTS_PER_EPISODE,
  VIDEO_HEIGHT,
  VIDEO_WIDTH,
  readEnv,
  type PipelineEnv,
} from "./config.ts";
import { generateValidated } from "./claude.ts";
import { hasFfmpeg } from "./ffmpeg.ts";
import { DRY_RUN_CONCEPT, dryRunBible, dryRunScript } from "./fixtures.ts";
import {
  AiStudioProvider,
  LocalSdProvider,
  PlaceholderImageProvider,
  buildImagePrompt,
  type ImageProvider,
} from "./images/index.ts";
import { packageHls } from "./hls.ts";
import { assertOriginalTitle, publishSeries, readCatalog, writeCatalog } from "./publish.ts";
import { renderEpisode, type ShotAsset } from "./render.ts";
import { bibleSchema, type Bible } from "./schemas/bible.ts";
import type { EpisodeEntry, SeriesEntry } from "./schemas/catalog.ts";
import { VOICE_IDS, conceptSchema, type Concept } from "./schemas/concept.ts";
import {
  NARRATOR,
  checkSpeakers,
  episodeScriptSchema,
  type EpisodeScript,
} from "./schemas/episode.ts";
import { QualityLog, qualityVerdictSchema } from "./schemas/quality.ts";
import { buildCues, toVtt, type SubtitleSegment } from "./subtitles.ts";
import {
  EdgeTtsProvider,
  KokoroProvider,
  PlaceholderTtsProvider,
  type TtsProvider,
} from "./tts/index.ts";

/**
 * Orchestrateur de la generation hebdomadaire (1 serie / semaine).
 *
 * Usage : tsx src/generate-weekly.ts [--dry-run] [--episodes N]
 *
 * La generation complete n'est lancee QUE par .github/workflows/weekly-drama.yml.
 * `.claude/settings.json` refuse l'appel sans --dry-run (fair-use, cf. CLAUDE.md).
 */

interface Args {
  dryRun: boolean;
  episodes: number;
}

type Logger = (message: string) => void;

export function parseArgs(argv: readonly string[]): Args {
  const dryRun = argv.includes("--dry-run");
  const flag = argv.indexOf("--episodes");
  const raw = flag === -1 ? undefined : argv[flag + 1];
  const episodes = raw === undefined ? DEFAULT_EPISODE_COUNT : Number.parseInt(raw, 10);
  if (!Number.isInteger(episodes) || episodes < 1 || episodes > DEFAULT_EPISODE_COUNT) {
    throw new Error(`--episodes doit etre un entier entre 1 et ${DEFAULT_EPISODE_COUNT}`);
  }
  return { dryRun, episodes };
}

const pad3 = (value: number): string => String(value).padStart(3, "0");

const stripTrailingSlash = (value: string): string => value.replace(/\/$/, "");

async function pickTts(env: PipelineEnv, dryRun: boolean, log: Logger): Promise<TtsProvider> {
  if (dryRun) return new PlaceholderTtsProvider();
  const candidates: TtsProvider[] =
    env.ttsProvider === "kokoro"
      ? [new KokoroProvider(), new EdgeTtsProvider()]
      : [new EdgeTtsProvider(), new KokoroProvider()];
  for (const provider of candidates) {
    if (await provider.isAvailable()) return provider;
  }
  log(`aucun TTS disponible (${candidates.map((item) => item.name).join(", ")}) : repli placeholder`);
  return new PlaceholderTtsProvider();
}

async function pickImages(env: PipelineEnv, dryRun: boolean, log: Logger): Promise<ImageProvider> {
  if (dryRun || env.imageProvider === "placeholder") return new PlaceholderImageProvider();
  const candidates: ImageProvider[] =
    env.imageProvider === "ai-studio"
      ? [new AiStudioProvider(), new LocalSdProvider()]
      : [new LocalSdProvider(), new AiStudioProvider()];
  for (const provider of candidates) {
    if (await provider.isAvailable()) return provider;
  }
  log("aucun fournisseur d'images disponible : repli placeholder");
  return new PlaceholderImageProvider();
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const env = readEnv();
  const quality = new QualityLog();
  const log: Logger = (message) => console.log(message);

  log(
    `DramaForge — generation ${args.dryRun ? "DRY-RUN" : "COMPLETE"} : ` +
      `${args.episodes} episode(s), ${SHOTS_PER_EPISODE} plans/episode`,
  );

  const catalog = await readCatalog();

  // 1. Concept -------------------------------------------------------------
  const concept: Concept = args.dryRun
    ? DRY_RUN_CONCEPT
    : await generateValidated({
        prompt: "concept",
        schema: conceptSchema,
        maxAttempts: MAX_ATTEMPTS_PER_EPISODE,
        vars: {
          LANGUAGE: "fr",
          EPISODE_COUNT: args.episodes,
          EXCLUDED_TERMS: catalog.excludedTerms.join(", "),
          VOICE_IDS: VOICE_IDS.join(", "),
        },
        onFailure: (attempt, reason) =>
          quality.add({
            stage: "concept",
            severity: "warning",
            message: `tentative ${attempt} : ${reason}`,
          }),
      });

  assertOriginalTitle(catalog, concept.title);
  log(`concept : ${concept.title} (${concept.characters.length} personnages)`);

  // 2. Bible ---------------------------------------------------------------
  const bible: Bible = args.dryRun
    ? dryRunBible(args.episodes)
    : await generateValidated({
        prompt: "bible",
        schema: bibleSchema,
        maxAttempts: MAX_ATTEMPTS_PER_EPISODE,
        vars: {
          CONCEPT_JSON: JSON.stringify(concept, null, 2),
          EPISODE_COUNT: args.episodes,
        },
        onFailure: (attempt, reason) =>
          quality.add({
            stage: "bible",
            severity: "warning",
            message: `tentative ${attempt} : ${reason}`,
          }),
      });

  const beats = bible.beats.slice(0, args.episodes);
  if (beats.length < args.episodes) {
    throw new Error(`bible incomplete : ${beats.length} beats pour ${args.episodes} episodes`);
  }

  // 3. Fournisseurs --------------------------------------------------------
  const tts = await pickTts(env, args.dryRun, log);
  const images = await pickImages(env, args.dryRun, log);
  const ffmpegReady = await hasFfmpeg();
  log(
    `TTS : ${tts.name} · images : ${images.name} · ffmpeg : ${ffmpegReady ? "disponible" : "ABSENT"}`,
  );
  if (!ffmpegReady) {
    quality.add({
      stage: "render",
      severity: args.dryRun ? "warning" : "blocker",
      message: "ffmpeg absent : montage et HLS sautes",
    });
  }

  const seriesDir = path.join(OUT_DIR, "media", "series", concept.id);
  await fs.mkdir(seriesDir, { recursive: true });

  // Jaquette : cadree sur le personnage principal, meme STYLE_LOCK que les plans.
  await images.generate({
    prompt: buildImagePrompt(
      `vertical poster, ${concept.characters[0]?.name ?? "protagonist"} looking away, night city, rain`,
      concept.characters,
    ),
    outPath: path.join(seriesDir, "cover.png"),
    width: VIDEO_WIDTH,
    height: VIDEO_HEIGHT,
    seed: 1,
  });

  // 4. Episodes ------------------------------------------------------------
  const entries: EpisodeEntry[] = [];
  let seriesBytes = 0;

  for (const beat of beats) {
    const number = beat.episode;
    const previous = beats[number - 2];
    const episodeDir = path.join(seriesDir, `ep-${pad3(number)}`);
    const workDir = path.join(OUT_DIR, "work", concept.id, `ep-${pad3(number)}`);
    await fs.mkdir(episodeDir, { recursive: true });

    const script: EpisodeScript = args.dryRun
      ? dryRunScript(number)
      : await generateValidated({
          prompt: "episode",
          schema: episodeScriptSchema,
          maxAttempts: MAX_ATTEMPTS_PER_EPISODE,
          vars: {
            CONCEPT_JSON: JSON.stringify(concept, null, 2),
            BEAT_JSON: JSON.stringify(beat, null, 2),
            PREVIOUS_BEAT_JSON: previous
              ? JSON.stringify(previous, null, 2)
              : "aucun (premier episode)",
            EPISODE_NUMBER: number,
            SHOTS_PER_EPISODE,
            EPISODE_MIN_SECONDS,
            EPISODE_MAX_SECONDS,
            NARRATOR,
          },
          onFailure: (attempt, reason) =>
            quality.add({
              episode: number,
              stage: "script",
              severity: "warning",
              message: `tentative ${attempt} : ${reason}`,
            }),
        });

    const unknownSpeakers = checkSpeakers(script, concept);
    if (unknownSpeakers.length > 0) {
      quality.add({
        episode: number,
        stage: "script",
        severity: "blocker",
        message: `locuteurs inconnus du concept : ${unknownSpeakers.join(", ")}`,
      });
    }

    // Revue de surete et de droits, obligatoire avant rendu (cf. CLAUDE.md).
    if (args.dryRun) {
      quality.add({
        episode: number,
        stage: "review",
        severity: "warning",
        message: "revue de surete non executee (dry-run)",
      });
    } else {
      const verdict = await generateValidated({
        prompt: "quality",
        schema: qualityVerdictSchema,
        maxAttempts: 2,
        vars: {
          CONCEPT_JSON: JSON.stringify(concept, null, 2),
          SCRIPT_JSON: JSON.stringify(script, null, 2),
        },
      });
      const failed = [
        verdict.originalContent ? undefined : "originalite",
        verdict.characterConsistency ? undefined : "coherence des personnages",
        verdict.safeForTargetRating ? undefined : "surete du contenu",
      ].filter((value): value is string => value !== undefined);

      for (const concern of verdict.concerns) {
        quality.add({
          episode: number,
          stage: "review",
          severity: failed.length > 0 ? "blocker" : "warning",
          message: concern,
        });
      }
      if (failed.length > 0) {
        quality.add({
          episode: number,
          stage: "review",
          severity: "blocker",
          message: `revue echouee : ${failed.join(", ")}`,
        });
      }
    }

    // Voix et images, plan par plan.
    const assets: ShotAsset[] = [];
    const segments: SubtitleSegment[] = [];

    for (const shot of script.shots) {
      const character = concept.characters.find((item) => item.name === shot.speaker);
      const voiceId = character?.voiceId ?? concept.characters[0]?.voiceId ?? VOICE_IDS[0];

      const voice = await tts.synthesize({
        text: shot.line,
        voiceId,
        outPath: path.join(workDir, `shot-${pad3(shot.index)}.mp3`),
      });

      const imagePath = path.join(workDir, `shot-${pad3(shot.index)}.png`);
      await images.generate({
        prompt: buildImagePrompt(shot.imagePrompt, concept.characters),
        outPath: imagePath,
        width: VIDEO_WIDTH,
        height: VIDEO_HEIGHT,
        // Graine stable : regenerer un plan redonne exactement la meme image.
        seed: number * 100 + shot.index,
      });

      // Un plan ne peut pas durer moins que sa replique, sinon la voix est coupee.
      const durationSeconds = Math.max(shot.durationSeconds, voice.durationSeconds);
      assets.push({
        imagePath,
        audioPath: voice.outPath.endsWith(".mp3") ? voice.outPath : undefined,
        durationSeconds,
      });
      segments.push({ text: shot.line, durationSeconds });
    }

    await fs.writeFile(path.join(episodeDir, "fr.vtt"), toVtt(buildCues(segments)), "utf8");

    let durationSeconds = Math.round(
      assets.reduce((sum, asset) => sum + asset.durationSeconds, 0),
    );
    let sizeBytes = 0;

    if (ffmpegReady) {
      const rendered = await renderEpisode({
        shots: assets,
        outPath: path.join(workDir, "episode.mp4"),
        workDir,
      });
      const packaged = await packageHls(rendered.outPath, episodeDir);
      durationSeconds = Math.round(rendered.durationSeconds);
      sizeBytes = packaged.sizeBytes;
    }

    if (durationSeconds < EPISODE_MIN_SECONDS || durationSeconds > EPISODE_MAX_SECONDS) {
      quality.add({
        episode: number,
        stage: "render",
        severity: "warning",
        message: `duree ${durationSeconds} s hors cible ${EPISODE_MIN_SECONDS}-${EPISODE_MAX_SECONDS} s`,
      });
    }

    seriesBytes += sizeBytes;
    const base = `${stripTrailingSlash(env.r2PublicBaseUrl)}/series/${concept.id}/ep-${pad3(number)}`;
    entries.push({
      number,
      title: script.title,
      // Bornes du schema catalogue : une duree aberrante est deja signalee ci-dessus.
      durationSeconds: Math.min(Math.max(durationSeconds, 60), 300),
      hlsUrl: `${base}/master.m3u8`,
      subtitlesUrl: `${base}/fr.vtt`,
      sizeBytes,
    });

    log(`  episode ${number}/${args.episodes} : ${script.title} (${durationSeconds} s)`);
  }

  // 5. Serie et publication ------------------------------------------------
  const series: SeriesEntry = {
    id: concept.id,
    title: concept.title,
    logline: concept.logline,
    genres: concept.genres,
    language: concept.language,
    badges: ["NOUVEAU"],
    coverUrl: `${stripTrailingSlash(env.r2PublicBaseUrl)}/series/${concept.id}/cover.png`,
    publishedAt: new Date().toISOString(),
    totalBytes: seriesBytes,
    episodes: entries,
  };

  await fs.mkdir(OUT_DIR, { recursive: true });
  const report = quality.toReport(concept.id, args.dryRun);
  await fs.writeFile(QUALITY_REPORT_PATH, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  log(`rapport qualite : ${report.issues.length} entree(s) -> ${QUALITY_REPORT_PATH}`);

  // En dry-run l'absence de ffmpeg est attendue et ne bloque pas.
  const blockers = quality.blockers.filter(
    (issue) => !(args.dryRun && issue.stage === "render"),
  );
  if (blockers.length > 0) {
    for (const blocker of blockers) {
      const scope = blocker.episode === undefined ? "" : ` ep.${blocker.episode}`;
      console.error(`  BLOQUANT ${blocker.stage}${scope} : ${blocker.message}`);
    }
    throw new Error(`publication bloquee : ${blockers.length} probleme(s) bloquant(s)`);
  }

  const updated = await publishSeries({
    series,
    mediaDir: seriesDir,
    env,
    dryRun: args.dryRun,
    log,
  });

  // Un dry-run n'ecrit jamais le vrai catalogue.
  const target = args.dryRun ? path.join(OUT_DIR, "catalog.preview.json") : undefined;
  await writeCatalog(updated, target);
  log(`catalogue ecrit : ${target ?? "catalog/catalog.json"}`);
}

/** Ne demarre la generation que si le fichier est lance directement : les tests
 *  importent parseArgs sans declencher le pipeline. */
const invokedDirectly =
  process.argv[1] !== undefined &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  main().catch((error: unknown) => {
    console.error(`\nechec : ${(error as Error).message}`);
    process.exitCode = 1;
  });
}
