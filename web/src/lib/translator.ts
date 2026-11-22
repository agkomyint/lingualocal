"use client";
/**
 * Translates every speech piece on its own (or only writes it down, in a one-language project): one Gemini
 * request per piece with its recording as audio.
 * Pieces run in parallel (3 at a time); a failed request is retried until it works (e.g. when the internet
 * is back). Port of SegmentTranslator.kt. The web has no on-device speech recognition, so without internet
 * pieces wait.
 */
import { getGenerativeModel, Schema, ThinkingLevel, type GenerativeModel } from "firebase/ai";
import { fbNow } from "./firebase";
import { Lang, type LangPair, TH, EN, MY } from "./lang";
import { SEGMENT_MODEL, segmentPrompt } from "./config";
import { base64, wav } from "./audio";
import { app, caption, setApp, updateCaption } from "./state";
import { isEcho, say } from "./speak";
import { identify } from "./people";
import type { Turn } from "./types";

const PARALLEL = 3;
const models = new Map<string, GenerativeModel>();

function model(pair: LangPair): GenerativeModel {
  let m = models.get(pair.code);
  if (!m) {
    const translation = pair.transcribeOnly ? undefined : { translation: Schema.string({ description: "Translation into the other language" }) };
    const line = Schema.object({
      properties: {
        speaker: Schema.string({ description: "A, B, C… in order of first appearance in this recording" }),
        transcript: Schema.string({ description: "What this person said, in the original language" }),
        ...translation,
        start: Schema.number({ description: "Second in this recording where this turn starts, e.g. 0.0 or 2.4" }),
        end: Schema.number({ description: "Second in this recording where this turn ends" }),
      },
    });
    m = getGenerativeModel(fbNow().ai, {
      model: SEGMENT_MODEL,
      generationConfig: {
        temperature: 0.2,
        thinkingConfig: { thinkingLevel: ThinkingLevel.LOW },
        responseMimeType: "application/json",
        responseSchema: Schema.object({
          properties: {
            language: Schema.enumString({ enum: ["th", "en", "my", "none"], description: "Main language spoken" }),
            transcript: Schema.string({ description: "Exactly what was said, in the original language" }),
            ...translation,
            turns: Schema.array({ items: line, description: "Who said what, in order. A single turn if only one person speaks." }),
          },
        }),
      },
      systemInstruction: segmentPrompt(pair),
    });
    models.set(pair.code, m);
  }
  return m;
}

let active = 0;
const waiting: (() => void)[] = [];
async function permit<T>(fn: () => Promise<T>): Promise<T> {
  if (active >= PARALLEL) await new Promise<void>((r) => waiting.push(r));
  active++;
  try { return await fn(); } finally { active--; waiting.shift()?.(); }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

class Blocked extends Error {}

const whenBack = (pair: LangPair) =>
  pair.transcribeOnly ? "will write it down when it's back" : "will translate when it's back";

function reason(e: unknown, pair: LangPair): [string, boolean] {
  const err = e as { message?: string; code?: string; customErrorData?: { status?: number } };
  const msg = `${err?.message ?? ""} ${err?.code ?? ""} ${err?.customErrorData?.status ?? ""}`.toLowerCase();
  if (!navigator.onLine) return [`Waiting for internet · ${whenBack(pair)}`, false];
  if (msg.includes("app check") || msg.includes("appcheck") || msg.includes("403") || msg.includes("permission_denied") || msg.includes("unauthenticated"))
    return ["Blocked by Firebase App Check · check the app's App Check setup", true];
  if (msg.includes("quota") || msg.includes("429") || msg.includes("resource_exhausted")) return ["Gemini quota reached · retrying", true];
  if (msg.includes("api-not-enabled")) return ["Firebase AI Logic is turned off for this project", true];
  return [`Retrying · ${(err?.message ?? String(e)).slice(0, 60)}`, false];
}

/** Queues one piece's 16 kHz PCM16 for translation, in project pid with its pair. */
export function submit(pid: string, pair: LangPair, id: number, pcm: Uint8Array, voicedMs = pcm.length / 32) {
  setApp({ bufferedBytes: app().bufferedBytes + pcm.length });
  updateCaption(pid, id, (c) => (c.status === "DONE" || c.status === "NO_SPEECH" || c.status === "SKIPPED" ? c : { ...c, status: "QUEUED" }));
  (async () => {
    let backoff = 1000;
    try {
      for (;;) {
        if (!caption(pid, id)) return; // project deleted
        if (!navigator.onLine) {
          const note = pair.worksOffline
            ? `Waiting for internet · ${whenBack(pair)}`
            : `${Lang.name(pair.b)} needs internet · ${whenBack(pair)}`;
          updateCaption(pid, id, (c) => ({ ...c, status: "QUEUED", note }));
          await Promise.race([sleep(backoff), new Promise((r) => window.addEventListener("online", r, { once: true }))]);
          backoff = Math.min(backoff * 2, 15_000);
          continue;
        }
        try {
          const json = await permit(async () => {
            updateCaption(pid, id, (c) => ({ ...c, status: "TRANSLATING" }));
            const r = await model(pair).generateContent([
              { inlineData: { data: base64(wav(pcm)), mimeType: "audio/wav" } },
              { text: pair.transcribeOnly ? "Transcribe this piece of speech. Don't translate it." : "Transcribe and translate this piece of speech." },
            ]);
            const res = r.response;
            const fin = res.candidates?.[0]?.finishReason;
            if (res.promptFeedback?.blockReason || (fin && ["SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION"].includes(fin)))
              throw new Blocked();
            return JSON.parse(res.text() || "{}");
          });
          apply(pid, pair, id, json, pcm, voicedMs);
          return;
        } catch (e) {
          if (e instanceof Blocked) {
            updateCaption(pid, id, (c) => ({ ...c, status: "NO_SPEECH", note: "Gemini wouldn't process this piece" }));
            return;
          }
          const [note, permanent] = reason(e, pair);
          console.warn(`#${id} translate failed`, e);
          updateCaption(pid, id, (c) => ({ ...c, status: "QUEUED", note }));
          await sleep(backoff);
          backoff = Math.min(backoff * 2, permanent ? 60_000 : 15_000);
        }
      }
    } finally {
      setApp({ bufferedBytes: Math.max(0, app().bufferedBytes - pcm.length) });
    }
  })();
}

type Timed = { turn: Turn; start: number; end: number };

function apply(pid: string, pair: LangPair, id: number, json: Record<string, unknown>, pcm: Uint8Array, voicedMs: number) {
  const language = String(json.language ?? "");
  const transcript = String(json.transcript ?? "").trim();
  const only = pair.transcribeOnly;
  const translation = only ? "" : String(json.translation ?? "").trim();
  const words = language !== "none" && !!transcript && (only || !!translation);
  const timed: Timed[] = (Array.isArray(json.turns) ? json.turns : []).flatMap((o: Record<string, unknown>) => {
    const heard = String(o?.transcript ?? "").trim();
    if (!heard) return [];
    return [{
      turn: { speaker: String(o.speaker ?? "").trim() || "?", heard, translation: only ? "" : String(o.translation ?? "").trim(), lang: Lang.of(heard) },
      start: typeof o.start === "number" ? o.start : -1, end: typeof o.end === "number" ? o.end : -1,
    }];
  });
  const turns = timed.map((t) => t.turn);
  const split = new Set(turns.map((t) => t.speaker)).size > 1 ? turns : [];
  if (words && isEcho(transcript)) return markEcho(pid, id);
  updateCaption(pid, id, (c) => words
    ? {
      ...c, heard: transcript, translation, turns: split, note: null, status: "DONE",
      lang: [TH, EN, MY].includes(language) ? language : Lang.of(transcript), engine: Lang.cloudEngine(SEGMENT_MODEL),
    }
    : { ...c, heard: "", translation: "", note: null, status: "NO_SPEECH" });
  if (words) {
    identifyPeople(pid, id, pcm, voicedMs, split.length ? timed : []);
    // A transcript is never read out by itself: the mic would hear it.
    if (!only) say(translation, `seg-${id}`);
  }
}

/** Matches who spoke to the project's people: each voice's stretches on their own, else the whole piece. */
export async function identifyPeople(pid: string, id: number, pcm: Uint8Array, voicedMs: number, timed: Timed[]) {
  if (caption(pid, id)?.mine) return;
  try {
    const byVoice = new Map<string, Uint8Array[]>();
    for (const { turn, start, end } of timed) {
      if (start < 0 || end <= start) continue;
      let from = Math.floor(start * 16000) * 2;
      let to = Math.floor(end * 16000) * 2;
      if (to - from > 19_200) { from += 3200; to -= 3200; }
      from = Math.min(Math.max(from, 0), pcm.length);
      to = Math.min(Math.max(to, from), pcm.length);
      if (to > from) {
        const l = byVoice.get(turn.speaker) ?? [];
        l.push(pcm.subarray(from, to));
        byVoice.set(turn.speaker, l);
      }
    }
    const people = new Map<string, number | null>();
    for (const [k, parts] of byVoice) {
      const size = parts.reduce((s, p) => s + p.length, 0);
      const all = new Uint8Array(size);
      let o = 0;
      for (const p of parts) { all.set(p, o); o += p.length; }
      people.set(k, await identify(pid, all, size / 32));
    }
    if ([...people.values()].some((v) => v != null)) {
      updateCaption(pid, id, (c) => {
        const withPeople = c.turns.map((t) => ({ ...t, person: people.get(t.speaker) ?? null }));
        const main = withPeople.filter((t) => t.person != null).sort((a, b) => b.heard.length - a.heard.length)[0]?.person;
        return { ...c, turns: withPeople, speaker: main ?? c.speaker };
      });
    } else {
      const who = await identify(pid, pcm, voicedMs);
      if (who != null) updateCaption(pid, id, (c) => ({ ...c, speaker: who }));
    }
  } catch (e) { console.warn(`#${id} speaker match failed`, e); }
}

function markEcho(pid: string, id: number) {
  updateCaption(pid, id, (c) => ({ ...c, heard: "", translation: "", turns: [], note: "Echo of the app's voice · ignored", status: "SKIPPED" }));
}
