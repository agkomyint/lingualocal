export type SegmentStatus = "RECORDING" | "QUEUED" | "TRANSLATING" | "DONE" | "NO_SPEECH" | "SKIPPED";

export interface Turn {
  speaker: string;
  heard: string;
  translation: string;
  lang?: string;
  person?: number | null;
}

/** A transcript and translation that Ask replaced (or the user undid), kept so it can be put back. */
export interface Revision {
  heard: string;
  translation: string;
  lang: string;
  engine: string;
  turns: Turn[];
  reason: string;
  /** "cloud:<model>" for Ask, USER_UNDO when the user undid a fix. */
  by: string;
  time: number;
}

export const USER_UNDO = "user:undo";

export interface Caption {
  id: number;
  time: number;
  durationMs: number;
  status: SegmentStatus;
  heard: string;
  /** Live words from Gemini Live while the piece is still being recorded. */
  preview: string;
  translation: string;
  /** Storage path of the piece's recording. */
  audioFile?: string | null;
  explanation?: string | null;
  explainSource?: string | null;
  mine: boolean;
  speaker?: number | null;
  turns: Turn[];
  note?: string | null;
  offline: boolean;
  session: number;
  lang: string;
  engine: string;
  revisions: Revision[];
  source: string;
  /** Storage path of the saved spoken translation. */
  voiceFile?: string | null;
  voiceText: string;
}

export const SOURCE_MIC = "mic";
export const SOURCE_PHONE = "phone";
export const SOURCE_TYPED = "typed";

export const correctionOf = (c: Caption): Revision | null => {
  const r = c.revisions[c.revisions.length - 1];
  return r && r.by !== USER_UNDO ? r : null;
};

export const isDone = (c: Caption) => c.status === "DONE" || c.status === "NO_SPEECH" || c.status === "SKIPPED";

export interface WaveBar {
  level: number;
  speech: boolean;
}

export interface Project {
  id: string;
  name: string;
  created: number;
  lastUsed: number;
  items: number;
  sessions: number;
  totalMs: number;
  pair: string;
  autoNamed: boolean;
}

export type AudioSource = "MIC" | "PHONE";

export function newCaption(id: number, session: number, mine: boolean, source: string): Caption {
  return {
    id, time: Date.now(), durationMs: 0, status: "RECORDING", heard: "", preview: "", translation: "",
    audioFile: null, explanation: null, explainSource: null, mine, speaker: null, turns: [], note: null,
    offline: false, session, lang: "", engine: "", revisions: [], source, voiceFile: null, voiceText: "",
  };
}

/** Plain text of a piece, for searching. */
export function searchText(c: Caption): string {
  let s = c.heard + "\n" + c.translation;
  for (const t of c.turns) s += "\n" + t.heard + "\n" + t.translation;
  if (!c.heard.trim()) s += "\n" + c.preview;
  return s;
}
