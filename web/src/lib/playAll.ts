"use client";
/**
 * Plays a whole project (or one session) piece by piece, skipping the silences between them.
 * Original voices by default; can switch to the spoken translations mid-play. Port of PlayAll.kt.
 */
import { create } from "zustand";
import { Lang } from "./lang";
import { app, toast } from "./state";
import { audioBlob } from "./store";
import { hasDeviceVoice, playBlob, remember, savedVoice, sayOnTap, spokenText, stop as stopSpeaker, stopPlayer, voiceFile, currentPlayer } from "./speak";
import type { Caption } from "./types";

export type PlayMode = "ORIGINAL" | "TRANSLATION";

interface PlayState {
  /** Caption being played now, or null when stopped. */
  current: number | null;
  mode: PlayMode;
  fast: boolean;
  /** Position in the queue, for the player bar ("3 / 12"). */
  position: [number, number];
  /** Key of the text being read by say (a caption id, or "typed"), null when quiet. */
  saying: string | null;
}
export const usePlay = create<PlayState>(() => ({ current: null, mode: "ORIGINAL", fast: false, position: [0, 0], saying: null }));
const st = () => usePlay.getState();
const set = (p: Partial<PlayState>) => usePlay.setState(p);

const GAP_MS = 350;
let source: Caption[] = [];
let queue: Caption[] = [];
let index = 0;
let generation = 0;

const playable = (c: Caption, m: PlayMode) =>
  m === "ORIGINAL" ? !!c.audioFile && (!!c.heard.trim() || c.turns.length > 0) : !!spokenText(c);

const speed = () => (st().fast ? 1.5 : 1);

/** Starts playing items in order, from fromId if given. */
export function start(items: Caption[], fromId?: number) {
  stopSpeaker();
  source = items;
  queue = items.filter((c) => playable(c, st().mode));
  if (!queue.length) { toast("No recorded sound to play here"); return; }
  // From a piece without sound: start at the next one that has it, never back at the beginning.
  const from = fromId != null ? items.find((c) => c.id === fromId) : undefined;
  const i = from ? queue.findIndex((c) => c.time >= from.time) : 0;
  play(i >= 0 ? i : 0);
}

/** Reads one translation aloud, on tap. Tapping the same one again stops it. */
export function say(text: string, key: string) {
  if (st().saying === key) { stopSpeaker(); set({ saying: null }); return; }
  stop();
  if (!text.trim()) return;
  sayOnTap(text, key, (on) => {
    if (on) set({ saying: key });
    else if (st().saying === key) set({ saying: null });
  });
}

export function stop() {
  generation++;
  stopSpeaker();
  set({ current: null, saying: null });
}

export const next = () => play(index + 1);
export const previous = () => play(Math.max(0, index - 1));

/** Switches between original voices and spoken translations, carrying on from the same piece. */
export function setMode(m: PlayMode) {
  if (st().mode === m) return;
  set({ mode: m });
  const at = st().current;
  if (at == null) return;
  queue = source.filter((c) => playable(c, m));
  const atTime = source.find((c) => c.id === at)?.time ?? 0;
  const i = queue.findIndex((c) => c.time >= atTime);
  play(i >= 0 ? i : queue.length);
}

export function toggleSpeed() {
  set({ fast: !st().fast });
  const p = currentPlayer();
  if (p) p.playbackRate = speed();
}

function play(i: number) {
  const gen = ++generation;
  stopSpeaker();
  if (i < 0 || i >= queue.length) { set({ current: null }); return; }
  index = i;
  const c = queue[i];
  set({ current: c.id, position: [i + 1, queue.length] });
  (st().mode === "ORIGINAL" ? playOriginal(c, gen) : speak(c, gen))
    .catch((e) => console.warn("play all", e))
    .finally(() => { if (gen === generation) setTimeout(() => { if (gen === generation) play(index + 1); }, GAP_MS); });
}

async function playOriginal(c: Caption, gen: number) {
  remember(c.heard);
  const b = c.audioFile ? await audioBlob(c.audioFile) : null;
  if (!b || gen !== generation) return;
  await playBlob(b, speed());
}

async function speak(c: Caption, gen: number) {
  const text = spokenText(c);
  remember(text);
  const pid = app().viewId;
  const saved = savedVoice(c, text);
  if (saved) {
    const b = await audioBlob(saved);
    if (gen !== generation) return;
    if (b) { await playBlob(b, speed()); return; }
  }
  const lang = Lang.of(text);
  if (hasDeviceVoice(lang)) {
    await new Promise<void>((resolve) => {
      const u = new SpeechSynthesisUtterance(text);
      const v = speechSynthesis.getVoices().find((x) => x.lang.toLowerCase().startsWith(Lang.locale(lang).slice(0, 2)));
      if (v) u.voice = v;
      u.lang = Lang.locale(lang);
      u.rate = speed();
      u.onend = () => resolve();
      u.onerror = () => resolve();
      speechSynthesis.speak(u);
    });
    return;
  }
  // Made once by Gemini and saved, so playing again later is free.
  const path = pid ? await voiceFile(pid, c.id) : null;
  if (gen !== generation) return;
  const b = path ? await audioBlob(path) : null;
  if (!b) { toast(`Couldn't speak ${Lang.name(lang)} (needs internet)`); return; }
  if (gen !== generation) return;
  await playBlob(b, speed());
}

export { stopPlayer };
