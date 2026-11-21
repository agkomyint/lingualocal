"use client";
/**
 * Speaking translations: the browser's voices where it has one, Gemini's voice otherwise (Burmese).
 * Port of Speaker.kt, CloudVoice.kt and TranslationVoice.kt.
 */
import { getGenerativeModel, ResponseModality } from "firebase/ai";
import { fbNow } from "./firebase";
import { Lang } from "./lang";
import { TTS_MODEL, TTS_VOICE, SPEAKER_SAMPLE_RATE } from "./config";
import { app, setApp, caption, updateCaption, useApp, toast } from "./state";
import { fromBase64, wav } from "./audio";
import { audioBlob, saveAudio, deleteAudio } from "./store";
import type { Caption } from "./types";

// ---------- Echo check: the mic hearing the app's own voice ----------

const ECHO_WINDOW_MS = 30_000;
const said: { text: string; at: number }[] = [];
const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");

/** Notes text the app is about to speak, so the mic picking it up isn't translated again. */
export function remember(text: string) {
  const now = Date.now();
  said.push({ text: norm(text), at: now });
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
  return said.some((x) => x.at >= now - ECHO_WINDOW_MS && ((h.length >= 4 && x.text.includes(h)) || dice(h, x.text) >= 0.6));
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

function speakDevice(text: string, lang: string, onEnd: () => void): SpeechSynthesisUtterance {
  const u = new SpeechSynthesisUtterance(text);
  const v = voiceFor(lang);
  if (v) u.voice = v;
  u.lang = Lang.locale(lang);
  u.rate = 1;
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
    a.onerror = done;
    a.onpause = () => { if (!a.ended) done(); };
    a.play().catch(done);
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

export const spokenText = (c: Caption) =>
  (c.turns.length ? c.turns.map((t) => t.translation).join(" ") : c.translation).trim();
export const savedVoice = (c: Caption, text: string) => (c.voiceFile && c.voiceText === text.trim() ? c.voiceFile : null);

const inFlight = new Map<string, Promise<string | null>>();
const failed = new Map<string, number>();
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
      try {
        const pcm = await recordVoice(text);
        const path = await saveAudio(pid, `segment_${id}_voice.wav`, wav(pcm, SPEAKER_SAMPLE_RATE));
        const now = caption(pid, id);
        if (!now || spokenText(now) !== text) return null;
        updateCaption(pid, id, (x) => ({ ...x, voiceFile: path, voiceText: text }));
        return path;
      } catch (e) {
        console.warn("voice of piece", id, e);
        failed.set(key, Date.now());
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
  const run = () => {
    const s = app();
    for (const pid of new Set([s.running ? s.liveId : null, s.viewId].filter(Boolean) as string[])) {
      for (const c of s.captions[pid] ?? []) {
        const k = `${pid}|${c.id}`;
        if (checked.get(k) === c || c.status !== "DONE") continue;
        if (c.time < Date.now() - CATCH_UP_WINDOW_MS) { checked.set(k, c); continue; }
        const text = spokenText(c);
        if (!text) continue;
        if (savedVoice(c, text) || hasDeviceVoice(Lang.of(text))) { checked.set(k, c); continue; }
        if ((failed.get(`${pid}|${c.id}|${text}`) ?? 0) > Date.now() - RETRY_MS) continue;
        if (!navigator.onLine) continue;
        voiceFile(pid, c.id);
      }
    }
  };
  let t: ReturnType<typeof setTimeout> | null = null;
  useApp.subscribe((s, p) => { if (s.captions !== p.captions && !t) t = setTimeout(() => { t = null; run(); }, 1500); });
  setInterval(run, RETRY_MS);
}

// ---------- Speaker: reads translations aloud while listening ----------

const queue: { text: string; id: string }[] = [];
let busy = false;
let gen = 0;

/** Speaks a translation (queued after anything already speaking; interrupt cuts it off). */
export function say(text: string, id: string, interrupt = false) {
  if (!text.trim() || !app().speakAloud) return;
  if (interrupt) stop();
  queue.push({ text, id });
  if (!busy) next();
}

function next() {
  const item = queue.shift();
  if (!item) { busy = false; setApp({ ttsSpeaking: false }); return; }
  busy = true;
  setApp({ ttsSpeaking: true });
  const g = gen;
  const done = () => { if (g === gen) next(); };
  const lang = Lang.of(item.text);
  remember(item.text);
  if (hasDeviceVoice(lang)) { speakDevice(item.text, lang, done); return; }
  const live = app().liveId;
  const n = item.id.startsWith("seg-") ? Number(item.id.slice(4)) : NaN;
  const task = live && Number.isFinite(n)
    ? voiceFile(live, n).then(async (path) => { if (path && g === gen) { const b = await audioBlob(path); if (b && g === gen) await playBlob(b); } })
    : cloudSpeak(item.text);
  task.catch((e) => console.warn("cloud voice", e)).finally(done);
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

export async function sayOnTap(text: string, key: string, onState: (playing: boolean) => void) {
  stop();
  if (!text.trim()) return;
  onState(true);
  const finish = () => onState(false);
  const pid = app().viewId;
  const id = Number(key);
  const c = pid && Number.isFinite(id) ? caption(pid, id) : undefined;
  const have = c ? savedVoice(c, text) : null;
  remember(text);
  if (have) {
    const b = await audioBlob(have);
    if (b) { await playBlob(b); finish(); return; }
  }
  const lang = Lang.of(text);
  if (hasDeviceVoice(lang)) { speakDevice(text, lang, finish); return; }
  try {
    if (pid && c) {
      const path = await voiceFile(pid, id);
      const b = path ? await audioBlob(path) : null;
      if (!b) throw new Error("no voice");
      await playBlob(b);
    } else await cloudSpeak(text);
  } catch {
    toast(`Couldn't speak ${Lang.name(lang)} (needs internet)`);
  }
  finish();
}

export async function dropVoice(c: Caption) { if (c.voiceFile) await deleteAudio(c.voiceFile); }
