import assert from "node:assert/strict";
import test from "node:test";
import {
  buildCues,
  formatTimestamp,
  MAX_CHARS_PER_LINE,
  MAX_LINES_PER_CUE,
  splitSegment,
  toVtt,
  wrapLines,
} from "../src/subtitles.ts";
import { estimateDurationSeconds } from "../src/tts/index.ts";

test("les horodatages respectent le format WebVTT", () => {
  assert.equal(formatTimestamp(0), "00:00:00.000");
  assert.equal(formatTimestamp(1.5), "00:00:01.500");
  assert.equal(formatTimestamp(83.25), "00:01:23.250");
  assert.equal(formatTimestamp(3725), "01:02:05.000");
});

test("un horodatage negatif est ramene a zero", () => {
  assert.equal(formatTimestamp(-4), "00:00:00.000");
});

test("le retour a la ligne ne coupe jamais un mot", () => {
  const text = "Le quai est vide, la rame ralentit, et personne ne descend jamais a cette station.";
  const lines = wrapLines(text);
  for (const line of lines) assert.ok(line.length <= MAX_CHARS_PER_LINE, line);
  assert.equal(lines.join(" "), text);
});

test("une replique courte tient en un seul cue", () => {
  const pieces = splitSegment({ text: "Il est deja monte.", durationSeconds: 4 });
  assert.equal(pieces.length, 1);
  assert.equal(pieces[0]?.durationSeconds, 4);
});

test("une replique longue est scindee et la duree totale est conservee", () => {
  const long = Array.from({ length: 40 }, (_, index) => `mot${index}`).join(" ");
  const pieces = splitSegment({ text: long, durationSeconds: 20 });
  assert.ok(pieces.length > 1);
  const total = pieces.reduce((sum, piece) => sum + piece.durationSeconds, 0);
  assert.ok(Math.abs(total - 20) < 1e-9);
  for (const piece of pieces) {
    assert.ok(piece.text.split("\n").length <= MAX_LINES_PER_CUE);
  }
});

test("les cues sont contigus et sans chevauchement", () => {
  const cues = buildCues([
    { text: "Premiere replique.", durationSeconds: 3 },
    { text: "Deuxieme replique.", durationSeconds: 4 },
    { text: "Troisieme replique.", durationSeconds: 2.5 },
  ]);
  assert.equal(cues.length, 3);
  assert.equal(cues[0]?.startSeconds, 0);
  for (let index = 1; index < cues.length; index += 1) {
    assert.equal(cues[index]?.startSeconds, cues[index - 1]?.endSeconds);
  }
  assert.equal(cues.at(-1)?.endSeconds, 9.5);
});

test("le .vtt produit un entete et des cues numerotes", () => {
  const vtt = toVtt(buildCues([{ text: "Une replique.", durationSeconds: 2 }]));
  assert.match(vtt, /^WEBVTT\n\n/);
  assert.match(vtt, /^1$/m);
  assert.match(vtt, /00:00:00\.000 --> 00:00:02\.000/);
  assert.match(vtt, /Une replique\./);
});

test("la duree estimee croit avec la longueur du texte et reste positive", () => {
  assert.ok(estimateDurationSeconds("Court.") >= 2);
  assert.ok(estimateDurationSeconds("x".repeat(140)) > estimateDurationSeconds("x".repeat(14)));
});
