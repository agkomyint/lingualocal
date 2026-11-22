"use client";
/** One project's conversation: header, transcript, player, input bar, Ask and engines. Port of Conversation.kt. */
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { DropdownMenu } from "radix-ui";
import {
  ArrowLeft, Check, CircleCheck, Ear, Info, Keyboard, Mic, Monitor, Play, Search, Send, SkipBack, SkipForward,
  SlidersHorizontal, Sparkles, Square, Users, Volume2, VolumeX,
} from "lucide-react";
import { cn } from "@/lib/cn";
import { app, setApp, toast, updateCaption, useApp } from "@/lib/state";
import { Lang, PAIRS, TH_EN, langColor, pairOf } from "@/lib/lang";
import { renameProject, setPair } from "@/lib/store";
import { setPushToTalk } from "@/lib/listen";
import * as playAll from "@/lib/playAll";
import { usePlay, type PlayMode } from "@/lib/playAll";
import { savedVoice, spokenText } from "@/lib/speak";
import { explain, sendTyped } from "@/lib/hybrid";
import { undoCorrection } from "@/lib/ask";
import { label as personLabel, rename as renamePerson } from "@/lib/people";
import { clockHits, matches, peopleIn, pieceAt, searchWords, timeMarks, type TimeMark } from "@/lib/find";
import { clockLength, dayLabel, duration, hm } from "@/lib/format";
import * as nav from "@/lib/nav";
import { useReplay } from "@/lib/nav";
import { correctionOf, isDone, type Caption, type Project, type Revision, type Turn } from "@/lib/types";
import { MIC_SAMPLE_RATE } from "@/lib/config";
import { AskPanel } from "./AskPanel";
import { FindBar, GoToRow } from "./FindBar";
import { KeyValue, Logo, NameDialog, PendingLine, Sheet } from "./ui";

const SILENCE_GAP_MS = 2000;
const ASK_WIDTH = 380;
const NO_CAPTIONS: Caption[] = [];
const NO_NAMES: Record<number, string> = {};

type Row =
  | { kind: "session"; key: string; number: number; start: number; items: Caption[] }
  | { kind: "gap"; key: string; ms: number }
  | { kind: "piece"; key: string; c: Caption };

function timeline(captions: Caption[]): Row[] {
  const bySession = new Map<number, Caption[]>();
  for (const c of captions) { const l = bySession.get(c.session); if (l) l.push(c); else bySession.set(c.session, [c]); }
  const rows: Row[] = [];
  let prev: Caption | null = null;
  let number = 0;
  for (const c of captions) {
    if (!prev || prev.session !== c.session) {
      rows.push({ kind: "session", key: `s-${c.session}`, number: ++number, start: c.session, items: bySession.get(c.session) ?? [] });
    } else {
      // Inside a session the transcript also marks the quiet stretches between pieces of speech.
      const gap = c.time - (prev.time + prev.durationMs);
      if (gap >= SILENCE_GAP_MS) rows.push({ kind: "gap", key: `gap-${c.id}`, ms: gap });
    }
    rows.push({ kind: "piece", key: String(c.id), c });
    prev = c;
  }
  return rows;
}

/** m:ss since the current listening session started, ticking once a second. */
function useElapsed(running: boolean) {
  const session = useApp((s) => s.session);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!running) return;
    setNow(Date.now());
    const h = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(h);
  }, [running]);
  if (!running || session <= 0) return "0:00";
  return clockLength(Math.max(0, Math.floor((now - session) / 1000)));
}

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [w, setW] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(e.contentRect.width));
    ro.observe(el);
    setW(el.getBoundingClientRect().width);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

const shownName = (p: Project) => (p.name.startsWith("Untitled · ") ? "Untitled" : p.name);

// ---------- Screen ----------

export function Conversation({ project, showBack }: { project: Project; showBack: boolean }) {
  const pid = project.id;
  const anyRunning = useApp((s) => s.running);
  const liveId = useApp((s) => s.liveId);
  const projects = useApp((s) => s.projects);
  const running = anyRunning && liveId === pid;
  const elsewhere = anyRunning && liveId && liveId !== pid ? projects.find((p) => p.id === liveId) : undefined;
  const captions = useApp((s) => s.captions[pid] ?? NO_CAPTIONS);
  const names = useApp((s) => s.names[pid] ?? NO_NAMES);
  const playing = usePlay((s) => s.current);
  const rows = useMemo(() => timeline(captions), [captions]);

  const [renaming, setRenaming] = useState(false);
  const [engines, setEngines] = useState(false);
  const [asking, setAsking] = useState(false);
  const [finding, setFinding] = useState(false);
  const [query, setQuery] = useState("");
  const [person, setPerson] = useState<number | null>(null);
  const [mineOnly, setMineOnly] = useState(false);
  const [jumpTo, setJumpTo] = useState<number | null>(null);

  // Find state belongs to one project.
  useEffect(() => { setFinding(false); setQuery(""); setPerson(null); setMineOnly(false); }, [pid]);

  const clock = useMemo(() => clockHits(captions, query), [captions, query]);
  const words = useMemo(() => (clock != null ? [] : searchWords(query)), [query, clock]);
  const filtering = finding && (words.length > 0 || person != null || mineOnly);
  const results = useMemo(
    () => (filtering ? captions.filter((c) => c.status !== "RECORDING" && matches(c, words, person, mineOnly)) : []),
    [captions, words, person, mineOnly, filtering],
  );
  const marks = useMemo(() => (finding ? timeMarks(captions) : []), [captions, finding]);
  const people = useMemo(() => (finding ? peopleIn(captions) : []), [captions, finding]);
  const hasMine = finding && captions.some((c) => c.mine);

  const [rootRef, rootW] = useWidth<HTMLDivElement>();
  const askSide = rootW >= 900;
  const wide = (asking && askSide ? rootW - ASK_WIDTH : rootW) >= 640;

  // ---- Scrolling ----
  const listRef = useRef<HTMLDivElement>(null);
  const atEnd = useRef(true);
  const [behind, setBehind] = useState(false);
  const rowEl = (key: string) => listRef.current?.querySelector<HTMLElement>(`[data-row="${CSS.escape(key)}"]`);
  const toEnd = (smooth = false) => { const el = listRef.current; if (el) el.scrollTo({ top: el.scrollHeight, behavior: smooth ? "smooth" : "auto" }); };
  const onScroll = () => {
    const el = listRef.current;
    if (!el) return;
    const gap = el.scrollHeight - el.scrollTop - el.clientHeight;
    atEnd.current = gap < 320;
    setBehind(gap > 600);
  };
  // Opening a project starts at the newest piece.
  useLayoutEffect(() => { atEnd.current = true; toEnd(); }, [pid]);
  // Follow new pieces only when already at the end, so reading back in a long meeting isn't interrupted.
  useLayoutEffect(() => {
    if (atEnd.current && playing == null && !filtering) toEnd(true);
  }, [captions, playing, filtering]);
  useEffect(() => {
    if (jumpTo == null || filtering) return;
    const i = rows.findIndex((r) => r.kind === "piece" && r.c.id === jumpTo);
    const target = rows[Math.max(0, i - 1)];
    if (i >= 0 && target) rowEl(target.key)?.scrollIntoView({ block: "start" });
    setJumpTo(null);
  }, [jumpTo, filtering, rows]);
  // Play all: keep the piece being played on screen.
  useEffect(() => {
    if (playing != null) rowEl(String(playing))?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [playing]);

  const jump = (m: TimeMark) => {
    if (m.time === Number.MAX_SAFE_INTEGER) { toEnd(); return; }
    const i = rows.findIndex((r) => (r.kind === "piece" && r.c.time >= m.time) || (m.session && r.kind === "session" && r.start === m.time));
    const target = rows[m.session ? i : Math.max(0, i - 1)];
    if (i >= 0 && target) rowEl(target.key)?.scrollIntoView({ block: "start" });
  };

  const pad = wide ? "px-6" : "px-3";

  return (
    <div ref={rootRef} className="flex h-full w-full bg-bg">
      <div className="flex min-w-0 flex-1 flex-col">
        {elsewhere && <RecordingElsewhere project={elsewhere} onGoBack={() => nav.open(elsewhere)} />}
        <Header
          project={project} running={running} showBack={showBack} captions={captions} playing={playing != null}
          asking={asking} onAsk={() => setAsking(!asking)} finding={finding} onFind={() => setFinding(!finding)}
          onRename={() => setRenaming(true)} onEngines={() => setEngines(true)}
        />
        {finding && (
          <FindBar
            query={query} onQuery={setQuery} person={person} onPerson={setPerson} mine={mineOnly} onMine={setMineOnly}
            people={people} hasMine={hasMine} names={names} marks={marks} onJump={jump}
            results={filtering ? results.length : null} onClose={() => setFinding(false)} clock={clock}
            onClockGo={(hit) => { setFinding(false); setJumpTo(pieceAt(captions, hit.time)?.id ?? null); }}
            onClockPlay={(hit) => {
              const p = pieceAt(captions, hit.time);
              setFinding(false);
              if (p) { setJumpTo(p.id); playAll.start(captions, p.id); }
            }}
          />
        )}
        <BufferBanner captions={captions} />
        <div className="relative min-h-0 flex-1">
          <div ref={listRef} onScroll={onScroll} className="h-full overflow-y-auto">
            <div className={cn("mx-auto flex w-full max-w-[860px] flex-col gap-3.5 py-3", pad)}>
              {captions.length === 0 && <EmptyTranscript running={running} pairLabel={pairOf(project.pair).label} />}
              {rows.map((r) => (
                <div key={r.key} data-row={r.key}>
                  {r.kind === "session" ? <SessionDivider row={r} />
                    : r.kind === "gap" ? <GapDivider ms={r.ms} />
                    : <Bubble c={r.c} pid={pid} pairCode={project.pair} names={names} playing={r.c.id === playing} />}
                </div>
              ))}
            </div>
          </div>
          {filtering && (
            <div className="absolute inset-0 overflow-y-auto bg-bg">
              <div className={cn("mx-auto flex w-full max-w-[860px] flex-col gap-2.5 py-3", pad)}>
                {results.map((c) => (
                  <div key={c.id}>
                    <GoToRow time={c.time} onGo={() => { setJumpTo(c.id); setFinding(false); }} />
                    <Bubble c={c} pid={pid} pairCode={project.pair} names={names} playing={c.id === playing} />
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
        {!filtering && behind && (
          <div className="flex justify-center py-1.5">
            <button onClick={() => toEnd()} className="h-9 rounded-[18px] border border-line-strong bg-raised px-3.5 text-[13px] font-medium text-text hover:bg-control">
              ↓  Latest
            </button>
          </div>
        )}
        {playing != null && <PlayerBar wide={wide} />}
        <InputBar pid={pid} pairCode={project.pair} running={running} elsewhere={elsewhere} wide={wide} />
      </div>
      {asking && askSide && (
        <>
          <div className="h-full w-px bg-line" />
          <AskPanel onClose={() => setAsking(false)} className="h-full w-[380px] shrink-0" />
        </>
      )}
      <Sheet open={asking && !askSide} onClose={() => setAsking(false)} title="Ask">
        <AskPanel onClose={() => setAsking(false)} className="h-[92dvh]" />
      </Sheet>
      <NameDialog open={renaming} title="Rename project" initial={project.name} onClose={() => setRenaming(false)}
        onDone={(n) => { renameProject(project, n); setRenaming(false); }} />
      <Sheet open={engines} onClose={() => setEngines(false)} title="Engines">
        <EnginePanel captions={captions} />
      </Sheet>
    </div>
  );
}

// ---------- Header ----------

function HeaderIcon({ icon, label, active, onClick }: { icon: ReactNode; label: string; active?: boolean; onClick: () => void }) {
  return (
    <button aria-label={label} title={label} onClick={onClick}
      className={cn("flex h-10 w-10 shrink-0 items-center justify-center rounded-full", active ? "bg-selected text-english" : "bg-surface text-muted hover:bg-raised")}>
      {icon}
    </button>
  );
}

function Pill({ children, onClick, active }: { children: ReactNode; onClick?: () => void; active?: boolean }) {
  return (
    <button onClick={onClick}
      className={cn("flex h-10 shrink-0 items-center rounded-[20px] border pl-2.5 pr-3.5 text-[14px] font-medium text-text",
        active ? "border-mine-line bg-selected" : "border-line-strong bg-raised hover:bg-control")}>
      {children}
    </button>
  );
}

function Header({ project, running, showBack, captions, playing, asking, onAsk, finding, onFind, onRename, onEngines }: {
  project: Project; running: boolean; showBack: boolean; captions: Caption[]; playing: boolean;
  asking: boolean; onAsk: () => void; finding: boolean; onFind: () => void; onRename: () => void; onEngines: () => void;
}) {
  const elapsed = useElapsed(running);
  const playable = captions.filter((c) => isDone(c) && (!!c.heard.trim() || c.turns.length > 0)).length;
  return (
    <>
      <div className={cn("flex min-h-16 items-center gap-2 py-2 pr-3", showBack ? "pl-1" : "pl-5")}>
        {showBack && (
          <button aria-label="All projects" onClick={() => nav.close()} className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-text hover:bg-raised">
            <ArrowLeft size={22} />
          </button>
        )}
        <button onClick={onRename} className="min-w-0 flex-1 rounded-lg px-1 py-0.5 text-left hover:bg-surface">
          <div className="truncate text-[17px] font-semibold text-text">{project.name}</div>
          <div className="flex items-center">
            <span className={cn("h-[7px] w-[7px] rounded-full", running ? "bg-live" : "bg-idle")} />
            <span className={cn("ml-1.5 text-[12px]", running ? "text-muted" : "text-faint")}>{running ? `Listening · ${elapsed}` : "Not listening"}</span>
          </div>
        </button>
        <HeaderIcon icon={<Search size={20} />} label="Find and jump to a time" active={finding} onClick={onFind} />
        <HeaderIcon icon={<SlidersHorizontal size={20} />} label="Engines and diagnostics" onClick={onEngines} />
        <PairSwitch project={project} />
        <Pill onClick={onAsk} active={asking}><Sparkles size={18} className="mr-1.5 text-english" />Ask</Pill>
        {playable > 0 && !playing && (
          <Pill onClick={() => playAll.start(captions)}><Play size={18} className="mr-1 fill-current" />Play all</Pill>
        )}
      </div>
      <div className="h-px w-full bg-line" />
    </>
  );
}

/** The project's language pair; tap to switch. New pieces use the new pair. */
function PairSwitch({ project }: { project: Project }) {
  const pair = pairOf(project.pair);
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button aria-label={`Language pair: ${pair.label}`} className="flex h-10 shrink-0 items-center rounded-[20px] border border-line-strong bg-raised px-3 text-[13px] font-bold hover:bg-control">
          <span style={{ color: langColor(pair.a) }}>{pair.a.toUpperCase()}</span>
          <span className="font-normal text-faint">&nbsp;⇄&nbsp;</span>
          <span style={{ color: langColor(pair.b) }}>{pair.b.toUpperCase()}</span>
        </button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content align="end" sideOffset={4} className="z-50 min-w-[220px] rounded-[12px] bg-raised py-1.5 shadow-xl">
          {PAIRS.map((p) => (
            <DropdownMenu.Item key={p.code} onSelect={() => { if (p.code !== pair.code) setPair(project, p.code); }}
              className="flex cursor-pointer items-center gap-3 px-4 py-2.5 outline-none data-[highlighted]:bg-control">
              <div className="flex-1">
                <div className={cn("text-[15px] text-text", p.code === pair.code && "font-semibold")}>{p.label}</div>
                <div className="text-[12px] text-faint">{p.worksOffline ? "Works offline too" : "Needs internet"}</div>
              </div>
              {p.code === pair.code && <Check size={18} className="text-english" aria-label="Selected" />}
            </DropdownMenu.Item>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

/** Listening goes on in another project while this one is open: where, for how long, and the way back. */
export function RecordingElsewhere({ project, onGoBack }: { project: Project; onGoBack: () => void }) {
  const elapsed = useElapsed(true);
  return (
    <>
      <button onClick={onGoBack} className="flex w-full items-center bg-surface px-4 py-2.5 text-left hover:bg-raised">
        <span className="h-2 w-2 shrink-0 rounded-full bg-live" />
        <span className="ml-2.5 min-w-0 flex-1 truncate text-[14px] text-text">Recording in {shownName(project)} · {elapsed}</span>
        <span className="ml-3 text-[14px] font-semibold text-english">Go back</span>
      </button>
      <div className="h-px w-full bg-line" />
    </>
  );
}

/** Speech saved while Gemini is unreachable, so nothing disappears silently. */
function BufferBanner({ captions }: { captions: Caption[] }) {
  const buffered = useApp((s) => s.bufferedBytes);
  const live = useApp((s) => s.liveStatus);
  const waiting = captions.filter((c) => c.status === "QUEUED" || c.status === "TRANSLATING").length;
  const sec = buffered / (2 * MIC_SAMPLE_RATE);
  if (sec < 1 && !(!live.startsWith("Live") && waiting > 0)) return null;
  return (
    <div className="flex w-full items-center bg-thai/10 px-5 py-2">
      <Info size={16} className="shrink-0 text-thai" />
      <span className="ml-2 text-[13px] text-thai">{Math.round(sec)} s of speech is saved and waiting. It&apos;s sent as soon as Gemini reconnects.</span>
    </div>
  );
}

function EmptyTranscript({ running, pairLabel }: { running: boolean; pairLabel: string }) {
  return (
    <div className="flex flex-col items-center pt-16 text-center">
      <Logo size={56} />
      <div className="mt-3 text-[16px] font-medium text-text">
        {running ? `Listening… speak ${pairLabel.replace(" ⇄ ", " or ")} nearby.` : "Tap listen, or type below."}
      </div>
      <div className="mt-1 text-[13px] text-muted">Everything heard or typed here is saved in this project.</div>
    </div>
  );
}

// ---------- Dividers ----------

function SessionDivider({ row }: { row: Extract<Row, { kind: "session" }> }) {
  const end = row.items.reduce((m, c) => Math.max(m, c.time + c.durationMs), row.start);
  return (
    <div className="flex items-center pt-2">
      <div className="h-px flex-1 bg-line-strong" />
      <div className="mx-3 flex flex-col items-center text-center">
        <div className="text-[13px] font-semibold text-text">Session {row.number}</div>
        <div className="text-[12px] text-muted">{dayLabel(row.start)} – {hm(end)} · {duration(end - row.start)}</div>
      </div>
      <button onClick={() => playAll.start(row.items)} className="mr-3 flex h-[30px] items-center rounded-[15px] bg-raised pl-1.5 pr-2.5 text-[12px] font-medium text-text hover:bg-control">
        <Play size={16} className="mr-0.5 fill-current" />Play
      </button>
      <div className="h-px flex-1 bg-line-strong" />
    </div>
  );
}

function GapDivider({ ms }: { ms: number }) {
  return (
    <div className="flex items-center px-6">
      <div className="flex-1 border-t border-dashed border-line-strong" />
      <span className="px-2 text-[12px] text-faint">{ms < 60_000 ? `${Math.floor(ms / 1000)} s` : duration(ms)} quiet</span>
      <div className="flex-1 border-t border-dashed border-line-strong" />
    </div>
  );
}

// ---------- Bubble ----------

function Bubble({ c, pid, pairCode, names, playing }: { c: Caption; pid: string; pairCode: string; names: Record<number, string>; playing: boolean }) {
  const heardLang = c.lang || Lang.of(c.heard.trim() ? c.heard : c.preview);
  if (c.status === "NO_SPEECH" || c.status === "SKIPPED") {
    // Pieces with no words stay in the record, but quietly.
    return (
      <div className="flex items-center gap-2 px-2 text-[12px] text-faint">
        <span>{hm(c.time)}</span>
        <span className="truncate">{c.status === "NO_SPEECH" ? `No words · ${Math.round(c.durationMs / 1000)} s` : c.note ?? "Noise · skipped on device"}</span>
      </div>
    );
  }
  // Everything reads top to bottom in one column, like a transcript. Your own pieces get a thin blue edge.
  const ring = playing ? "border-2 border-english" : c.status === "RECORDING" ? "border border-thai/50" : "border border-line";
  return (
    <div className="w-full">
      <BubbleHeader c={c} pid={pid} pairCode={pairCode} names={names} heardLang={heardLang} />
      <div className={cn("relative mt-1.5 overflow-hidden rounded-[16px] bg-surface pb-2 pl-[18px] pr-4 pt-3.5", ring)}>
        {c.mine && <div className="absolute inset-y-0 left-0 w-[3px] bg-english" />}
        <BubbleBody c={c} pid={pid} playing={playing} names={names} />
      </div>
    </div>
  );
}

function BubbleHeader({ c, pid, pairCode, names, heardLang }: { c: Caption; pid: string; pairCode: string; names: Record<number, string>; heardLang: string }) {
  const [renaming, setRenaming] = useState(false);
  const person = c.mine ? null : c.speaker ?? null;
  const firstTr = c.translation.trim() ? c.translation : c.turns[0]?.translation ?? "";
  const to = isDone(c) ? Lang.target(heardLang, firstTr) : Lang.other(heardLang, pairOf(pairCode));
  return (
    <div className="flex items-center px-1">
      <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-raised">
        {c.mine ? (c.audioFile == null ? <Keyboard size={15} className="text-english" /> : <Mic size={15} className="text-english" />)
          : person != null ? <span className="text-[13px] font-bold text-thai">{names[person]?.trim()?.[0]?.toUpperCase() ?? person}</span>
          : <span className="text-[13px] font-bold text-muted">•</span>}
      </div>
      {person != null ? (
        <button onClick={() => setRenaming(true)} className="ml-2 text-[13px] font-semibold text-text hover:underline">{personLabel(person, names)}</button>
      ) : (
        <span className="ml-2 text-[13px] font-semibold text-text">{c.mine ? "You" : "Voice"}</span>
      )}
      <span className="ml-2 text-[12px] text-faint">{hm(c.time)}</span>
      {heardLang && <span className="ml-2"><LangPill from={heardLang} to={to} /></span>}
      {person != null && <PersonDialog open={renaming} pid={pid} n={person} names={names} onClose={() => setRenaming(false)} />}
    </div>
  );
}

function PersonDialog({ open, pid, n, names, onClose }: { open: boolean; pid: string; n: number; names: Record<number, string>; onClose: () => void }) {
  return (
    <NameDialog open={open} title={`Name ${personLabel(n)}`} initial={names[n] ?? ""} onClose={onClose}
      placeholder="e.g. Khun Somchai" hint="Shown on all of this person's cards in this project"
      onDone={(name) => { renamePerson(pid, n, name); onClose(); }} />
  );
}

/** Who said this turn: the project's person (tap to name them), or Gemini's A/B when not matched by voice. */
function TurnLabel({ t, pid, names }: { t: Turn; pid: string; names: Record<number, string> }) {
  const [renaming, setRenaming] = useState(false);
  const p = t.person ?? null;
  if (p == null) return <div className="text-[13px] font-bold text-thai">{t.speaker}</div>;
  return (
    <>
      <button onClick={() => setRenaming(true)} className="block text-[13px] font-bold text-thai hover:underline">{personLabel(p, names)}</button>
      <PersonDialog open={renaming} pid={pid} n={p} names={names} onClose={() => setRenaming(false)} />
    </>
  );
}

function LangPill({ from, to }: { from: string; to: string }) {
  return (
    <span className="flex items-center rounded-[10px] bg-raised px-2 py-0.5 text-[11px]">
      <span className="font-semibold" style={{ color: langColor(from) }}>{from.toUpperCase()}</span>
      {to && <><span className="text-faint">&nbsp;→&nbsp;</span><span className="font-semibold" style={{ color: langColor(to) }}>{to.toUpperCase()}</span></>}
    </span>
  );
}

function NoteBox({ text, icon }: { text: string; icon: ReactNode }) {
  return (
    <div className="mt-2.5 flex w-full rounded-[12px] bg-bg p-3">
      <span className="mt-0.5 shrink-0">{icon}</span>
      <span className="ml-2 whitespace-pre-wrap text-[14px] leading-5 text-muted">{text}</span>
    </div>
  );
}

/** Ask fixed this piece after re-listening: what it said before, why, and a way back. */
function CorrectionBox({ pid, id, k }: { pid: string; id: number; k: Revision }) {
  const [open, setOpen] = useState(false);
  const before = k.heard.trim() ? k.heard : k.turns.map((t) => `${t.speaker}: ${t.heard}`).join(" / ");
  const beforeTr = k.translation.trim() ? k.translation : k.turns.map((t) => `${t.speaker}: ${t.translation}`).join(" / ");
  return (
    <div role="button" tabIndex={0} onClick={() => setOpen(!open)} onKeyDown={(e) => { if (e.key === "Enter") setOpen(!open); }}
      className="mt-2.5 w-full cursor-pointer rounded-[12px] border border-ok/35 bg-bg p-3">
      <div className="flex items-center">
        <Sparkles size={16} className="shrink-0 text-ok" />
        <span className="ml-2 flex-1 text-[14px] leading-5 text-muted">Corrected by Gemini · {k.reason}</span>
        <button onClick={(e) => { e.stopPropagation(); undoCorrection(pid, id); }} className="rounded-lg px-2 py-1 text-[14px] font-medium text-english hover:bg-raised">Undo</button>
      </div>
      {open && (
        <div className="mt-2 text-[14px] leading-5 text-faint">
          <div className="text-[12px]">Before</div>
          <div>{before}</div>
          {beforeTr.trim() && <div>{beforeTr}</div>}
        </div>
      )}
    </div>
  );
}

/** Reads text (a translation) aloud in its language. Tap again to stop. */
function SayButton({ text, k, saved }: { text: string; k: string; saved: boolean }) {
  const on = usePlay((s) => s.saying === k);
  const lang = Lang.of(text);
  const color = langColor(lang);
  return (
    <button onClick={() => playAll.say(text, k)} aria-label={on ? "Stop" : `Speak the ${Lang.name(lang)} translation`}
      className="flex items-center rounded-2xl py-1.5 pl-2 pr-2.5"
      style={{ background: on ? `color-mix(in srgb, ${color} 20%, transparent)` : "var(--ll-control)" }}>
      {on ? <Square size={16} className="fill-current" style={{ color }} /> : <Volume2 size={18} style={{ color }} />}
      <span className="ml-1 text-[12px] font-semibold" style={{ color }}>{lang.toUpperCase()}</span>
      {saved && !on && <CircleCheck size={14} className="ml-1 text-faint" aria-label="Voice saved, plays offline" />}
    </button>
  );
}

const engineLabel = (c: Caption) => (c.engine.startsWith("cloud:") ? "Gemini" : c.engine.startsWith("device:") || c.offline ? "On device" : "");

function BubbleBody({ c, pid, playing, names }: { c: Caption; pid: string; playing: boolean; names: Record<number, string> }) {
  const [explaining, setExplaining] = useState(false);
  const replaying = useReplay((s) => s.id === c.id);
  const done = isDone(c);
  const correction = correctionOf(c);
  const spoken = spokenText(c);
  const mineIsPlaying = replaying || playing;

  const runExplain = async () => {
    setExplaining(true);
    try {
      const a = await explain(c.heard, c.translation);
      updateCaption(pid, c.id, (x) => ({ ...x, explanation: a.text, explainSource: a.source }));
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      updateCaption(pid, c.id, (x) => ({ ...x, explanation: `Couldn't explain: ${msg.slice(0, 100)}`, explainSource: "" }));
    }
    setExplaining(false);
  };

  const original = c.heard.trim() || (!done ? c.preview.trim() : "");
  return (
    <>
      {c.turns.length > 0 ? (
        // More than one voice inside this piece: who said what.
        <div className="flex flex-col gap-2.5">
          {c.turns.map((t, i) => (
            <div key={i}>
              <TurnLabel t={t} pid={pid} names={names} />
              <div className="text-[15px] leading-[22px] text-muted">{t.heard.trim()}</div>
              {t.translation.trim() && <div className="text-[18px] font-medium leading-[26px] text-text">{t.translation.trim()}</div>}
            </div>
          ))}
        </div>
      ) : (
        <>
          {original && (
            <>
              <div className="text-[15px] leading-[22px] text-muted">{original}</div>
              <div className={cn("my-2.5 h-px w-full", c.mine ? "bg-mine-line" : "bg-line")} />
            </>
          )}
          {c.translation.trim() ? <div className="text-[19px] font-medium leading-[27px] text-text">{c.translation.trim()}</div>
            : c.status === "QUEUED" ? <PendingLine text="Waiting to translate" color="var(--ll-thai)" />
            : c.status === "RECORDING" ? <PendingLine text="Recording" color="var(--ll-thai)" />
            : <PendingLine text="Translating" color="var(--ll-english)" />}
        </>
      )}
      {c.note && !done && <NoteBox text={c.note} icon={<Info size={16} className="text-thai" />} />}
      {correction && <CorrectionBox pid={pid} id={c.id} k={correction} />}
      {c.explanation != null && (
        <NoteBox text={c.explanation + (c.explainSource?.trim() ? `\n— ${c.explainSource}` : "")} icon={<Sparkles size={16} className="text-english" />} />
      )}
      <div className="mt-2 flex w-full flex-wrap items-center gap-y-1">
        {c.audioFile && (
          <button aria-label="Play original audio"
            onClick={() => (replaying ? nav.stopReplay() : nav.replay(c.audioFile!, c.heard, c.id))}
            className={cn("mr-2 flex h-8 w-8 items-center justify-center rounded-full", mineIsPlaying ? "bg-english text-bg" : "bg-control text-text hover:bg-raised")}>
            {mineIsPlaying ? <Square size={14} className="fill-current" /> : <Play size={16} className="fill-current" />}
          </button>
        )}
        {(c.audioFile || c.durationMs > 0) && <span className="text-[12px] text-faint">{(c.durationMs / 1000).toFixed(1)} s</span>}
        {done && spoken && <span className="ml-2.5"><SayButton text={spoken} k={String(c.id)} saved={savedVoice(c, spoken) != null} /></span>}
        {done && c.explanation == null && c.translation.trim() && (
          <button disabled={explaining} onClick={runExplain} className="ml-2.5 rounded-[10px] px-1.5 py-1 text-[12px] font-medium text-english hover:bg-raised">
            {explaining ? "Thinking…" : "What did they mean?"}
          </button>
        )}
        <span className="ml-auto truncate pl-2 text-[12px] text-faint">{engineLabel(c)}</span>
      </div>
    </>
  );
}

// ---------- Play all player ----------

/** Plays the project in order: original voices, or the translations spoken. The mic keeps listening. */
function PlayerBar({ wide }: { wide: boolean }) {
  const mode = usePlay((s) => s.mode);
  const fast = usePlay((s) => s.fast);
  const [i, n] = usePlay((s) => s.position);
  const progress = n > 0 ? i / n : 0;
  const transport = (
    <div className="flex items-center">
      <button aria-label="Previous" onClick={() => playAll.previous()} className="flex h-12 w-12 items-center justify-center rounded-full text-text hover:bg-raised"><SkipBack size={22} className="fill-current" /></button>
      <button aria-label="Stop playing" onClick={() => playAll.stop()} className="flex h-14 w-14 items-center justify-center rounded-full bg-english text-bg"><Square size={22} className="fill-current" /></button>
      <button aria-label="Next" onClick={() => playAll.next()} className="flex h-12 w-12 items-center justify-center rounded-full text-text hover:bg-raised"><SkipForward size={22} className="fill-current" /></button>
    </div>
  );
  const label = `Playing ${i} of ${n}`;
  return (
    <div className={cn("flex w-full justify-center", wide && "px-6 pb-2.5")}>
      <div className={cn("w-full max-w-[860px] border border-line-strong bg-surface px-4 py-3", wide ? "rounded-[24px]" : "rounded-t-[24px]")}>
        <div className="h-[3px] w-full overflow-hidden rounded-sm bg-line">
          <div className="h-full bg-english transition-[width] duration-300" style={{ width: `${progress * 100}%` }} />
        </div>
        <div className="mt-2.5">
          {wide ? (
            <div className="flex items-center">
              {transport}
              <span className="ml-2 flex-1 truncate text-[12px] text-muted">{label}</span>
              <ModeSegments mode={mode} />
              <span className="ml-2"><SpeedPill fast={fast} /></span>
            </div>
          ) : (
            <>
              <div className="flex items-center"><span className="flex-1 text-[12px] text-muted">{label}</span><SpeedPill fast={fast} /></div>
              <div className="mt-1.5 flex items-center">
                <ModeSegments mode={mode} className="flex-1" />
                <span className="ml-2">{transport}</span>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function ModeSegments({ mode, className }: { mode: PlayMode; className?: string }) {
  const opts: [PlayMode, string][] = [["ORIGINAL", "Original voices"], ["TRANSLATION", "Translations spoken"]];
  return (
    <div className={cn("flex rounded-[12px] bg-bg p-[3px]", className)}>
      {opts.map(([m, text]) => (
        <button key={m} onClick={() => playAll.setMode(m)}
          className={cn("flex-1 truncate rounded-[9px] px-2.5 py-[7px] text-center text-[12px]", m === mode ? "bg-selected font-semibold text-text" : "text-muted")}>
          {text}
        </button>
      ))}
    </div>
  );
}

function SpeedPill({ fast }: { fast: boolean }) {
  return (
    <button onClick={() => playAll.toggleSpeed()} className="rounded-[12px] bg-raised px-3 py-[7px] text-[12px] font-semibold text-text hover:bg-control">
      {fast ? "1.5×" : "1×"}
    </button>
  );
}

// ---------- Live level ----------

/** Live mic level, last few seconds. Blue = speech being recorded, amber = mic muted for our own audio. */
function WaveBars({ running, className }: { running: boolean; className?: string }) {
  const bars = useApp((s) => s.waveform);
  const muted = useApp((s) => s.ttsSpeaking);
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current;
    if (!cv) return;
    const dpr = devicePixelRatio || 1;
    const w = cv.clientWidth, h = cv.clientHeight;
    if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
    const g = cv.getContext("2d");
    if (!g) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const css = getComputedStyle(cv);
    const col = (v: string) => css.getPropertyValue(v).trim();
    const idle = col("--ll-idle"), english = col("--ll-english"), thai = col("--ll-thai");
    const barW = 3, gap = 2;
    const n = Math.max(1, Math.floor(w / (barW + gap)));
    const shown = bars.slice(-n);
    const offset = n - shown.length;
    for (let i = 0; i < n; i++) {
      const b = shown[i - offset];
      const level = running && b ? b.level : 0;
      const bh = Math.min(h, Math.max(0.06, level) * h);
      g.fillStyle = !running || !b ? idle : muted ? thai : b.speech ? english : idle;
      g.beginPath();
      g.roundRect(i * (barW + gap), h / 2 - bh / 2, barW, bh, barW / 2);
      g.fill();
    }
  });
  return <canvas ref={ref} className={className} aria-hidden />;
}

// ---------- Input bar ----------

/**
 * The one input. Left: listen to the people around (or tab audio), live. Middle: type your side.
 * Right: hold to speak your side, or send what you typed. Everything lands in the same transcript.
 */
function InputBar({ pid, pairCode, running, elsewhere, wide }: { pid: string; pairCode: string; running: boolean; elsewhere?: Project; wide: boolean }) {
  const held = useApp((s) => s.pushToTalk);
  const ptt = useApp((s) => s.pushToTalkStatus);
  const speak = useApp((s) => s.speakAloud);
  const source = useApp((s) => s.runningSource);
  const chosen = useApp((s) => s.source);
  const elapsed = useElapsed(running);
  const [text, setText] = useState("");
  const pair = pairOf(pairCode);
  const canHold = running && source !== "PHONE";

  const status = held && pair.code !== TH_EN.code ? "Speak now… release to translate"
    : held ? ptt.trim() || "Speak English now… release to translate"
    : running && source === "PHONE" ? `Phone audio · ${pair.label} · ${elapsed}`
    : running ? `Listening · ${pair.label} auto-detected · ${elapsed}`
    : "Tap the mic to start listening";
  const hint = held || running ? status : "Type to translate, or tap to listen";

  const send = () => {
    const input = text.trim();
    if (!input) return;
    setText("");
    sendTyped(pid, input);
  };

  const phone = (running ? source : chosen) === "PHONE";
  const toggleSource = async () => {
    setApp({ source: phone ? "MIC" : "PHONE" });
    if (running) {
      await nav.toggleListening(); // stop
      await nav.toggleListening(); // start on the new source
    }
  };

  const release = () => { if (app().pushToTalk) setPushToTalk(false); };

  return (
    <div className={cn("flex w-full justify-center pb-[max(10px,env(safe-area-inset-bottom))] pt-2.5", wide ? "px-6" : "px-3")}>
      <div className={cn("flex w-full max-w-[860px] items-center rounded-[30px] border bg-surface p-1.5", running ? "border-english/45" : "border-line-strong")}>
        <ListenButton running={running} />
        <div className="relative ml-3 flex min-h-11 min-w-0 flex-1 items-center">
          {running && !text && <WaveBars running={running} className="pointer-events-none absolute inset-x-0 top-1/2 h-[26px] w-full -translate-y-1/2 opacity-30" />}
          {elsewhere && !text && (
            // Another project records: say so, and that the listen button moves the recording here.
            <div className="pointer-events-none absolute inset-x-0 top-1/2 -translate-y-1/2 leading-[18px]">
              <div className="flex items-center text-[13px] text-muted">
                <span className="mr-1.5 h-[7px] w-[7px] shrink-0 rounded-full bg-live" />
                <span className="truncate">Recording in {shownName(elsewhere)}</span>
              </div>
              <div className="truncate text-[12px] text-faint">{wide ? "Type to translate, or tap to record here instead" : "Type, or tap to switch"}</div>
            </div>
          )}
          <textarea
            value={text} rows={1} aria-label="Type to translate"
            placeholder={elsewhere ? "" : hint}
            onChange={(e) => setText(e.target.value.replace(/\n/g, " "))}
            onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
            className={cn(
              "field-sizing-content relative max-h-[104px] w-full resize-none bg-transparent py-2.5 text-[16px] text-text caret-english",
              held ? "placeholder:text-text" : running ? "placeholder:text-muted" : "placeholder:text-faint",
              "placeholder:truncate",
            )}
          />
        </div>
        {!text.trim() && (
          <>
            <BarIcon label={phone ? "Listening to tab or phone audio" : "Listening to people in the room"} active={phone} onClick={toggleSource}>
              {phone ? <Monitor size={20} /> : <Users size={20} />}
            </BarIcon>
            <BarIcon label={speak ? "Translations are read aloud" : "Captions only"} active={speak} onClick={() => setApp({ speakAloud: !speak })}>
              {speak ? <Volume2 size={20} /> : <VolumeX size={20} />}
            </BarIcon>
          </>
        )}
        <div className="w-1" />
        {text.trim() ? (
          <button aria-label="Translate" onClick={send} className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-english text-bg">
            <Send size={20} />
          </button>
        ) : (
          // Your voice. Needs listening on (mic), because it goes through the same pipeline.
          <button
            aria-label="Hold to speak"
            onPointerDown={(e) => {
              if (!canHold) { toast(running ? "Hold to speak works with the mic, not phone audio" : elsewhere ? "Recording in another project. Move it here to hold and speak" : "Tap listen first, then hold to speak"); return; }
              e.currentTarget.setPointerCapture(e.pointerId);
              setPushToTalk(true);
            }}
            onPointerUp={release} onPointerCancel={release} onLostPointerCapture={release}
            onContextMenu={(e) => e.preventDefault()}
            className={cn("flex h-12 w-12 shrink-0 touch-none select-none items-center justify-center rounded-full",
              held ? "bg-live text-bg" : canHold ? "bg-raised text-text" : "bg-raised text-faint")}
          >
            <Mic size={22} />
          </button>
        )}
      </div>
    </div>
  );
}

/** Starts or stops live listening. Ear when idle; a stop square with a soft pulse while live. */
function ListenButton({ running }: { running: boolean }) {
  return (
    <div className="relative flex h-12 w-12 shrink-0 items-center justify-center">
      {running && <div className="absolute h-12 w-12 rounded-full bg-english/16 animate-[ll-pulse_1100ms_ease-in-out_infinite_alternate]" />}
      <button aria-label={running ? "Stop listening" : "Start listening"} onClick={() => nav.toggleListening()}
        className={cn("relative flex h-12 w-12 items-center justify-center rounded-full", running ? "bg-english" : "bg-raised text-text hover:bg-control")}>
        {running ? <span className="h-3.5 w-3.5 rounded-[3px] bg-bg" /> : <Ear size={22} />}
      </button>
    </div>
  );
}

function BarIcon({ label, active, onClick, children }: { label: string; active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button aria-label={label} title={label} onClick={onClick}
      className={cn("flex h-10 w-10 shrink-0 items-center justify-center rounded-full hover:bg-raised", active ? "text-english" : "text-faint")}>
      {children}
    </button>
  );
}

// ---------- Engines sheet (diagnostics) ----------

function Chip({ text, color }: { text: string; color: string }) {
  return (
    <span className="whitespace-nowrap rounded-lg px-2 py-[3px] text-[11px] font-medium"
      style={{ color, background: `color-mix(in srgb, ${color} 12%, transparent)` }}>{text}</span>
  );
}

function EnginePanel({ captions }: { captions: Caption[] }) {
  const s = useApp();
  const count = (st: Caption["status"]) => captions.filter((c) => c.status === st).length;
  return (
    <div className="flex max-h-[85dvh] flex-col gap-2.5 overflow-y-auto px-5 pb-7 pt-5">
      <div className="mx-auto mb-2 h-1 w-8 rounded-full bg-line-strong" />
      <div className="text-[18px] font-bold text-text">Engines</div>
      <div className="flex items-center">
        <span className={cn("h-2.5 w-2.5 rounded-full", s.ttsSpeaking ? "bg-thai" : s.speechActive ? "bg-english" : "bg-idle")} />
        <span className="ml-2 text-[14px] text-text">{s.ttsSpeaking ? "Mic muted while the app plays audio" : s.speechActive ? "Speech detected" : "Silence"}</span>
      </div>
      <WaveBars running={s.running} className="h-10 w-full" />
      <div className="flex items-center">
        <span className="w-[150px] text-[12px] text-muted">Voice (Silero VAD)</span>
        <div className="h-1.5 flex-1 overflow-hidden rounded-[3px] bg-line">
          <div className="h-full bg-english transition-[width] duration-150" style={{ width: `${Math.min(1, Math.max(0, s.vadProbability)) * 100}%` }} />
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5">
        <Chip text={`Recording ${count("RECORDING")}`} color="var(--ll-live)" />
        <Chip text={`Queued ${count("QUEUED")}`} color="var(--ll-thai)" />
        <Chip text={`Translating ${count("TRANSLATING")}`} color="var(--ll-english)" />
        <Chip text={`Done ${count("DONE")}`} color="var(--ll-ok)" />
        <Chip text={`Boost ×${s.inputGain.toFixed(1)}`} color="var(--ll-muted)" />
      </div>
      <div className="h-px w-full bg-line" />
      <KeyValue k="Voice detector" v={s.vadStatus} />
      <KeyValue k="Interpreter · Gemini Live" v={s.liveStatus} />
      <KeyValue k="Hybrid · Firebase AI Logic" v={s.nanoStatus} />
      <KeyValue k="Offline translator" v={s.offlineStatus} />
      <KeyValue k="Who is speaking" v={s.speakerStatus} />
    </div>
  );
}
