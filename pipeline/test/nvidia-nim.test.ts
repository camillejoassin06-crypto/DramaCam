import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  NIM_BASE_URL,
  NIM_DEFAULT_MODEL,
  NvidiaNimProvider,
  extractNimImage,
  isPng,
  nimDimensions,
} from "../src/images/nvidia-nim.ts";

const PNG_BYTES = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

test("le format vertical 1080x1920 est ramene aux cotes acceptees par NIM", () => {
  assert.deepEqual(nimDimensions(1080, 1920), { width: 768, height: 1344 });
  assert.deepEqual(nimDimensions(1920, 1080), { width: 1344, height: 768 });
  assert.deepEqual(nimDimensions(1024, 1024), { width: 1344, height: 1344 });
});

test("les deux formats de reponse NIM sont lus", () => {
  const encoded = PNG_BYTES.toString("base64");
  assert.ok(isPng(extractNimImage({ artifacts: [{ base64: encoded, finishReason: "SUCCESS" }] })));
  assert.ok(isPng(extractNimImage({ image: `data:image/png;base64,${encoded}` })));
});

test("une image filtree ou absente echoue explicitement", () => {
  assert.throws(
    () => extractNimImage({ artifacts: [{ base64: "", finishReason: "CONTENT_FILTERED" }] }),
    /CONTENT_FILTERED/,
  );
  assert.throws(() => extractNimImage({}), /sans image/);
});

test("sans cle, le fournisseur se declare indisponible", async () => {
  assert.equal(await new NvidiaNimProvider("").isAvailable(), false);
  assert.equal(await new NvidiaNimProvider("nvapi-test").isAvailable(), true);
});

test("la requete vise le modele NIM et reprend apres un 429", async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const responses = [
    new Response("trop de requetes", { status: 429, headers: { "retry-after": "0" } }),
    Response.json({ artifacts: [{ base64: PNG_BYTES.toString("base64"), finishReason: "SUCCESS" }] }),
  ];
  const fakeFetch = (async (url: string, init: RequestInit) => {
    calls.push({ url, init });
    return responses.shift()!;
  }) as typeof fetch;

  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "nim-"));
  const outPath = path.join(dir, "shot-001.png");
  const provider = new NvidiaNimProvider("nvapi-test", NIM_DEFAULT_MODEL, fakeFetch, async () => {});
  await provider.generate({ prompt: "quai de gare la nuit", outPath, width: 1080, height: 1920, seed: 42 });

  assert.equal(calls.length, 2);
  assert.equal(calls[0]!.url, `${NIM_BASE_URL}/${NIM_DEFAULT_MODEL}`);
  const headers = calls[0]!.init.headers as Record<string, string>;
  assert.equal(headers.authorization, "Bearer nvapi-test");
  const body = JSON.parse(String(calls[0]!.init.body)) as Record<string, unknown>;
  assert.equal(body.seed, 42);
  assert.equal(body.width, 768);
  assert.equal(body.height, 1344);
  assert.ok(isPng(await fs.readFile(outPath)));
  await fs.rm(dir, { recursive: true, force: true });
});

test("un modele qui refuse seed est rappele sans, une seule fois pour toutes", async () => {
  const bodies: Record<string, unknown>[] = [];
  const rejection =
    '{"detail":[{"type":"extra_forbidden","loc":["body","seed"],"msg":"Extra inputs are not permitted","input":42}]}';
  const fakeFetch = (async (_url: string, init: RequestInit) => {
    const body = JSON.parse(String(init.body)) as Record<string, unknown>;
    bodies.push(body);
    if ("seed" in body) return new Response(rejection, { status: 422 });
    return Response.json({ artifacts: [{ base64: PNG_BYTES.toString("base64"), finishReason: "SUCCESS" }] });
  }) as typeof fetch;

  const dir = await fs.mkdtemp(path.join(os.tmpdir(), "nim-"));
  const provider = new NvidiaNimProvider("nvapi-test", NIM_DEFAULT_MODEL, fakeFetch, async () => {});
  const shot = (n: number) => ({ prompt: "x", outPath: path.join(dir, `s${n}.png`), width: 1080, height: 1920, seed: n });
  await provider.generate(shot(1));
  await provider.generate(shot(2));

  assert.deepEqual(bodies.map((body) => "seed" in body), [true, false, false]);
  await fs.rm(dir, { recursive: true, force: true });
});

test("une erreur non transitoire n'est pas reessayee", async () => {
  let count = 0;
  const fakeFetch = (async () => {
    count += 1;
    return new Response("cle invalide", { status: 401 });
  }) as typeof fetch;
  const provider = new NvidiaNimProvider("nvapi-test", NIM_DEFAULT_MODEL, fakeFetch, async () => {});
  await assert.rejects(
    provider.generate({ prompt: "x", outPath: path.join(os.tmpdir(), "nim-x.png"), width: 1080, height: 1920, seed: 1 }),
    /NVIDIA NIM 401/,
  );
  assert.equal(count, 1);
});
