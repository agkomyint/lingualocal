"use client";
/**
 * Moving between projects (Nav in LinguaScreen.kt) and replaying one piece's recording (SegmentAudio.kt).
 * Listening keeps recording into its own project while the user looks at, plays or asks about others;
 * only the mic button or Quick start ends or moves a session.
 */
import { create } from "zustand";
import { app } from "./state";
import { closeProject, createProject, openProject, audioBlob } from "./store";
import { startListening, stopListening as stopMic } from "./listen";
import * as playAll from "./playAll";
import { playBlob, remember, stopPlayer, stop as stopSpeaker } from "./speak";
import { watchPeople } from "./people";
import { toast } from "./state";
import type { Project } from "./types";

// ---------- One piece's original recording ----------

export const useReplay = create<{ id: number | null }>(() => ({ id: null }));

export async function replay(path: string, heard: string, id: number) {
  playAll.stop();
  stopSpeaker();
  useReplay.setState({ id });
  remember(heard);
  const b = await audioBlob(path);
  if (useReplay.getState().id !== id) return;
  if (!b) { toast("Recording not available"); useReplay.setState({ id: null }); return; }
  await playBlob(b);
  if (useReplay.getState().id === id) useReplay.setState({ id: null });
}

export function stopReplay() {
  if (useReplay.getState().id == null) return;
  useReplay.setState({ id: null });
  stopPlayer();
}

// ---------- Nav ----------

/** Playback belongs to the project on screen. */
function leaveView() {
  playAll.stop();
  stopReplay();
}

export async function open(p: Project) {
  if (app().viewId === p.id) return;
  leaveView();
  watchPeople(p.id);
  await openProject(p);
}

export function close() {
  leaveView();
  closeProject();
}

/** The mic button of the project on screen: stops listening here, or starts here (ending a session in another project first). */
export async function toggleListening() {
  const here = app().viewId;
  if (!here) return;
  if (app().running && app().liveId === here) { stopMic(); return; }
  if (app().running) stopMic();
  if (!app().running && app().viewId === here) await startListening();
}

/** Stops listening wherever it is. */
export function stopListening() {
  if (app().running) stopMic();
}

/** One tap: a new project named by date, already listening. */
export async function quickStart() {
  leaveView();
  const p = await createProject(null);
  await open(p);
  await toggleListening();
}

/** New project from the name dialog. */
export async function createAndOpen(name: string) {
  const p = await createProject(name);
  await open(p);
}
