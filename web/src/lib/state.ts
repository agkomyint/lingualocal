"use client";
import { create } from "zustand";
import { type AudioSource, type Caption, type Project, type WaveBar, newCaption as makeCaption, SOURCE_MIC, SOURCE_PHONE } from "./types";
import { pairOf, type LangPair } from "./lang";

/** Everything the screens show, like AppState on Android. */
export interface AppState {
  running: boolean;
  /** Mic or tab/phone audio, chosen for the next listen. */
  source: AudioSource;
  runningSource: AudioSource;
  liveStatus: string;
  vadStatus: string;
  nanoStatus: string;
  offlineStatus: string;
  speakerStatus: string;
  pushToTalkStatus: string;
  micLevel: number;
  vadProbability: number;
  speechActive: boolean;
  waveform: WaveBar[];
  bufferedBytes: number;
  inputGain: number;
  speakAloud: boolean;
  pushToTalk: boolean;
  ttsSpeaking: boolean;
  /** Start of the listening session (ms), 0 when not listening. */
  session: number;
  projects: Project[];
  /** Project on screen. */
  viewId: string | null;
  /** Project being recorded into (stays fixed while the user looks at others). */
  liveId: string | null;
  /** Pieces of the projects in memory, by project id. */
  captions: Record<string, Caption[]>;
  /** Language pair for the next new project. */
  nextPair: LangPair | null;
  /** People's names per project: project id -> person number -> name. */
  names: Record<string, Record<number, string>>;
  toast: { text: string; at: number } | null;
}

export const useApp = create<AppState>(() => ({
  running: false, source: "MIC", runningSource: "MIC",
  liveStatus: "Idle", vadStatus: "Not loaded", nanoStatus: "Checking…", offlineStatus: "Checking…",
  speakerStatus: "Not loaded", pushToTalkStatus: "Checking…",
  micLevel: 0, vadProbability: 0, speechActive: false, waveform: [], bufferedBytes: 0, inputGain: 1,
  speakAloud: true, pushToTalk: false, ttsSpeaking: false, session: 0,
  projects: [], viewId: null, liveId: null, captions: {}, nextPair: null, names: {}, toast: null,
}));

export const app = () => useApp.getState();
export const setApp = (p: Partial<AppState>) => useApp.setState(p);

export function toast(text: string) { setApp({ toast: { text, at: Date.now() } }); }

export const projectOf = (id: string | null | undefined) => (id ? app().projects.find((p) => p.id === id) : undefined);
export const pairFor = (id: string | null | undefined) => pairOf(projectOf(id)?.pair);
export const liveId = () => (app().running ? app().liveId : null);

export const captionsOf = (pid: string) => app().captions[pid] ?? [];
export const caption = (pid: string, id: number) => {
  const list = captionsOf(pid);
  for (let i = list.length - 1; i >= 0; i--) if (list[i].id === id) return list[i];
  return undefined;
};

export function updateCaption(pid: string, id: number, fn: (c: Caption) => Caption) {
  useApp.setState((s) => {
    const list = s.captions[pid];
    if (!list) return {};
    let i = list.length - 1;
    while (i >= 0 && list[i].id !== id) i--;
    if (i < 0) return {};
    const next = fn(list[i]);
    if (next === list[i]) return {};
    const copy = list.slice();
    copy[i] = next;
    return { captions: { ...s.captions, [pid]: copy } };
  });
}

/** A new piece at the end of the project. Its session is the listening session, else its own. */
export function newCaption(pid: string, mine = false, source?: string): number {
  const list = captionsOf(pid);
  const id = list.reduce((m, c) => Math.max(m, c.id), 0) + 1;
  const s = app().session > 0 ? app().session : Date.now();
  const src = source ?? (app().runningSource === "PHONE" ? SOURCE_PHONE : SOURCE_MIC);
  const c = makeCaption(id, s, mine, src);
  useApp.setState((st) => ({ captions: { ...st.captions, [pid]: [...(st.captions[pid] ?? []), c] } }));
  return id;
}

export function addCaption(pid: string, c: Caption) {
  useApp.setState((st) => ({ captions: { ...st.captions, [pid]: [...(st.captions[pid] ?? []), c] } }));
}
