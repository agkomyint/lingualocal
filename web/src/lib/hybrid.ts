"use client";
/**
 * Text features around the interpreter. Port of HybridAssistant.kt, CloudText.kt and OfflineTranslator.kt:
 * - "What did they mean?": Gemini Nano in Chrome when it has it (Firebase AI Logic hybrid), cloud Gemini otherwise.
 * - Typed messages: Chrome's built-in on-device translator for Thai ⇄ English, cloud Gemini for the rest.
 */
import { getGenerativeModel, InferenceMode, InferenceSource, type GenerativeModel } from "firebase/ai";
import { fbNow } from "./firebase";
import { CLOUD_TEXT_MODEL } from "./config";
import { Lang, EN, MY, TH, type LangPair } from "./lang";
import { app, captionsOf, newCaption, pairFor, setApp, updateCaption, liveId } from "./state";
import { say } from "./playAll";
import { SOURCE_TYPED } from "./types";

// ---------- Chrome's built-in AI (Prompt API = Gemini Nano, Translator API) ----------

type Availability = "unavailable" | "downloadable" | "downloading" | "available";
interface ChromeTranslator { translate(text: string): Promise<string> }
interface TranslatorStatic {
  availability(o: { sourceLanguage: string; targetLanguage: string }): Promise<Availability>;
  create(o: { sourceLanguage: string; targetLanguage: string; monitor?: (m: EventTarget) => void }): Promise<ChromeTranslator>;
}
interface LanguageModelStatic { availability(o?: unknown): Promise<Availability> }
const g = () => globalThis as unknown as { Translator?: TranslatorStatic; LanguageModel?: LanguageModelStatic };

let nanoReady = false;

/** Status lines for the Engines sheet; also starts the on-device downloads Chrome allows. */
export async function refreshStatus() {
  const LM = g().LanguageModel;
  let nano = "Not available in this browser · using cloud fallback";
  try {
    const a = LM ? await LM.availability() : "unavailable";
    nanoReady = a === "available";
    if (a === "available") nano = "Gemini Nano ready in this browser";
    else if (a === "downloading" || a === "downloadable") nano = "Gemini Nano downloading…";
  } catch (e) { console.warn("Nano check failed", e); }
  setApp({ nanoStatus: nano, nanoReady });

  const T = g().Translator;
  let offline = "Not available in this browser · typed Thai ⇄ English uses the cloud";
  try {
    if (T) {
      const [a, b] = await Promise.all([
        T.availability({ sourceLanguage: TH, targetLanguage: EN }),
        T.availability({ sourceLanguage: EN, targetLanguage: TH }),
      ]);
      if (a === "available" && b === "available") offline = "Ready offline (Chrome's on-device translator)";
      else if (a !== "unavailable" && b !== "unavailable") offline = "Downloads on first typed message (needs internet once)";
    }
  } catch (e) { console.warn("Translator check failed", e); }
  setApp({ offlineStatus: offline });
}

const translators = new Map<string, Promise<ChromeTranslator>>();
async function deviceTranslate(text: string, from: string, to: string): Promise<string> {
  const T = g().Translator;
  if (!T) throw new Error("no on-device translator");
  const key = `${from}-${to}`;
  let t = translators.get(key);
  if (!t) {
    t = T.create({ sourceLanguage: from, targetLanguage: to });
    translators.set(key, t);
    t.then(() => refreshStatus(), () => translators.delete(key));
  }
  return (await t).translate(text);
}

async function deviceCan(from: string, to: string) {
  const T = g().Translator;
  if (!T) return false;
  try { return (await T.availability({ sourceLanguage: from, targetLanguage: to })) !== "unavailable"; } catch { return false; }
}

// ---------- Cloud text (CloudText.kt) ----------

let cloud: GenerativeModel | null = null;
const cloudModel = () => (cloud ??= getGenerativeModel(fbNow().ai, { model: CLOUD_TEXT_MODEL }));

/** Gemini, directly between the pair's two languages. */
export async function cloudTranslate(text: string, pair: LangPair): Promise<string> {
  const from = Lang.of(text);
  const to = Lang.other(from, pair);
  const script = to === MY ? " in Myanmar Unicode script" : "";
  const r = await cloudModel().generateContent(
    `Translate this ${Lang.name(from)} text directly into natural ${Lang.name(to)}${script}. ` +
    `Reply with the translation only.\n\n${text}`,
  );
  return r.response.text().trim();
}

// ---------- "What did they mean?" ----------

export interface Answer { text: string; source: string }

let hybrid: GenerativeModel | null = null;
const hybridModel = () => (hybrid ??= getGenerativeModel(fbNow().ai, {
  mode: InferenceMode.PREFER_ON_DEVICE,
  inCloudParams: { model: CLOUD_TEXT_MODEL },
}));

/** Explains what was meant (cultural context and a polite reply): Gemini Nano in Chrome, else cloud Gemini. */
export async function explain(heard: string, translation: string): Promise<Answer> {
  if (!navigator.onLine && !nanoReady)
    return { text: "No internet, and this browser has no offline AI model for explanations. Tap again when you're back online.", source: "" };
  const prompt = `An expat in Thailand heard this in a conversation.
Original: "${heard}"
Translation: "${translation}"
In at most 3 short sentences of plain English: explain what the speaker most likely meant
(including any cultural or politeness nuance), then suggest one short reply the expat could say.`;
  const r = (await hybridModel().generateContent(prompt)).response;
  const source = r.inferenceSource === InferenceSource.ON_DEVICE ? "on-device · Gemini Nano" : `cloud · ${CLOUD_TEXT_MODEL}`;
  return { text: r.text().trim(), source };
}

// ---------- Typed messages ----------

/**
 * A typed message becomes a piece of the project ("You"), translated and saved like spoken ones.
 * In a one-language project it is read aloud as typed instead.
 */
export function sendTyped(pid: string, input: string) {
  const pair = pairFor(pid);
  const listeningHere = liveId() === pid;
  const id = newCaption(pid, true, SOURCE_TYPED);
  // Not listening here: keep typed pieces in the latest session instead of starting one per message.
  const lastSession = [...captionsOf(pid)].reverse().find((c) => c.id !== id)?.session;
  updateCaption(pid, id, (c) => ({
    ...c, heard: input, lang: Lang.of(input), status: pair.transcribeOnly ? "DONE" : "TRANSLATING",
    session: listeningHere || lastSession == null ? c.session : lastSession,
  }));
  if (pair.transcribeOnly) {
    // Typed here to be heard, so it plays whatever the speaker switch says; the voice is saved with the piece.
    say(input, String(id), pid);
    return;
  }
  (async () => {
    const from = Lang.of(input);
    const to = Lang.other(from, pair);
    const onDevice = pair.worksOffline && (await deviceCan(from, to));
    try {
      const t = (onDevice ? await deviceTranslate(input, from, to).catch(() => cloudTranslate(input, pair)) : await cloudTranslate(input, pair)).trim();
      updateCaption(pid, id, (c) => ({
        ...c, translation: t, status: "DONE", note: null, offline: onDevice,
        engine: onDevice ? Lang.DEVICE_ENGINE : Lang.cloudEngine(CLOUD_TEXT_MODEL),
      }));
      if (app().speakAloud) say(t, String(id), pid);
    } catch (e) {
      updateCaption(pid, id, (c) => ({
        ...c, status: "QUEUED",
        note: onDevice ? `Offline translation failed: ${String((e as Error)?.message ?? e).slice(0, 60)}` : "Couldn't translate (needs internet)",
      }));
    }
  })();
}
