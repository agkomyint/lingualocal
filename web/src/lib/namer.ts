"use client";
/**
 * Gives an "Untitled · date" project a short name from what was said, once its first few pieces are
 * translated (one small Gemini text request per project). A name the user typed is never replaced.
 * Port of ProjectNamer.kt.
 */
import { getGenerativeModel, type GenerativeModel } from "firebase/ai";
import { fbNow } from "./firebase";
import { CLOUD_TEXT_MODEL } from "./config";
import { useApp, projectOf } from "./state";
import { autoRename, needsName } from "./store";

const MIN_PIECES = 3;
const RETRY_MS = 60_000;

let model: GenerativeModel | null = null;
let busy = false;
const failed = new Map<string, number>();

async function check() {
  if (busy) return;
  const s = useApp.getState();
  for (const pid of new Set([s.running ? s.liveId : null, s.viewId].filter(Boolean) as string[])) {
    const p = projectOf(pid);
    if (!p || !needsName(p)) continue;
    if ((failed.get(pid) ?? 0) > Date.now() - RETRY_MS) continue;
    const pieces = (s.captions[pid] ?? []).filter((c) => c.status === "DONE" && (c.heard.trim() || c.turns.length));
    if (pieces.length < MIN_PIECES) continue;
    busy = true;
    try {
      const text = pieces.slice(0, 10).map((c) => {
        const heard = c.heard.trim() ? c.heard : c.turns.map((t) => t.heard).join(" / ");
        const tr = c.translation.trim() ? c.translation : c.turns.map((t) => t.translation).join(" / ");
        return c.translation.trim() || c.turns.some((t) => t.translation.trim()) ? `- ${heard} => ${tr}` : `- ${heard}`;
      }).join("\n").slice(0, 2000);
      model ??= getGenerativeModel(fbNow().ai, { model: CLOUD_TEXT_MODEL });
      const r = await model.generateContent(
        "Here is the start of a conversation recorded by an interpreter app. Give it a short title " +
        "(2 to 5 words, in English) saying what it is about, like \"Landlord · lease renewal\" or " +
        `"Clinic visit · fever". Reply with the title only, no quotes.\n\n${text}`,
      );
      const name = (r.response.text().split("\n").find((l) => l.trim()) ?? "").trim().replace(/^["'*. ]+|["'*. ]+$/g, "").slice(0, 48);
      if (!name) throw new Error("empty title");
      await autoRename(pid, name);
    } catch (e) {
      console.warn(`naming ${pid} failed`, e);
      failed.set(pid, Date.now());
    } finally {
      busy = false;
    }
    return;
  }
}

/** Watches the project being recorded and the one on screen. */
export function startNamer() {
  let t: ReturnType<typeof setTimeout> | null = null;
  useApp.subscribe((s, p) => {
    if ((s.captions !== p.captions || s.projects !== p.projects) && !t) t = setTimeout(() => { t = null; check(); }, 2000);
  });
}
