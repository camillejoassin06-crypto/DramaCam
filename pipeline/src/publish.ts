import fs from "node:fs/promises";
import path from "node:path";
import {
  BLOB_MAX_SERIES_BYTES,
  CATALOG_PATH,
  PURGE_THRESHOLD_BYTES,
  mediaPublicBaseUrl,
  type PipelineEnv,
} from "./config.ts";
import { formatBytes, listFiles } from "./fsutil.ts";
import { catalogSchema, findExcludedTerms, type Catalog, type SeriesEntry } from "./schemas/catalog.ts";

/**
 * Publication, selon MEDIA_STORAGE :
 * - r2 : upload -> purge si bucket > 9 Go -> mise a jour de catalog.json.
 *   R2 free = 10 Go, le seuil laisse une marge d'une serie.
 * - vercel-blob : une seule serie a la fois (cf. publishToBlob).
 */

const CONTENT_TYPES: Record<string, string> = {
  ".m3u8": "application/vnd.apple.mpegurl",
  ".ts": "video/mp2t",
  ".vtt": "text/vtt",
  ".mp4": "video/mp4",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
};

export function contentTypeFor(file: string): string {
  return CONTENT_TYPES[path.extname(file).toLowerCase()] ?? "application/octet-stream";
}

export async function readCatalog(file = CATALOG_PATH): Promise<Catalog> {
  return catalogSchema.parse(JSON.parse(await fs.readFile(file, "utf8")));
}

/** Valide AVANT d'ecrire : catalog.json ne doit jamais etre commite invalide. */
export async function writeCatalog(catalog: Catalog, file = CATALOG_PATH): Promise<void> {
  const validated = catalogSchema.parse(catalog);
  await fs.writeFile(file, `${JSON.stringify(validated, null, 2)}\n`, "utf8");
}

/** Insere ou remplace une serie, les plus recentes d'abord (ordre d'affichage Home). */
export function upsertSeries(catalog: Catalog, series: SeriesEntry): Catalog {
  const others = catalog.series.filter((existing) => existing.id !== series.id);
  return {
    ...catalog,
    generatedAt: new Date().toISOString(),
    series: [series, ...others].sort(
      (a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt),
    ),
  };
}

/** Refuse un titre reprenant une marque listee dans excludedTerms. */
export function assertOriginalTitle(catalog: Catalog, title: string): void {
  const hits = findExcludedTerms(title, catalog.excludedTerms);
  if (hits.length > 0) {
    throw new Error(`titre refuse, termes exclus presents : ${hits.join(", ")}`);
  }
}

/**
 * Selectionne les series a purger pour revenir sous le seuil, les plus
 * anciennes d'abord. Ne purge jamais la serie qu'on vient de publier.
 */
export function seriesToPurge(
  catalog: Catalog,
  incomingBytes: number,
  keepId: string,
  threshold = PURGE_THRESHOLD_BYTES,
): SeriesEntry[] {
  // La serie conservee compte dans l'occupation : seule la liste des candidats
  // a la purge l'exclut. `incomingBytes` sert quand elle n'est pas encore au
  // catalogue.
  let total =
    incomingBytes + catalog.series.reduce((sum, series) => sum + series.totalBytes, 0);

  const candidates = catalog.series
    .filter((series) => series.id !== keepId)
    .sort((a, b) => Date.parse(a.publishedAt) - Date.parse(b.publishedAt));

  const purge: SeriesEntry[] = [];
  for (const series of candidates) {
    if (total <= threshold) break;
    purge.push(series);
    total -= series.totalBytes;
  }
  return purge;
}

/** Stockage des medias. Ajouter un fournisseur = une fabrique de plus. */
export interface MediaStore {
  readonly name: string;
  /** Renvoie l'URL publique de l'objet. */
  put(key: string, body: Buffer, contentType: string): Promise<string>;
  deletePrefix(prefix: string): Promise<number>;
  usageBytes(): Promise<number>;
}

/**
 * Vercel Blob ne tient qu'une serie (1 Go, blocage de 30 jours au-dela) :
 * on vide `series/` AVANT l'envoi et on refuse une serie trop lourde. Rien
 * n'est envoye tant que ces deux conditions ne sont pas reunies.
 */
export function assertFitsBlob(seriesBytes: number, limit = BLOB_MAX_SERIES_BYTES): void {
  if (seriesBytes > limit) {
    throw new Error(
      `serie de ${formatBytes(seriesBytes)} > plafond Vercel Blob ${formatBytes(limit)} : ` +
        "envoi annule (un depassement bloquerait le stockage 30 jours)",
    );
  }
}

/** Import dynamique : un dry-run n'a besoin ni du SDK ni de credentials. */
async function createBlobStore(env: PipelineEnv): Promise<MediaStore> {
  const token = env.blobToken;
  if (!token) throw new Error("BLOB_READ_WRITE_TOKEN absent (GitHub Secrets)");
  const { put, list, del } = await import("@vercel/blob");

  const listAll = async (prefix: string) => {
    const blobs: { url: string; size: number }[] = [];
    let cursor: string | undefined;
    do {
      const page = await list({ prefix, cursor, limit: 1000, token });
      for (const blob of page.blobs) blobs.push({ url: blob.url, size: blob.size });
      cursor = page.hasMore ? page.cursor : undefined;
    } while (cursor);
    return blobs;
  };

  return {
    name: "vercel-blob",
    async put(key, body, contentType) {
      const result = await put(key, body, {
        access: "public",
        token,
        contentType,
        addRandomSuffix: false,
        allowOverwrite: true,
        cacheControlMaxAge: 31_536_000,
        multipart: body.length > 100 * 1024 ** 2,
      });
      return result.url;
    },
    async deletePrefix(prefix) {
      const blobs = await listAll(prefix);
      for (let index = 0; index < blobs.length; index += 1000) {
        await del(blobs.slice(index, index + 1000).map((blob) => blob.url), { token });
      }
      return blobs.length;
    },
    async usageBytes() {
      return (await listAll("")).reduce((sum, blob) => sum + blob.size, 0);
    },
  };
}

/** Import dynamique : un dry-run n'a besoin ni du SDK ni de credentials. */
async function createR2Client(env: PipelineEnv): Promise<MediaStore> {
  if (!env.r2AccountId || !env.r2AccessKeyId || !env.r2SecretAccessKey) {
    throw new Error("R2_ACCOUNT_ID / R2_ACCESS_KEY_ID / R2_SECRET_ACCESS_KEY absents (GitHub Secrets)");
  }
  const { S3Client, PutObjectCommand, ListObjectsV2Command, DeleteObjectsCommand } = await import(
    "@aws-sdk/client-s3"
  );
  const client = new S3Client({
    region: "auto",
    endpoint: `https://${env.r2AccountId}.r2.cloudflarestorage.com`,
    credentials: {
      accessKeyId: env.r2AccessKeyId,
      secretAccessKey: env.r2SecretAccessKey,
    },
  });

  const listAll = async (prefix: string) => {
    const keys: { Key: string; Size: number }[] = [];
    let token: string | undefined;
    do {
      const page = await client.send(
        new ListObjectsV2Command({ Bucket: env.r2Bucket, Prefix: prefix, ContinuationToken: token }),
      );
      for (const object of page.Contents ?? []) {
        if (object.Key) keys.push({ Key: object.Key, Size: object.Size ?? 0 });
      }
      token = page.NextContinuationToken;
    } while (token);
    return keys;
  };

  return {
    name: "r2",
    async put(key, body, contentType) {
      await client.send(
        new PutObjectCommand({
          Bucket: env.r2Bucket,
          Key: key,
          Body: body,
          ContentType: contentType,
          CacheControl: "public, max-age=31536000, immutable",
        }),
      );
      return `${env.r2PublicBaseUrl.replace(/\/$/, "")}/${key}`;
    },
    async deletePrefix(prefix) {
      const keys = await listAll(prefix);
      for (let index = 0; index < keys.length; index += 1000) {
        const batch = keys.slice(index, index + 1000);
        await client.send(
          new DeleteObjectsCommand({
            Bucket: env.r2Bucket,
            Delete: { Objects: batch.map((object) => ({ Key: object.Key })) },
          }),
        );
      }
      return keys.length;
    },
    async usageBytes() {
      return (await listAll("")).reduce((sum, object) => sum + object.Size, 0);
    },
  };
}

export interface PublishOptions {
  series: SeriesEntry;
  /** Racine locale des medias, calquee sur l'arborescence R2. */
  mediaDir: string;
  env: PipelineEnv;
  dryRun: boolean;
  log?: (message: string) => void;
}

export async function publishSeries(options: PublishOptions): Promise<Catalog> {
  const log = options.log ?? ((message: string) => console.log(message));
  const catalog = await readCatalog();
  assertOriginalTitle(catalog, options.series.title);

  const files = await listFiles(options.mediaDir);
  const prefix = `series/${options.series.id}`;

  const storage = options.env.mediaStorage;
  if (options.dryRun) {
    if (storage === "vercel-blob") assertFitsBlob(options.series.totalBytes);
    log(`[dry-run] ${files.length} fichiers seraient envoyes sur ${storage} sous ${prefix}/ (aucun appel)`);
    return storage === "vercel-blob"
      ? { ...upsertSeries(catalog, options.series), series: [options.series] }
      : upsertSeries(catalog, options.series);
  }

  if (storage === "vercel-blob") {
    return publishToBlob(options, catalog, files, prefix, log);
  }

  const client = await createR2Client(options.env);

  for (const file of files) {
    const body = await fs.readFile(path.join(options.mediaDir, file));
    await client.put(`${prefix}/${file}`, body, contentTypeFor(file));
  }
  log(`${files.length} fichiers publies sous ${prefix}/`);

  const usage = await client.usageBytes();
  log(`occupation R2 : ${formatBytes(usage)} / seuil ${formatBytes(PURGE_THRESHOLD_BYTES)}`);

  let updated = upsertSeries(catalog, options.series);
  if (usage > PURGE_THRESHOLD_BYTES) {
    const purge = seriesToPurge(updated, 0, options.series.id);
    for (const series of purge) {
      const removed = await client.deletePrefix(`series/${series.id}`);
      log(`purge : ${series.id} (${removed} objets, ${formatBytes(series.totalBytes)})`);
    }
    const purgedIds = new Set(purge.map((series) => series.id));
    updated = { ...updated, series: updated.series.filter((series) => !purgedIds.has(series.id)) };
  }

  return updated;
}

/**
 * Vercel Blob : verifie le poids, vide `series/`, envoie, puis ne garde au
 * catalogue que la serie publiee. L'URL rendue par le premier envoi doit
 * correspondre a celle deja ecrite dans le catalogue, sinon on s'arrete.
 */
async function publishToBlob(
  options: PublishOptions,
  catalog: Catalog,
  files: readonly string[],
  prefix: string,
  log: (message: string) => void,
): Promise<Catalog> {
  assertFitsBlob(options.series.totalBytes);
  const store = await createBlobStore(options.env);
  const expectedBase = mediaPublicBaseUrl(options.env);

  const removed = await store.deletePrefix("series/");
  if (removed > 0) log(`Vercel Blob : ${removed} objets de la serie precedente supprimes`);

  for (const [index, file] of files.entries()) {
    const key = `${prefix}/${file}`;
    const body = await fs.readFile(path.join(options.mediaDir, file));
    const url = await store.put(key, body, contentTypeFor(file));
    if (index === 0 && url !== `${expectedBase}/${key}`) {
      throw new Error(`URL Vercel Blob inattendue : ${url} (catalogue : ${expectedBase}/${key})`);
    }
  }
  log(`${files.length} fichiers publies sur Vercel Blob sous ${prefix}/`);

  const usage = await store.usageBytes();
  log(`occupation Vercel Blob : ${formatBytes(usage)} / plafond ${formatBytes(BLOB_MAX_SERIES_BYTES)}`);

  // Les autres series n'ont plus de medias : elles quittent le catalogue.
  return { ...upsertSeries(catalog, options.series), series: [options.series] };
}
