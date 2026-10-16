import { SHOTS_PER_EPISODE } from "./config.ts";
import type { Bible } from "./schemas/bible.ts";
import type { Concept } from "./schemas/concept.ts";
import { NARRATOR, type EpisodeScript } from "./schemas/episode.ts";

/**
 * Donnees deterministes du dry-run. Aucun appel a `claude -p`, aucun reseau :
 * sert a verifier la plomberie (schemas, TTS, images, montage, catalogue) sans
 * consommer de quota (fair-use).
 */

export const DRY_RUN_CONCEPT: Concept = {
  id: "le-train-de-minuit",
  title: "Le Train de Minuit",
  logline:
    "Chaque nuit je prends le dernier train, et chaque nuit un passager de plus connait mon nom.",
  genres: ["Mystere", "Suspense"],
  tone: "Tension feutree, lumiere froide, confidences a voix basse.",
  language: "fr",
  characters: [
    {
      name: "Alma",
      role: "Archiviste de nuit qui rentre toujours par le dernier train",
      voiceId: "fr-FR-DeniseNeural",
      appearance:
        "femme de trente-deux ans, cheveux noirs coupes au carre, manteau de laine gris anthracite ferme jusqu'au col, echarpe bordeaux, silhouette fine, regard cerne",
    },
    {
      name: "Victor",
      role: "Controleur qui semble connaitre son trajet par coeur",
      voiceId: "fr-FR-HenriNeural",
      appearance:
        "homme de cinquante ans, carrure massive, uniforme bleu nuit use aux coudes, casquette tenue a la main, barbe grise taillee court, alliance au doigt",
    },
  ],
};

export function dryRunBible(episodeCount: number): Bible {
  return {
    seriesTitle: DRY_RUN_CONCEPT.title,
    beats: Array.from({ length: episodeCount }, (_, index) => ({
      episode: index + 1,
      summary:
        `Episode ${index + 1} de verification : Alma monte dans la voiture ${index + 1} et releve un detail ` +
        "qui ne figurait pas sur le plan affiche la veille. Victor evite sa question.",
      cliffhanger: `Un nom de plus est barre sur la liste que Victor garde dans sa poche (beat ${index + 1}).`,
    })),
  };
}

export function dryRunScript(episodeNumber: number): EpisodeScript {
  const shots = Array.from({ length: SHOTS_PER_EPISODE }, (_, index) => {
    const speaker = index % 3 === 0 ? NARRATOR : index % 2 === 0 ? "Alma" : "Victor";
    return {
      index: index + 1,
      speaker,
      line:
        `Plan ${index + 1} de l'episode ${episodeNumber}. ` +
        "Le quai est vide, la rame ralentit, et personne ne descend jamais a cette station.",
      imagePrompt:
        `night train platform, shot ${index + 1}, ${speaker === NARRATOR ? "empty carriage" : speaker} ` +
        "in frame, medium shot, cold fluorescent light, rain on the windows",
      durationSeconds: 20,
    };
  });

  return {
    number: episodeNumber,
    title: `Verification ${episodeNumber} : la voiture sans numero`,
    shots,
    cliffhanger: `Fin de verification ${episodeNumber} : la porte se referme sur un siege deja occupe.`,
  };
}
