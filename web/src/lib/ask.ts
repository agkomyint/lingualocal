"use client";
/**
 * "Ask": a chat about the open project on cloud Gemini, with tools the model calls itself (read the transcript,
 * listen to recordings again, correct pieces, search the web). Chats are saved per project in Firestore
 * (users/{uid}/projects/{pid}/ask/{threadId}). Port of AskAssistant.kt.
 */
import { create } from "zustand";
import { getGenerativeModel, Schema, type ChatSession, type Content, type FunctionCall, type FunctionResponsePart } from "firebase/ai";
import { doc, getDocs, setDoc } from "firebase/firestore";
import { fbNow } from "./firebase";
import { CLOUD_TEXT_MODEL } from "./config";
import { Lang, TH, EN, MY } from "./lang";
import { app, captionsOf, caption, pairFor, updateCaption } from "./state";
import { askCol, audioBlob } from "./store";
import { base64 } from "./audio";
import { USER_UNDO, correctionOf, searchText, type Caption, type Revision } from "./types";

export type Role = "USER" | "AI";
/** source: which model wrote an answer, e.g. "cloud · gemini…" or "on-device · Gemini Nano". */
export interface Msg { role: Role; text: string; steps?: string[]; error?: boolean; source?: string }
/** Cloud Gemini (tools, listening, web) or Gemini Nano in Chrome (reads the text only, works offline). */
export type Engine = "cloud" | "nano";
export interface Thread { id: number; title: string; updated: number; messages: Msg[] }

interface AskState {
  messages: Msg[];
  threads: Thread[];
  currentThread: number | null;
  busy: boolean;
  activity: string;
  liveSteps: string[];
  engine: Engine;
}
export const useAsk = create<AskState>(() => ({ messages: [], threads: [], currentThread: null, busy: false, activity: "", liveSteps: [], engine: "cloud" }));
const st = () => useAsk.getState();
const set = (p: Partial<AskState>) => useAsk.setState(p);

const MAX_ROUNDS = 6;
const MAX_PIECES = 300;
const MAX_HITS = 40;
const MAX_TEXT = 400;
const TITLE_MAX = 60;

const SYSTEM = `You are the assistant inside LinguaLocal, a live interpreter used by people living in Thailand. Each project
is either Thai ⇄ English or Thai ⇄ Burmese (read_transcript says which). Write Burmese in Myanmar Unicode script.
The user asks about a conversation the app recorded and translated (the open project).
Use read_transcript to see it; in long meetings, find earlier parts with search_transcript and read_pieces
instead of guessing. When tone, intent, or the exact words matter, use listen_to_recording on the
relevant pieces; transcripts and translations can be wrong. Use web_search for outside facts.
Answer in the language the user writes in, short and practical. Refer to pieces by their time (e.g. 14:05:12).
When suggesting what to say, give the Thai with a romanised reading and the English meaning.
Never invent what was said: if the recordings don't show it, say so.
The first transcript comes from a faster model and can be wrong. When listening shows a piece's transcript or
translation is clearly wrong and you are confident, call correct_piece to fix it, then tell the user plainly
which piece was wrong, what it said before, and what you changed it to. If you're unsure, only report it.`;

const tools = [{
  functionDeclarations: [
    {
      name: "read_transcript",
      description: "Returns the recorded pieces of the open project in order: id, time, session number, speaker, " +
        "spoken language, what was heard, the translation, length and whether audio is kept. Call this first. " +
        "In a long project it returns an outline of the sessions (times and piece ids) and only the latest pieces; " +
        "then use search_transcript and read_pieces for earlier parts.",
    },
    {
      name: "search_transcript",
      description: "Searches every piece of the open project (any length, all sessions) for words in what was heard or in the " +
        "translation. Returns the matching pieces with id and time, best matches first. Search in the language " +
        "the words were spoken or translated into (e.g. Thai and English words), several words at once.",
      parameters: Schema.object({ properties: { query: Schema.string({ description: "Words to look for, separated by spaces." }) } }),
    },
    {
      name: "read_pieces",
      description: `Returns the pieces with ids from from_id to to_id (at most ${MAX_PIECES}), e.g. a part of a long meeting ` +
        "found with the outline or around a search match.",
      parameters: Schema.object({ properties: { from_id: Schema.integer({ description: "First piece id." }), to_id: Schema.integer({ description: "Last piece id." }) } }),
    },
    {
      name: "listen_to_recording",
      description: "Call it for several pieces in the same turn when needed; they are listened to in parallel. Plays the original audio of one piece to a speech model and answers a question about it: tone of voice, " +
        "emotion, politeness, how many people speak and who says what, words the transcript may have missed, " +
        "or a corrected transcript and translation. Use it whenever the answer depends on how something was said.",
      parameters: Schema.object({ properties: { id: Schema.integer({ description: "The piece id from read_transcript." }), question: Schema.string({ description: "What to listen for, in English." }) } }),
    },
    {
      name: "correct_piece",
      description: "Replaces a piece's transcript and translation with the corrected version, after you listened to it with " +
        "listen_to_recording and are confident the original was wrong (missed words, wrong language, wrong meaning). " +
        "Don't use it for style or tiny wording preferences. The old text is kept so the user can undo it.",
      parameters: Schema.object({
        properties: {
          id: Schema.integer({ description: "The piece id." }),
          heard: Schema.string({ description: "The corrected transcript, in the language actually spoken (Thai script for Thai, Myanmar Unicode for Burmese)." }),
          translation: Schema.string({ description: "The corrected translation into the other language of the project's pair." }),
          language: Schema.enumString({ enum: ["th", "en", "my"], description: "Language actually spoken in the piece (my = Burmese)." }),
          reason: Schema.string({ description: "Short reason shown to the user, e.g. 'missed the negative ไม่ (don't)'." }),
          speaker: Schema.string({ description: "Only for pieces with turns: the speaker letter (A, B…) whose turn to correct." }),
        },
        optionalProperties: ["speaker"],
      }),
    },
    {
      name: "web_search",
      description: "Searches the web with Google for background: Thai customs, places, laws, prices, slang, names mentioned.",
      parameters: Schema.object({ properties: { query: Schema.string({ description: "The search query." }) } }),
    },
  ],
}];

let mainModel: ReturnType<typeof getGenerativeModel> | null = null;
let listenModel: ReturnType<typeof getGenerativeModel> | null = null;
let searchModel: ReturnType<typeof getGenerativeModel> | null = null;
const model = () => (mainModel ??= getGenerativeModel(fbNow().ai, { model: CLOUD_TEXT_MODEL, tools, systemInstruction: SYSTEM }));
const listener = () => (listenModel ??= getGenerativeModel(fbNow().ai, { model: CLOUD_TEXT_MODEL }));
const searcher = () => (searchModel ??= getGenerativeModel(fbNow().ai, { model: CLOUD_TEXT_MODEL, tools: [{ googleSearch: {} }] }));

let chat: ChatSession | null = null;
let projectId: string | null = null;
let generation = 0;
const heardIds = new Set<number>();

const pad = (n: number) => String(n).padStart(2, "0");
const clock = (t: number) => { const d = new Date(t); return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`; };
const day = (t: number) => { const d = new Date(t); return `${d.getDate()} ${d.toLocaleString("en-GB", { month: "short" })} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };

/** Each project has its own chats; opening a project loads them and reopens the latest one. */
export async function forProject(id: string | null) {
  if (id === projectId) return;
  projectId = id;
  set({ threads: [] });
  show(null);
  if (!id) return;
  try {
    const snap = await getDocs(askCol(id));
    if (projectId !== id) return;
    const threads = snap.docs.map((d) => d.data() as Thread).sort((a, b) => b.updated - a.updated);
    set({ threads });
    if (!st().busy && !st().messages.length) show(threads[0] ?? null);
  } catch (e) { console.warn("couldn't read Ask chats", e); }
}

export function newChat() { show(null); }
export function setEngine(engine: Engine) { if (!st().busy) set({ engine }); }
/** Nano only when chosen and Chrome still has it ready; otherwise cloud. */
export const usingNano = () => st().engine === "nano" && app().nanoReady;
export function openThread(threadId: number) {
  if (st().busy || threadId === st().currentThread) return;
  const t = st().threads.find((x) => x.id === threadId);
  if (t) show(t);
}

function show(t: Thread | null) {
  generation++;
  chat = null;
  heardIds.clear();
  set({ currentThread: t?.id ?? null, messages: t?.messages ?? [], activity: "" });
}

function persist() {
  const pid = projectId;
  const tid = st().currentThread;
  const msgs = st().messages;
  if (!pid || tid == null || !msgs.length) return;
  const title = st().threads.find((x) => x.id === tid)?.title
    ?? (msgs.find((m) => m.role === "USER")?.text.split("\n")[0] ?? "").slice(0, TITLE_MAX);
  const t: Thread = { id: tid, title, updated: Date.now(), messages: msgs };
  set({ threads: [t, ...st().threads.filter((x) => x.id !== tid)] });
  setDoc(doc(askCol(pid), String(tid)), JSON.parse(JSON.stringify(t))).catch((e) => console.warn("couldn't save Ask chat", e));
}

function reply(gen: number, msg: Msg) {
  if (gen !== generation) return;
  set({ messages: [...st().messages, msg] });
  persist();
}

function history(msgs: Msg[]): Content[] {
  const out: Content[] = [];
  msgs.forEach((m, i) => {
    const next = msgs[i + 1];
    if (m.role === "USER" && next && next.role === "AI" && !next.error) {
      out.push({ role: "user", parts: [{ text: m.text }] });
      out.push({ role: "model", parts: [{ text: next.text }] });
    }
  });
  return out;
}

const pieces = () => (projectId ? captionsOf(projectId) : []).filter((c) => c.status !== "RECORDING");

export async function ask(question: string) {
  const q = question.trim();
  if (!q || st().busy) return;
  const gen = generation;
  const before = st().messages;
  if (st().currentThread == null) set({ currentThread: Date.now() });
  set({ messages: [...st().messages, { role: "USER", text: q }] });
  persist();
  if (usingNano()) return askNano(gen, q, before);
  if (!navigator.onLine) {
    reply(gen, { role: "AI", text: "Ask needs the internet. Your recordings are safe; ask again when you're back online.", error: true });
    return;
  }
  set({ busy: true, liveSteps: [] });
  const steps: string[] = [];
  const addStep = (s: string) => { steps.push(s); set({ liveSteps: [...steps] }); };
  try {
    const c = chat ?? (chat = model().startChat({ history: history(before) }));
    set({ activity: "Thinking…" });
    let response = (await c.sendMessage(q)).response;
    let rounds = 0;
    let calls: FunctionCall[] | undefined;
    while ((calls = response.functionCalls())?.length && rounds++ < MAX_ROUNDS) {
      const results: FunctionResponsePart[] = await Promise.all(calls.map(async (call) => {
        let result: string;
        try { result = await runTool(call.name, (call.args ?? {}) as Record<string, unknown>, addStep); }
        catch (e) { console.warn(`tool ${call.name} failed`, e); result = `Error: ${String((e as Error)?.message ?? e).slice(0, 200)}`; }
        return { functionResponse: { name: call.name, response: { result }, ...(call.id ? { id: call.id } : {}) } } as FunctionResponsePart;
      }));
      set({ activity: "Thinking…" });
      response = (await c.sendMessage(results)).response;
    }
    let text = "";
    try { text = response.text().trim(); } catch { /* blocked */ }
    reply(gen, { role: "AI", text: text || "I couldn't come up with an answer. Try asking another way.", steps: [...steps], source: `cloud · ${CLOUD_TEXT_MODEL}` });
  } catch (e) {
    if (gen === generation) chat = null;
    reply(gen, { role: "AI", text: `Something went wrong: ${String((e as Error)?.message ?? e).slice(0, 160)}`, steps: [...steps], error: true });
  } finally {
    set({ busy: false, activity: "", liveSteps: [] });
  }
}

// ---------- Gemini Nano (Chrome's Prompt API) ----------

interface NanoSession { prompt(input: string): Promise<string>; destroy(): void }
interface NanoStatic { create(o?: Record<string, unknown>): Promise<NanoSession> }
type NanoPrompt = { role: "system" | "user" | "assistant"; content: string };

const NANO_PIECES = 150;
const NANO_CHARS = 12_000;
const NANO_TURNS = 6;

const NANO_SYSTEM = `You are the assistant inside LinguaLocal, a live interpreter used by people living in Thailand.
Below is the transcript of the conversation the user is asking about: one line per piece, with time, speaker,
spoken language, what was heard and its translation. Answer short and practical in the language the user writes in.
Refer to pieces by their time. Only use what the transcript shows; if it doesn't show something, say so.
You can't hear the recordings or search the web.`;

/** Newest pieces that fit Nano's small context, oldest first. */
function nanoLines(list: Caption[], max: number): string[] {
  const out: string[] = [];
  let chars = 0;
  for (let i = list.length - 1; i >= 0 && out.length < max; i--) {
    const c = list[i];
    const who = c.mine ? "you" : c.speaker != null ? `voice ${c.speaker}` : "someone";
    const body = c.turns.length
      ? c.turns.map((t) => `${t.speaker}: ${t.heard} => ${t.translation}`).join(" / ")
      : `${c.heard.slice(0, MAX_TEXT)} => ${c.translation.slice(0, MAX_TEXT)}`;
    const line = `${clock(c.time)} ${who}${c.lang.trim() ? ` (${c.lang})` : ""}: ${body}`;
    if (chars + line.length > NANO_CHARS && out.length) break;
    chars += line.length + 1;
    out.unshift(line);
  }
  return out;
}

/** Ask on Gemini Nano: the latest transcript text and the chat so far, no tools. Free, private, works offline. */
async function askNano(gen: number, q: string, before: Msg[]) {
  const LM = (globalThis as unknown as { LanguageModel?: NanoStatic }).LanguageModel;
  set({ busy: true, liveSteps: [], activity: "Loading Gemini Nano…" });
  const steps: string[] = [];
  let session: NanoSession | null = null;
  try {
    if (!LM) throw new Error("Gemini Nano isn't available in this browser");
    const all = pieces().filter((c) => c.heard.trim() || c.turns.length);
    const turns = history(before).slice(-NANO_TURNS * 2)
      .map((c): NanoPrompt => ({ role: c.role === "model" ? "assistant" : "user", content: c.parts.map((p) => ("text" in p ? p.text : "")).join("") }));
    // Shrink the transcript until Chrome accepts it; Nano's context is only a few pages.
    for (let max = NANO_PIECES; ; max = Math.floor(max / 2)) {
      const lines = nanoLines(all, max);
      const text = lines.length ? lines.join("\n") : "The project has no recordings yet.";
      try {
        session = await LM.create({ initialPrompts: [{ role: "system", content: `${NANO_SYSTEM}\n\nTranscript:\n${text}` }, ...turns] });
        steps.push(lines.length < all.length ? `Read the latest ${lines.length} of ${all.length} pieces` : "Read the transcript");
        break;
      } catch (e) {
        if (max < 10 || (e as Error)?.name !== "QuotaExceededError") throw e;
      }
    }
    set({ liveSteps: [...steps], activity: "Thinking…" });
    const text = (await session.prompt(q)).trim();
    reply(gen, { role: "AI", text: text || "I couldn't come up with an answer. Try asking another way.", steps, source: "on-device · Gemini Nano" });
  } catch (e) {
    const tooLong = (e as Error)?.name === "QuotaExceededError";
    reply(gen, {
      role: "AI", steps, error: true,
      text: tooLong ? "That's too long for Gemini Nano. Start a new chat or switch to Cloud." : `Something went wrong: ${String((e as Error)?.message ?? e).slice(0, 160)}`,
    });
  } finally {
    session?.destroy();
    set({ busy: false, activity: "", liveSteps: [] });
  }
}

async function runTool(name: string, args: Record<string, unknown>, step: (s: string) => void): Promise<string> {
  const str = (k: string) => (typeof args[k] === "string" ? (args[k] as string) : args[k] != null ? String(args[k]) : "").trim();
  const num = (k: string) => { const v = Number(args[k]); return args[k] == null || !Number.isFinite(v) ? null : v; };
  switch (name) {
    case "read_transcript":
      set({ activity: "Reading the transcript…" });
      step("Read the transcript");
      return transcript();
    case "search_transcript": {
      const query = str("query");
      set({ activity: "Searching the transcript…" });
      step(`Searched the transcript for “${query}”`);
      return search(query);
    }
    case "read_pieces": {
      const from = num("from_id"), to = num("to_id");
      if (from == null) return "Missing from_id.";
      if (to == null) return "Missing to_id.";
      const all = pieces();
      const part = all.filter((c) => c.id >= from && c.id <= to);
      if (!part.length) return `No pieces with ids ${from} to ${to}.`;
      set({ activity: `Reading ${clock(part[0].time)}…` });
      step(`Read ${clock(part[0].time)} – ${clock(part[part.length - 1].time)}`);
      const note = part.length > MAX_PIECES ? `Only the first ${MAX_PIECES} of ${part.length} pieces are shown.\n` : "";
      return note + rows(part.slice(0, MAX_PIECES), sessionsOf(all));
    }
    case "listen_to_recording": {
      const id = num("id");
      if (id == null) return "Missing id.";
      const c = pieces().find((x) => x.id === id);
      if (!c) return `No piece with id ${id}.`;
      const time = clock(c.time);
      set({ activity: st().liveSteps.some((s) => s.startsWith("Listen")) ? "Listening to the recordings…" : `Listening to ${time}…` });
      step(`Listened to ${time}`);
      const r = await listen(c, str("question"));
      heardIds.add(id);
      return r;
    }
    case "correct_piece": return correct(args, str, num, step);
    case "web_search": {
      const query = str("query");
      set({ activity: `Searching “${query}”…` });
      step(`Searched “${query}”`);
      const r = await searcher().generateContent(`Search the web and answer briefly with the key facts: ${query}`);
      try { return r.response.text().trim(); } catch { return ""; }
    }
    default: return `Unknown tool ${name}.`;
  }
}

const sessionsOf = (list: Caption[]) => [...new Set(list.map((c) => c.session))];

function transcript(): string {
  const captions = pieces();
  if (!captions.length) return "The project has no recordings yet.";
  const sessions = sessionsOf(captions);
  if (captions.length <= MAX_PIECES) return rows(captions, sessions);
  const groups = new Map<number, Caption[]>();
  captions.forEach((c) => { const g = groups.get(c.session) ?? []; g.push(c); groups.set(c.session, g); });
  const outline = [...groups.values()].map((s, i) => {
    const last = s[s.length - 1];
    return `Session ${i + 1}: ${day(s[0].time)} – ${clock(last.time + last.durationMs)}, ${s.length} pieces, ids ${s[0].id}–${last.id}`;
  }).join("\n");
  return `This project has ${captions.length} pieces. Outline:\n${outline}\n\n` +
    `Only the latest ${MAX_PIECES} pieces are below; use search_transcript or read_pieces for earlier parts.\n` +
    rows(captions.slice(-MAX_PIECES), sessions);
}

function search(query: string): string {
  const words = [...new Set(query.toLowerCase().split(/\s+/).filter(Boolean))];
  if (!words.length) return "Empty query.";
  const all = pieces();
  const hits = all.map((c) => { const t = searchText(c).toLowerCase(); return [c, words.filter((w) => t.includes(w)).length] as const; })
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1] || a[0].id - b[0].id)
    .slice(0, MAX_HITS);
  if (!hits.length) return `No piece mentions ${words.join(", ")}.`;
  return `${hits.length} matching pieces (best first):\n` + rows(hits.map((h) => h[0]), sessionsOf(all));
}

function rows(list: Caption[], sessions: number[]): string {
  return JSON.stringify(list.map((c) => {
    const o: Record<string, unknown> = {
      id: c.id, time: clock(c.time), session: sessions.indexOf(c.session) + 1, seconds: Math.floor(c.durationMs / 1000),
      status: c.status.toLowerCase(),
      speaker: c.mine ? "the user (push-to-talk)" : c.speaker != null ? `voice ${c.speaker}` : "unknown",
    };
    if (c.lang.trim()) o.language = c.lang;
    if (c.turns.length) o.turns = c.turns.map((t) => `${t.speaker}: ${t.heard} => ${t.translation}`);
    else { o.heard = c.heard.slice(0, MAX_TEXT); o.translation = c.translation.slice(0, MAX_TEXT); }
    o.has_audio = !!c.audioFile;
    return o;
  }));
}

async function listen(c: Caption, question: string): Promise<string> {
  const path = c.audioFile;
  if (!path) return "This piece has no saved audio.";
  const blob = await audioBlob(path);
  if (!blob) return "The audio file is missing.";
  const bytes = new Uint8Array(await blob.arrayBuffer());
  const heard = c.heard.trim() ? c.heard : c.turns.map((t) => `${t.speaker}: ${t.heard}`).join(" / ");
  const prompt = `This is a recording from a conversation in Thailand (${pairFor(projectId).label}, possibly mixed).
The app's transcript was: "${heard}"
Its translation was: "${c.translation}"
Listen to the audio itself and answer: ${question.trim() ? question : "What is said, by whom, and in what tone?"}
Be specific and brief. Quote Thai words with their English meaning.
Check the transcript and translation against the audio. If either is wrong, give the corrected transcript (in the
language spoken), the corrected translation, and how confident you are (high / medium / low).`;
  const r = await listener().generateContent([
    { inlineData: { data: base64(bytes), mimeType: path.toLowerCase().endsWith(".wav") ? "audio/wav" : "audio/mp4" } },
    { text: prompt },
  ]);
  try { return r.response.text().trim(); } catch { return ""; }
}

function correct(args: Record<string, unknown>, str: (k: string) => string, num: (k: string) => number | null, step: (s: string) => void): string {
  const id = num("id");
  if (id == null) return "Missing id.";
  const pid = projectId;
  const c = pid ? caption(pid, id) : undefined;
  if (!pid || !c) return `No piece with id ${id}.`;
  if (!heardIds.has(id)) return `Listen to piece ${id} with listen_to_recording before correcting it.`;
  const heard = str("heard"), translation = str("translation");
  if (!heard || !translation) return "Both heard and translation are required.";
  const l = str("language");
  const lang = [TH, EN, MY].includes(l) ? l : Lang.of(heard);
  const reason = str("reason") || "Checked against the recording";
  const speaker = str("speaker").toUpperCase();
  const engine = Lang.cloudEngine(CLOUD_TEXT_MODEL);
  updateCaption(pid, id, (old) => {
    const replaced: Revision = { heard: old.heard, translation: old.translation, lang: old.lang, engine: old.engine, turns: old.turns, reason, by: engine, time: Date.now() };
    const fixed = speaker && old.turns.some((t) => t.speaker.toUpperCase() === speaker)
      ? { ...old, turns: old.turns.map((t) => (t.speaker.toUpperCase() === speaker ? { ...t, heard, translation, lang } : t)) }
      : { ...old, heard, translation, lang, turns: [] };
    return { ...fixed, engine, status: "DONE", note: null, revisions: [...old.revisions, replaced] };
  });
  const time = clock(c.time);
  set({ activity: `Correcting ${time}…` });
  step(`Corrected ${time}`);
  const before = c.heard.trim() ? c.heard : c.turns.map((t) => t.heard).join(" / ");
  return `Saved. Piece ${id} now reads "${heard}" → "${translation}". The previous text was "${before}" → "${c.translation}".`;
}

/** Puts back what the piece said before Ask's last fix (the fix stays in the history). */
export function undoCorrection(pid: string, id: number) {
  updateCaption(pid, id, (c) => {
    const k = correctionOf(c);
    if (!k) return c;
    const undone: Revision = { heard: c.heard, translation: c.translation, lang: c.lang, engine: c.engine, turns: c.turns, reason: "Undone by user", by: USER_UNDO, time: Date.now() };
    return { ...c, heard: k.heard, translation: k.translation, lang: k.lang, engine: k.engine, turns: k.turns, revisions: [...c.revisions, undone] };
  });
}


