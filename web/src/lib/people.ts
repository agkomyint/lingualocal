"use client";
/**
 * The people heard in each project: voice fingerprints (CAM++ in the browser, features from the Rust core)
 * and the names the user gave them, saved in Firestore (meta/people). Port of People.kt + SpeakerId.kt.
 */
import { getDoc, setDoc } from "firebase/firestore";
import { onnx, type InferenceSession } from "./onnx";
import { speaker_features } from "./audio";
import { metaDoc } from "./store";
import { app, setApp } from "./state";

const MIN_VOICED_MS = 700;
const NEW_VOICE_MS = 1200;
const MATCH = 0.55;
const LEARN = 0.62;
const MAX_LEARN = 40;
const MAX_VOICES = 12;
const MIN_FRAMES = 50;
const BINS = 80;

interface Voice { number: number; sum: number[]; count: number }
interface Data { voices: Voice[]; names: Record<number, string>; loaded: Promise<void> }

const data = new Map<string, Data>();
let session: Promise<InferenceSession> | null = null;
let lock: Promise<unknown> = Promise.resolve();

/** The fingerprint model, loaded once. */
export function model(): Promise<InferenceSession> {
  session ??= onnx().InferenceSession.create("/models/speaker_campplus.onnx", { executionProviders: ["wasm"] });
  session.catch(() => { session = null; });
  return session;
}

function dataOf(pid: string): Data {
  let d = data.get(pid);
  if (!d) {
    const nd: Data = { voices: [], names: {}, loaded: Promise.resolve() };
    nd.loaded = getDoc(metaDoc(pid, "people")).then((s) => {
      const people = (s.data()?.people ?? []) as { n: number; name?: string; sum?: number[]; count?: number }[];
      for (const o of people) {
        if (o.name?.trim()) nd.names[o.n] = o.name;
        if (o.sum?.length) nd.voices.push({ number: o.n, sum: o.sum.slice(), count: o.count ?? 1 });
      }
      publish(pid);
    }).catch(() => {});
    data.set(pid, nd);
    d = nd;
  }
  return d;
}

function publish(pid: string) {
  const d = data.get(pid);
  if (d) setApp({ names: { ...app().names, [pid]: { ...d.names } } });
}

/** Loads a project's names (for the cards). */
export function watchPeople(pid: string) { dataOf(pid); }

export const label = (n: number, names: Record<number, string> = {}) => names[n]?.trim() || `Person ${n}`;

function save(pid: string) {
  const d = data.get(pid);
  if (!d) return;
  const nums = [...new Set([...d.voices.map((v) => v.number), ...Object.keys(d.names).map(Number)])].sort((a, b) => a - b);
  const people = nums.map((n) => {
    const o: Record<string, unknown> = { n };
    if (d.names[n]) o.name = d.names[n];
    const v = d.voices.find((x) => x.number === n);
    if (v) { o.count = v.count; o.sum = v.sum; }
    return o;
  });
  setDoc(metaDoc(pid, "people"), { people }).catch(() => {});
}

export function rename(pid: string, n: number, name: string) {
  const d = dataOf(pid);
  d.names[n] = name.trim();
  publish(pid);
  save(pid);
}

function cosine(a: Float32Array, b: number[]) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
}

async function embed(pcm: Uint8Array): Promise<Float32Array | null> {
  const feats = speaker_features(pcm);
  const frames = feats.length / BINS;
  if (frames < MIN_FRAMES) return null;
  const ort = onnx();
  const s = await model();
  const out = await s.run({ [s.inputNames[0]]: new ort.Tensor("float32", feats, [1, frames, BINS]) });
  return new Float32Array(out[s.outputNames[0]].data as Float32Array);
}

/**
 * Person number for 16 kHz PCM16 speech with voicedMs of voice in project pid; a clearly new voice becomes
 * a new person. Null when too short to be sure.
 */
export function identify(pid: string, pcm: Uint8Array, voicedMs: number): Promise<number | null> {
  const run = lock.then(async () => {
    if (voicedMs < MIN_VOICED_MS) return null;
    const d = dataOf(pid);
    await d.loaded;
    const emb = await embed(pcm).catch((e) => { console.warn("speaker model", e); return null; });
    if (!emb) return null;
    let best: Voice | null = null;
    let bestSim = -1;
    for (const v of d.voices) {
      const s = cosine(emb, v.sum);
      if (s > bestSim) { bestSim = s; best = v; }
    }
    let result: number | null = null;
    if (best && bestSim >= MATCH) {
      if (bestSim >= LEARN && best.count < MAX_LEARN) {
        for (let i = 0; i < emb.length; i++) best.sum[i] += emb[i];
        best.count++;
      }
      result = best.number;
    } else if (voicedMs >= NEW_VOICE_MS && d.voices.length < MAX_VOICES) {
      const n = d.voices.reduce((m, v) => Math.max(m, v.number), 0) + 1;
      d.voices.push({ number: n, sum: Array.from(emb), count: 1 });
      result = n;
    }
    save(pid);
    return result;
  });
  lock = run.catch(() => {});
  return run;
}
