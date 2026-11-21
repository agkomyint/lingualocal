"use client";
/**
 * Projects in Firestore (users/{uid}/projects/{pid}, pieces in .../pieces/{id}) and recordings in Storage
 * (users/{uid}/projects/{pid}/audio/...). Firestore keeps an offline cache, so nothing is lost without internet;
 * recordings wait in IndexedDB until they are uploaded. Port of ProjectStore.kt.
 */
import {
  collection, doc, getDocs, onSnapshot, orderBy, query, setDoc, updateDoc, writeBatch, deleteDoc, getDoc,
} from "firebase/firestore";
import { ref, uploadBytes, getBytes, deleteObject, listAll } from "firebase/storage";
import { openDB, type IDBPDatabase } from "idb";
import { fbNow } from "./firebase";
import { app, setApp, useApp } from "./state";
import { type Caption, type Project, searchText } from "./types";
import { TH_EN } from "./lang";

const UNTITLED = "Untitled · ";

const userPath = () => `users/${fbNow().user.uid}`;
const projectsCol = () => collection(fbNow().db, `${userPath()}/projects`);
const projectDoc = (pid: string) => doc(fbNow().db, `${userPath()}/projects/${pid}`);
const piecesCol = (pid: string) => collection(fbNow().db, `${userPath()}/projects/${pid}/pieces`);
export const metaDoc = (pid: string, name: string) => doc(fbNow().db, `${userPath()}/projects/${pid}/meta/${name}`);
export const askCol = (pid: string) => collection(fbNow().db, `${userPath()}/projects/${pid}/ask`);
export const audioPath = (pid: string, file: string) => `${userPath()}/projects/${pid}/audio/${file}`;

const fmtDay = (t: number) => {
  const d = new Date(t);
  return `${d.getDate()} ${d.toLocaleString("en-GB", { month: "short" })} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

/** Firestore doesn't take undefined values. */
function clean<T>(o: T): T {
  return JSON.parse(JSON.stringify(o));
}

function toProject(id: string, d: Record<string, unknown>): Project {
  return {
    id, name: String(d.name ?? ""), created: Number(d.created ?? 0), lastUsed: Number(d.lastUsed ?? 0),
    items: Number(d.items ?? 0), sessions: Number(d.sessions ?? 0), totalMs: Number(d.totalMs ?? 0),
    pair: String(d.pair ?? TH_EN.code), autoNamed: Boolean(d.autoNamed),
  };
}

function toCaption(d: Record<string, unknown>): Caption {
  const c = d as unknown as Caption;
  return {
    id: Number(c.id), time: Number(c.time ?? 0), durationMs: Number(c.durationMs ?? 0),
    // A piece still recording or waiting when the page closed: its audio is gone with the page.
    status: c.status === "RECORDING" || c.status === "QUEUED" || c.status === "TRANSLATING"
      ? (c.heard || c.turns?.length ? "DONE" : "NO_SPEECH") : c.status ?? "DONE",
    heard: c.heard ?? "", preview: c.preview ?? "", translation: c.translation ?? "", audioFile: c.audioFile ?? null,
    explanation: c.explanation ?? null, explainSource: c.explainSource ?? null, mine: !!c.mine, speaker: c.speaker ?? null,
    turns: c.turns ?? [], note: c.note ?? null, offline: !!c.offline, session: Number(c.session ?? 0), lang: c.lang ?? "",
    engine: c.engine ?? "", revisions: c.revisions ?? [], source: c.source ?? "", voiceFile: c.voiceFile ?? null,
    voiceText: c.voiceText ?? "",
  };
}

let unsubProjects: (() => void) | null = null;

/** Keeps the projects list in step with Firestore (newest first by creation, so they never jump around). */
export function watchProjects(): Promise<void> {
  unsubProjects?.();
  return new Promise((resolve) => {
    let first = true;
    unsubProjects = onSnapshot(query(projectsCol(), orderBy("created", "desc")), (snap) => {
      setApp({ projects: snap.docs.map((d) => toProject(d.id, d.data())) });
      if (first) { first = false; resolve(); }
    }, () => { if (first) { first = false; resolve(); } });
  });
}

/** New project; a blank name becomes "Untitled · 21 Nov 14:05". */
export async function createProject(name?: string | null): Promise<Project> {
  const now = Date.now();
  const last = [...app().projects].sort((a, b) => b.lastUsed - a.lastUsed)[0];
  const p: Project = {
    id: `p${now}`,
    name: name?.trim() || UNTITLED + fmtDay(now),
    created: now, lastUsed: now, items: 0, sessions: 0, totalMs: 0,
    autoNamed: !name?.trim(),
    pair: app().nextPair?.code ?? last?.pair ?? TH_EN.code,
  };
  setApp({ projects: [p, ...app().projects.filter((x) => x.id !== p.id)] });
  setDoc(projectDoc(p.id), clean(p)).catch((e) => console.warn("create project failed", e));
  return p;
}

const loading = new Map<string, Promise<void>>();
/** Pieces as last saved, to write only what changed. */
const saved = new Map<string, Map<number, Caption>>();

/** Loads a project's pieces into memory (once). */
export function loadPieces(pid: string): Promise<void> {
  if (app().captions[pid]) return Promise.resolve();
  let p = loading.get(pid);
  if (!p) {
    p = (async () => {
      let list: Caption[] = [];
      try {
        const snap = await getDocs(query(piecesCol(pid), orderBy("id")));
        list = snap.docs.map((d) => toCaption(d.data()));
      } catch (e) { console.warn("load pieces failed", e); }
      const m = new Map<number, Caption>();
      list.forEach((c) => m.set(c.id, c));
      saved.set(pid, m);
      if (!app().captions[pid]) setApp({ captions: { ...app().captions, [pid]: list } });
    })().finally(() => loading.delete(pid));
    loading.set(pid, p);
  }
  return p;
}

/** Shows project p. A project being recorded keeps recording. */
export async function openProject(p: Project) {
  if (app().viewId === p.id) return;
  setApp({ viewId: p.id });
  updateDoc(projectDoc(p.id), { lastUsed: Date.now() }).catch(() => {});
  await loadPieces(p.id);
}

export function closeProject() { setApp({ viewId: null }); }

function changed(p: Project) {
  setApp({ projects: app().projects.map((x) => (x.id === p.id ? p : x)) });
}

export function renameProject(p: Project, name: string) {
  const n = name.trim();
  if (!n) return;
  changed({ ...p, name: n, autoNamed: false });
  updateDoc(projectDoc(p.id), { name: n, autoNamed: false }).catch(() => {});
}

/** Still "Untitled · date" and never named by the user: waiting for a title from what was said. */
export const needsName = (p: Project) => p.autoNamed && p.name.startsWith(UNTITLED);

/** Sets a title made by the app, unless the user named the project meanwhile. */
export async function autoRename(id: string, name: string): Promise<boolean> {
  const snap = await getDoc(projectDoc(id)).catch(() => null);
  const stored = snap?.exists() ? toProject(id, snap.data()) : app().projects.find((x) => x.id === id);
  if (!stored || !needsName(stored)) return false;
  changed({ ...stored, name });
  await updateDoc(projectDoc(id), { name }).catch(() => {});
  return true;
}

/** Switches the language pair; pieces already recorded keep their languages. */
export function setPair(p: Project, code: string) {
  changed({ ...p, pair: code });
  updateDoc(projectDoc(p.id), { pair: code }).catch(() => {});
}

/** Deletes the project with all its recordings. Listening in it must be stopped first. */
export async function deleteProject(p: Project) {
  const s = app();
  const captions = { ...s.captions };
  delete captions[p.id];
  setApp({
    viewId: s.viewId === p.id ? null : s.viewId, liveId: s.liveId === p.id ? null : s.liveId,
    projects: s.projects.filter((x) => x.id !== p.id), captions,
  });
  saved.delete(p.id);
  const { db, storage } = fbNow();
  try {
    for (const col of [piecesCol(p.id), askCol(p.id), collection(db, `${userPath()}/projects/${p.id}/meta`)]) {
      const snap = await getDocs(col);
      for (let i = 0; i < snap.docs.length; i += 400) {
        const b = writeBatch(db);
        snap.docs.slice(i, i + 400).forEach((d) => b.delete(d.ref));
        await b.commit();
      }
    }
    await deleteDoc(projectDoc(p.id));
    const files = await listAll(ref(storage, `${userPath()}/projects/${p.id}/audio`)).catch(() => null);
    await Promise.all(files?.items.map((f) => deleteObject(f).catch(() => {})) ?? []);
    await clearLocalAudio(p.id);
  } catch (e) { console.warn("delete project failed", e); }
}

/** Writes the pieces that changed since the last save, and the project's totals. */
function save(pid: string) {
  const list = app().captions[pid];
  const before = saved.get(pid);
  if (!list || !before) return;
  const changedPieces = list.filter((c) => before.get(c.id) !== c && c.status !== "RECORDING");
  if (!changedPieces.length) return;
  const { db } = fbNow();
  const b = writeBatch(db);
  for (const c of changedPieces.slice(0, 450)) {
    b.set(doc(piecesCol(pid), String(c.id)), clean({ ...c, search: searchText(c).toLowerCase() }));
    before.set(c.id, c);
  }
  const last = list[list.length - 1];
  const p = app().projects.find((x) => x.id === pid);
  const totals = {
    lastUsed: Math.max(p?.lastUsed ?? 0, last ? last.time + last.durationMs : 0),
    items: list.filter((c) => c.heard.trim() || c.turns.length).length,
    sessions: new Set(list.map((c) => c.session)).size,
    totalMs: list.reduce((s, c) => s + c.durationMs, 0),
  };
  b.update(projectDoc(pid), totals);
  if (p) changed({ ...p, ...totals });
  b.commit().catch((e) => console.warn("save failed", e));
}

let timer: ReturnType<typeof setTimeout> | null = null;
/** Saves a moment after the pieces change (and right away when the page is hidden). */
export function startAutoSave() {
  const flush = () => { timer = null; Object.keys(app().captions).forEach(save); };
  useApp.subscribe((s, prev) => {
    if (s.captions !== prev.captions && !timer) timer = setTimeout(flush, 800);
  });
  document.addEventListener("visibilitychange", () => { if (document.hidden) flush(); });
  window.addEventListener("pagehide", flush);
}

// ---- Recordings: IndexedDB first (plays offline), uploaded to Storage in the background. ----

let idb: Promise<IDBPDatabase> | null = null;
const local = () => (idb ??= openDB("lingualocal-audio", 1, {
  upgrade(d) { d.createObjectStore("audio"); d.createObjectStore("pending"); },
}));

/** Saves a recording; returns its Storage path (also the local key). */
export async function saveAudio(pid: string, file: string, bytes: Uint8Array, type = "audio/wav"): Promise<string> {
  const path = audioPath(pid, file);
  const blob = new Blob([bytes as BlobPart], { type });
  const d = await local();
  await d.put("audio", blob, path);
  await d.put("pending", type, path);
  upload(path, blob).catch(() => {});
  return path;
}

async function upload(path: string, blob: Blob) {
  await uploadBytes(ref(fbNow().storage, path), blob, { contentType: blob.type });
  await (await local()).delete("pending", path);
}

/** Uploads recordings that were made offline. */
export async function uploadPending() {
  const d = await local();
  for (const key of await d.getAllKeys("pending")) {
    const blob = (await d.get("audio", key)) as Blob | undefined;
    if (!blob) { await d.delete("pending", key); continue; }
    await upload(String(key), blob).catch(() => {});
  }
}

/** A recording's bytes: from this browser, else downloaded (and kept). */
export async function audioBlob(path: string): Promise<Blob | null> {
  const d = await local();
  const have = (await d.get("audio", path)) as Blob | undefined;
  if (have) return have;
  try {
    const bytes = await getBytes(ref(fbNow().storage, path));
    const type = path.endsWith(".wav") ? "audio/wav" : "audio/webm";
    const blob = new Blob([bytes], { type });
    await d.put("audio", blob, path);
    return blob;
  } catch { return null; }
}

export async function deleteAudio(path: string) {
  const d = await local();
  await d.delete("audio", path);
  await d.delete("pending", path);
  deleteObject(ref(fbNow().storage, path)).catch(() => {});
}

async function clearLocalAudio(pid: string) {
  const d = await local();
  const prefix = audioPath(pid, "");
  for (const key of await d.getAllKeys("audio")) if (String(key).startsWith(prefix)) await d.delete("audio", key);
  for (const key of await d.getAllKeys("pending")) if (String(key).startsWith(prefix)) await d.delete("pending", key);
}
