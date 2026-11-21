/** Find & jump in long meetings: search words, one person's pieces, or a time of day. Port of FindBar.kt's logic. */
import { dMon, hm } from "./format";
import { searchText, type Caption } from "./types";

/** A place in the transcript to jump to: a session start or a quarter hour inside a session. */
export interface TimeMark { label: string; time: number; session: boolean }

const MARK_EVERY_MS = 15 * 60_000;

function bySession(captions: Caption[]): Caption[][] {
  const m = new Map<number, Caption[]>();
  for (const c of captions) { const l = m.get(c.session); if (l) l.push(c); else m.set(c.session, [c]); }
  return [...m.values()];
}

/** Session starts, then every quarter hour of long sessions (09:00, 09:15, 09:30…). */
export function timeMarks(captions: Caption[]): TimeMark[] {
  if (!captions.length) return [];
  const sessions = bySession(captions);
  const days = new Set(sessions.map((s) => dMon(s[0].time))).size;
  const out: TimeMark[] = [];
  sessions.forEach((s, i) => {
    const start = s[0].time;
    const end = s[s.length - 1].time;
    const label = (days > 1 ? dMon(start) + " " : "") + hm(start);
    out.push({ label: sessions.length > 1 ? `Session ${i + 1} · ${label}` : `Start · ${label}`, time: start, session: true });
    for (let t = (Math.floor(start / MARK_EVERY_MS) + 1) * MARK_EVERY_MS; t < end; t += MARK_EVERY_MS) out.push({ label: hm(t), time: t, session: false });
  });
  return out;
}

/** People heard in the project (person numbers on cards and turns). */
export function peopleIn(captions: Caption[]): number[] {
  const s = new Set<number>();
  for (const c of captions) {
    if (c.speaker != null) s.add(c.speaker);
    for (const t of c.turns) if (t.person != null) s.add(t.person);
  }
  return [...s].sort((a, b) => a - b);
}

/** Does the piece match the search words (all of them) and the chosen person? */
export function matches(c: Caption, words: string[], person: number | null, mine: boolean): boolean {
  if (mine && !c.mine) return false;
  if (person != null && c.speaker !== person && !c.turns.some((t) => t.person === person)) return false;
  if (!words.length) return true;
  const text = searchText(c).toLowerCase();
  return words.every((w) => text.includes(w));
}

/** A moment the user typed ("8:35", "20:35", "8:35 pm") found inside a recorded session. */
export interface ClockHit { time: number; session: number; sessionStart: number }

const CLOCK = /^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a|p)?$/;

/** If query is a time of day, the places in the recording at that time, else null. Without am/pm, both 8:35 and 20:35 are tried. */
export function clockHits(captions: Caption[], query: string): ClockHit[] | null {
  const m = CLOCK.exec(query.trim().toLowerCase());
  if (!m) return null;
  const [, hs, ms = "", ampm = ""] = m;
  if (!ms && !ampm) return null; // a lone number is a search word, not a time
  const h = Number(hs), min = Number(ms || "0");
  if (h > 23 || min > 59) return null;
  let hours: number[];
  if (ampm.startsWith("p")) { if (h >= 1 && h <= 11) hours = [h + 12]; else if (h === 12) hours = [12]; else return null; }
  else if (ampm.startsWith("a")) { if (h === 12) hours = [0]; else if (h >= 1 && h <= 11) hours = [h]; else return null; }
  else if (h >= 1 && h <= 11) hours = [h, h + 12];
  else hours = [h];
  const out: ClockHit[] = [];
  bySession(captions).forEach((s, i) => {
    const from = s[0].time;
    const to = Math.max(...s.map((c) => c.time + c.durationMs));
    const day = new Date(from); day.setHours(0, 0, 0, 0);
    // Every day the session touches (a session can run past midnight).
    while (day.getTime() <= to) {
      for (const hour of hours) {
        const d = new Date(day); d.setHours(hour, min, 0, 0);
        if (d.getTime() >= from && d.getTime() <= to) out.push({ time: d.getTime(), session: i + 1, sessionStart: from });
      }
      day.setDate(day.getDate() + 1);
    }
  });
  return out.sort((a, b) => a.time - b.time);
}

export const searchWords = (q: string) => q.toLowerCase().split(/\s+/).filter(Boolean);

/** The piece being spoken at time, or the first one after it. */
export function pieceAt(captions: Caption[], time: number): Caption | undefined {
  for (let i = captions.length - 1; i >= 0; i--) {
    const c = captions[i];
    if (c.time <= time && time < c.time + c.durationMs + 1000) return c;
  }
  return captions.find((c) => c.time >= time);
}
