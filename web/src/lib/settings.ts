/** Settings kept in this browser: speaking translations, and the voice for each language. */
import { EN, MY, TH } from "./lang";
import { app, setApp, useApp, type Voice, type VoiceChoice } from "./state";

const KEY = "lingualocal.settings";

/** Picks the voice for a language. Gemini makes voices by itself only for pieces from now on. */
export function setVoice(lang: string, engine: Voice) {
  const { voices } = app();
  if (voices[lang]?.engine === engine) return;
  setApp({ voices: { ...voices, [lang]: { engine, since: Date.now() } } });
}

/** Loads the saved settings, then keeps them saved. */
export function loadSettings() {
  let saved: { speakAloud?: unknown; voices?: Record<string, Partial<VoiceChoice> | undefined> } = {};
  try {
    saved = JSON.parse(localStorage.getItem(KEY) ?? "{}") ?? {};
  } catch (e) {
    console.warn("settings not read", e);
  }
  const now = Date.now();
  const voices: Record<string, VoiceChoice> = {};
  for (const lang of [EN, TH, MY]) {
    const v = saved.voices?.[lang];
    voices[lang] = v?.engine === "device" || v?.engine === "gemini"
      ? { engine: v.engine, since: Number(v.since) || 0 }
      // First run: what's already in the projects isn't made again.
      : { engine: app().voices[lang].engine, since: now };
  }
  setApp({ speakAloud: saved.speakAloud !== false, voices });

  const save = () => {
    const s = app();
    try {
      localStorage.setItem(KEY, JSON.stringify({ speakAloud: s.speakAloud, voices: s.voices }));
    } catch (e) {
      console.warn("settings not saved", e);
    }
  };
  save();
  useApp.subscribe((s, p) => { if (s.speakAloud !== p.speakAloud || s.voices !== p.voices) save(); });
}
