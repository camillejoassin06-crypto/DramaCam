import fs from "node:fs/promises";
import path from "node:path";
import zlib from "node:zlib";
import type { ImageProvider, ImageRequest, ImageResult } from "./index.ts";

/**
 * Fournisseur de dry-run : ecrit un vrai PNG uni, deterministe (couleur
 * derivee de la graine), sans GPU, sans reseau et sans dependance externe.
 * Suffisant pour verifier le montage FFmpeg et la structure du catalogue.
 */
export class PlaceholderImageProvider implements ImageProvider {
  readonly name = "placeholder";

  async isAvailable(): Promise<boolean> {
    return true;
  }

  async generate(request: ImageRequest): Promise<ImageResult> {
    await fs.mkdir(path.dirname(request.outPath), { recursive: true });
    const hue = request.seed % 360;
    await fs.writeFile(
      request.outPath,
      solidPng(request.width, request.height, hueToRgb(hue, 0.35, 0.18)),
    );
    return { outPath: request.outPath };
  }
}

type Rgb = readonly [number, number, number];

/** Teintes sombres dans l'esprit de la DA (fond #0E0B16). */
function hueToRgb(hue: number, saturation: number, lightness: number): Rgb {
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const sector = hue / 60;
  const second = chroma * (1 - Math.abs((sector % 2) - 1));
  const base: Rgb =
    sector < 1 ? [chroma, second, 0]
    : sector < 2 ? [second, chroma, 0]
    : sector < 3 ? [0, chroma, second]
    : sector < 4 ? [0, second, chroma]
    : sector < 5 ? [second, 0, chroma]
    : [chroma, 0, second];
  const offset = lightness - chroma / 2;
  return [
    Math.round((base[0] + offset) * 255),
    Math.round((base[1] + offset) * 255),
    Math.round((base[2] + offset) * 255),
  ];
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buffer: Buffer): number {
  let crc = -1;
  for (const byte of buffer) crc = CRC_TABLE[(crc ^ byte) & 0xff]! ^ (crc >>> 8);
  return (crc ^ -1) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}

/** PNG 8 bits RGB, une seule couleur, encode sans dependance tierce. */
function solidPng(width: number, height: number, [r, g, b]: Rgb): Buffer {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8; // profondeur
  header[9] = 2; // couleur verite (RGB)

  const row = Buffer.alloc(1 + width * 3);
  for (let x = 0; x < width; x += 1) {
    row[1 + x * 3] = r;
    row[2 + x * 3] = g;
    row[3 + x * 3] = b;
  }
  const raw = Buffer.concat(Array.from({ length: height }, () => row));

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", header),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
