"use client";
/**
 * Speaking translations: the voice picked for each language, the browser's or Gemini's. Gemini also speaks
 * the languages the browser has no voice for, and the browser steps in when Gemini can't be reached.
 * In a one-language project every piece, typed or spoken, gets Gemini's voice and is saved by itself.
 * Port of Speaker.kt, CloudVoice.kt and TranslationVoice.kt.
 */
import { getGenerativeModel, ResponseModality } from "firebase/ai";
import { fbNow } from "./firebase";
import { Lang } from "./lang";
import { TTS_MODEL, TTS_VOICE, SPEAKER_SAMPLE_RATE } from "./config";
import { app, setApp, caption, pairFor, updateCaption, useApp, toast } from "./state";
import { fromBase64, wav } from "./audio";
import { audioBlob, saveAudio, deleteAudio } from "./store";
import { SOURCE_TYPED, textOnly, type Caption } from "./types";

// ---------- Echo check: the mic hearing the app's own voice ----------

const ECHO_WINDOW_MS = 30_000;
const said: { text: string; at: number }[] = [];
const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");

/** Notes text the app is about to speak, so the mic picking it up isn't translated again. */
export function remember(text: string) {
  const now = Date.now();
  said.push({ text, at: now });
  while (said.length && said[0].at < now - ECHO_WINDOW_MS) said.shift();
}

function bigrams(s: string) {
  const m = new Map<string, number>();
  for (let i = 0; i < s.length - 1; i++) { const b = s.slice(i, i + 2); m.set(b, (m.get(b) ?? 0) + 1); }
  return m;
}
function dice(a: string, b: string) {
  if (a.length < 2 || b.length < 2) return 0;
  const x = bigrams(a), y = bigrams(b);
  let both = 0;
  x.forEach((n, k) => { both += Math.min(n, y.get(k) ?? 0); });
  return (2 * both) / (a.length - 1 + b.length - 1);
}
export function looksLikeEcho(heard: string, saidText: string) {
  const h = norm(heard), s = norm(saidText);
  if (!h || !s) return false;
  return (h.length >= 4 && s.includes(h)) || dice(h, s) >= 0.6;
}
/** Whether heard is the app's own voice from the last 30 s. */
export function isEcho(heard: string) {
  const now = Date.now();
  const h = norm(heard);
  if (!h) return false;
  return said.some((x) => {
    const s = norm(x.text);
    return x.at >= now - ECHO_WINDOW_MS && ((h.length >= 4 && s.includes(h)) || dice(h, s) >= 0.6);
  });
}
/** What the app said in the last 30 s. */
export function recentlySaid(): string[] {
  const since = Date.now() - ECHO_WINDOW_MS;
  return said.filter((x) => x.at >= since).map((x) => x.text);
}

// ---------- Browser voices ----------

function voiceFor(lang: string): SpeechSynthesisVoice | null {
  if (typeof speechSynthesis === "undefined") return null;
  const want = Lang.locale(lang).slice(0, 2);
  const vs = speechSynthesis.getVoices().filter((v) => v.lang.toLowerCase().startsWith(want));
  return vs.find((v) => v.localService) ?? vs[0] ?? null;
}
export const hasDeviceVoice = (lang: string) => voiceFor(lang) != null;
if (typeof speechSynthesis !== "undefined") speechSynthesis.getVoices();

export function speakDevice(text: string, lang: string, onEnd: () => void, rate = 1): SpeechSynthesisUtterance {
  const u = new SpeechSynthesisUtterance(text);
  const v = voiceFor(lang);
  if (v) u.voice = v;
  u.lang = Lang.locale(lang);
  u.rate = rate;
  u.onend = onEnd;
  u.onerror = onEnd;
  speechSynthesis.speak(u);
  return u;
}

// ---------- Gemini's voice ----------

let ttsModel: ReturnType<typeof getGenerativeModel> | null = null;
const tts = () => (ttsModel ??= getGenerativeModel(fbNow().ai, {
  model: TTS_MODEL,
  generationConfig: { responseModalities: [ResponseModality.AUDIO], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: TTS_VOICE } } } },
}));

const withTimeout = <T,>(p: Promise<T>, ms: number) =>
  Promise.race([p, new Promise<T>((_, rej) => setTimeout(() => rej(new Error("timed out")), ms))]);

/** Gemini reads text into 16-bit mono PCM at 24 kHz (needs internet). Only the text: it reads out everything it's sent. */
export async function recordVoice(text: string): Promise<Uint8Array> {
  const r = await withTimeout(tts().generateContent(text), 60_000);
  const parts = (r.response.candidates ?? []).flatMap((c) => c.content.parts.map((p) => ("inlineData" in p ? p.inlineData?.data : undefined)).filter(Boolean) as string[]);
  const chunks = parts.map(fromBase64);
  const size = chunks.reduce((s, c) => s + c.length, 0);
  if (!size) throw new Error("no audio came back");
  const out = new Uint8Array(size);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return wavData(out) ?? out;
}

const tag = (b: Uint8Array, at: number) => String.fromCharCode(b[at], b[at + 1], b[at + 2], b[at + 3]);

/**
 * The samples of a WAV file, or null if the bytes aren't one. Gemini's voice now comes as a whole WAV file
 * with a signed Content Credentials (C2PA) block after the audio; played as raw samples, the header clicks
 * and that block buzzes at the end.
 */
export function wavData(b: Uint8Array): Uint8Array | null {
  if (b.length < 12 || tag(b, 0) !== "RIFF" || tag(b, 8) !== "WAVE") return null;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  for (let pos = 12; pos + 8 <= b.length;) {
    const size = dv.getUint32(pos + 4, true);
    const body = pos + 8;
    if (tag(b, pos) === "data") return b.subarray(body, size && body + size <= b.length ? body + size : b.length);
    pos = body + size + (size & 1);
  }
  return null;
}

/** Voice clips saved before the fix hold Gemini's whole WAV file inside ours; keep only its samples. */
async function cleanVoice(blob: Blob): Promise<Blob> {
  if (blob.size < 56) return blob;
  const head = new Uint8Array(await blob.slice(0, 56).arrayBuffer());
  if (tag(head, 0) !== "RIFF" || tag(head, 44) !== "RIFF") return blob;
  const inner = wavData(new Uint8Array(await blob.arrayBuffer()).subarray(44));
  return inner ? new Blob([wav(inner, SPEAKER_SAMPLE_RATE) as BlobPart], { type: "audio/wav" }) : blob;
}

let player: HTMLAudioElement | null = null;
let playUrl: string | null = null;
let playGen = 0;

/** Plays a recording to the end (or until stopped). */
export async function playBlob(blob: Blob, rate = 1): Promise<void> {
  stopPlayer();
  const gen = ++playGen;
  const clean = await cleanVoice(blob);
  if (gen !== playGen) return;
  return new Promise((resolve) => {
    const url = URL.createObjectURL(clean);
    const a = new Audio(url);
    player = a; playUrl = url;
    a.playbackRate = rate;
    const done = () => { if (playGen === gen) stopPlayer(); resolve(); };
    a.onended = done;
    const broken = (e?: unknown) => {
      // Not an error when it was stopped or replaced.
      const name = (e as DOMException | undefined)?.name;
      if (playGen === gen && name !== "AbortError")
        toast(name === "NotAllowedError" ? "The browser blocked the sound · tap play again" : "Couldn't play this sound");
      done();
    };
    a.onerror = () => broken();
    a.onpause = () => { if (!a.ended) done(); };
    a.play().catch(broken);
  });
}
export function currentPlayer() { return player; }
export function stopPlayer() {
  playGen++;
  if (player) { player.onpause = null; player.pause(); player = null; }
  if (playUrl) { URL.revokeObjectURL(playUrl); playUrl = null; }
}

/** Speaks text with Gemini's voice. */
async function cloudSpeak(text: string) {
  const pcm = await recordVoice(text);
  await playBlob(new Blob([wav(pcm, SPEAKER_SAMPLE_RATE) as BlobPart], { type: "audio/wav" }));
}

// ---------- Saved voices of pieces (TranslationVoice) ----------

/** What reading a piece aloud says: its translation, or its own words when it was only written down. */
export const spokenText = (c: Caption) => {
  const own = textOnly(c);
  return (c.turns.length ? c.turns.map((t) => (own ? t.heard : t.translation)).join(" ") : own ? c.heard : c.translation).trim();
};
export const savedVoice = (c: Caption, text: string) => (c.voiceFile && c.voiceText === text.trim() ? c.voiceFile : null);

/** A piece of a one-language project: always Gemini's voice, even where the browser has one, so it can be saved. */
export const alwaysGemini = (pid: string, c: Caption) => textOnly(c) && pairFor(pid).transcribeOnly;

/** Gemini's voice is wanted for this piece: the setting of the language being spoken. */
export const wantsGemini = (pid: string | null | undefined, c: Caption | undefined, text: string) =>
  (!!pid && !!c && alwaysGemini(pid, c)) || app().voices[Lang.of(text)]?.engine === "gemini";

/**
 * Gemini's voice is made by itself: always in a one-language project, else for pieces since Gemini was picked.
 * Untranslated speech in a translation project isn't paid for.
 */
export function autoGemini(pid: string, c: Caption, text: string) {
  if (alwaysGemini(pid, c)) return true;
  if (c.source !== SOURCE_TYPED && textOnly(c)) return false;
  const v = app().voices[Lang.of(text)];
  return v?.engine === "gemini" && c.time >= v.since;
}

const inFlight = new Map<string, Promise<string | null>>();
/** Why a piece's voice last failed, to show instead of guessing. */
const problems = new Map<string, string>();

function whyFailed(e: unknown): string {
  const raw = String((e as Error)?.message ?? e);
  const m = raw.toLowerCase();
  if (!navigator.onLine) return "No internet for Gemini's voice";
  if (/app ?check|\[40[13]|permission_denied|unauthenticated/.test(m)) return "Gemini voice blocked (App Check or permission)";
  if (/quota|\[429|resource_exhausted/.test(m)) return "Gemini voice quota reached";
  if (m.includes("timed out")) return "Gemini voice timed out";
  if (m.includes("no audio came back")) return "Gemini sent no audio";
  return `Gemini voice failed · ${raw.replace(/^.*Error fetching from \S+: /, "").slice(0, 70)}`;
}

/** What to tell the user when a piece's voice can't be played. */
export const voiceProblem = (pid: string | null | undefined, id: number) =>
  problems.get(`${pid}|${id}`) ?? "Couldn't load this voice";
const failed = new Map<string, number>();
const tries = new Map<string, number>();
/** Voices made but not saved yet: saving is retried without paying again. */
const unsaved = new Map<string, Uint8Array>();
const MAX_LATE_TRIES = 5;
const RETRY_MS = 120_000;
const CATCH_UP_WINDOW_MS = 10 * 60_000;

/** The saved Gemini voice of a piece (made once, kept in Storage). */
export function voiceFile(pid: string, id: number): Promise<string | null> {
  const c = caption(pid, id);
  if (!c) return Promise.resolve(null);
  const text = spokenText(c);
  if (!text) return Promise.resolve(null);
  const have = savedVoice(c, text);
  if (have) return Promise.resolve(have);
  const key = `${pid}|${id}|${text}`;
  let job = inFlight.get(key);
  if (!job) {
    job = (async () => {
      let saving = false;
      problems.delete(`${pid}|${id}`);
      try {
        const pcm = unsaved.get(key) ?? await recordVoice(text);
        unsaved.set(key, pcm);
        saving = true;
        const path = await saveAudio(pid, `segment_${id}_voice.wav`, wav(pcm, SPEAKER_SAMPLE_RATE));
        unsaved.delete(key);
        const now = caption(pid, id);
        if (!now || spokenText(now) !== text) return null;
        updateCaption(pid, id, (x) => ({ ...x, voiceFile: path, voiceText: text }));
        return path;
      } catch (e) {
        console.warn("voice of piece", id, e);
        failed.set(key, Date.now());
        tries.set(key, (tries.get(key) ?? 0) + 1);
        const why = saving ? "Couldn't save the voice in this browser" : whyFailed(e);
        problems.set(`${pid}|${id}`, why);
        // These pieces count on the saved voice, so a failure mustn't be silent.
        if (app().viewId === pid && alwaysGemini(pid, c)) toast(why);
        return null;
      } finally { inFlight.delete(key); }
    })();
    inFlight.set(key, job);
  }
  return job;
}

/** Makes the voices of recent finished pieces ahead of time, for languages the browser can't speak. */
export function startVoiceCatchUp() {
  const checked = new Map<string, Caption>();
  // Pieces seen unfinished or recent: Gemini's voice is made when it can be, however late that is.
  const due = new Set<string>();
  const run = () => {
    const s = app();
    const active = [s.running ? s.liveId : null, s.viewId];
    // Gemini's voices keep being saved after a project is left.
    for (const pid of Object.keys(s.captions)) {
      for (const c of s.captions[pid] ?? []) {
        const k = `${pid}|${c.id}`;
        if (checked.get(k) === c) continue;
        const recent = c.time >= Date.now() - CATCH_UP_WINDOW_MS;
        if (recent || c.status !== "DONE") due.add(k);
        if (c.status !== "DONE") continue;
        const text = spokenText(c);
        const auto = autoGemini(pid, c, text);
        if (!recent && !(auto && due.has(k) && (tries.get(`${k}|${text}`) ?? 0) < MAX_LATE_TRIES)) { checked.set(k, c); continue; }
        if (!auto && !active.includes(pid)) continue;
        // Untranslated speech in a translation project isn't paid for.
        if (!auto && c.source !== SOURCE_TYPED && textOnly(c)) continue;
        if (!text) continue;
        if (savedVoice(c, text) || (!auto && hasDeviceVoice(Lang.of(text)))) { checked.set(k, c); continue; }
        if ((failed.get(`${pid}|${c.id}|${text}`) ?? 0) > Date.now() - RETRY_MS) continue;
        if (!navigator.onLine) continue;
        voiceFile(pid, c.id);
      }
    }
  };
  let t: ReturnType<typeof setTimeout> | null = null;
  useApp.subscribe((s, p) => {
    if (s.voices !== p.voices) checked.clear();
    if ((s.captions !== p.captions || s.voices !== p.voices) && !t) t = setTimeout(() => { t = null; run(); }, 1500);
  });
  setInterval(run, RETRY_MS);
  if (typeof window !== "undefined") window.addEventListener("online", run);
}

// ---------- Speaker: reads translations aloud while listening ----------

/** How long a live translation waits for Gemini before the browser's voice reads it. */
const GEMINI_WAIT_MS = 20_000;

interface Item { text: string; pid: string | null; n: number; voice: Promise<string | null> | null }
const queue: Item[] = [];
let busy = false;
let gen = 0;

/** Speaks a translation (queued after anything already speaking; interrupt cuts it off). */
export function say(text: string, id: string, interrupt = false, pid: string | null = app().liveId) {
  if (!text.trim() || !app().speakAloud) return;
  if (interrupt) stop();
  const n = id.startsWith("seg-") ? Number(id.slice(4)) : NaN;
  const c = pid && Number.isFinite(n) ? caption(pid, n) : undefined;
  // Asked for straight away, so it's ready by its turn. Offline, the browser's voice speaks instead.
  const voice = pid && c && wantsGemini(pid, c, text) && (navigator.onLine || savedVoice(c, spokenText(c)))
    ? voiceFile(pid, n) : null;
  queue.push({ text, pid, n, voice });
  if (!busy) next();
}

function next() {
  const item = queue.shift();
  if (!item) { busy = false; setApp({ ttsSpeaking: false }); return; }
  busy = true;
  const g = gen;
  const done = () => { if (g === gen) next(); };
  speakItem(item, g).catch((e) => console.warn("voice", e)).finally(done);
}

async function speakItem({ text, pid, n, voice }: Item, g: number) {
  const lang = Lang.of(text);
  const device = hasDeviceVoice(lang);
  // Noted as the sound starts: Gemini's can come seconds later.
  const starting = () => { remember(text); setApp({ ttsSpeaking: true }); };
  // Gemini was picked, or is the only voice for this language.
  const made = voice ?? (!device && pid && Number.isFinite(n) ? voiceFile(pid, n) : null);
  if (made) {
    setApp({ ttsSpeaking: false });
    const path = await (device ? withTimeout(made, GEMINI_WAIT_MS).catch(() => null) : made);
    const b = path && g === gen ? await audioBlob(path).catch(() => null) : null;
    if (g !== gen) return;
    if (b) { starting(); await playBlob(b); return; }
  }
  if (device) {
    starting();
    await new Promise<void>((resolve) => { speakDevice(text, lang, resolve); });
  } else if (!made) {
    starting();
    await cloudSpeak(text);
  }
}

/** Stops speaking and drops what was waiting. */
export function stop() {
  gen++;
  queue.length = 0;
  busy = false;
  if (typeof speechSynthesis !== "undefined") speechSynthesis.cancel();
  stopPlayer();
  setApp({ ttsSpeaking: false });
}

// ---------- Saying one piece on tap (PlayAll.say) ----------

export async function sayOnTap(text: string, key: string, onState: (playing: boolean) => void, pid = app().viewId) {
  stop();
  if (!text.trim()) return;
  const g = gen;
  onState(true);
  const finish = () => onState(false);
  const id = Number(key);
  const c = pid && Number.isFinite(id) ? caption(pid, id) : undefined;
  const have = c ? savedVoice(c, text) : null;
  const wants = wantsGemini(pid, c, text);
  const lang = Lang.of(text);
  const device = !(pid && c && alwaysGemini(pid, c)) && hasDeviceVoice(lang);
  remember(text);
  if (have && (wants || !device)) {
    const b = await audioBlob(have);
    if (g !== gen) { finish(); return; }
    if (b) { await playBlob(b); finish(); return; }
  }
  if (device && (!wants || !navigator.onLine)) {
    if (wants) toast("No internet · using the device's voice");
    speakDevice(text, lang, finish);
    return;
  }
  try {
    if (pid && c) {
      const path = await voiceFile(pid, id);
      const b = path ? await audioBlob(path) : null;
      if (g !== gen) { finish(); return; }
      if (b) { remember(text); await playBlob(b); finish(); return; }
      toast(voiceProblem(pid, id));
    } else { await cloudSpeak(text); finish(); return; }
  } catch (e) {
    toast(whyFailed(e));
  }
  if (device && g === gen) { remember(text); speakDevice(text, lang, finish); return; }
  finish();
}

export async function dropVoice(c: Caption) { if (c.voiceFile) await deleteAudio(c.voiceFile); }
