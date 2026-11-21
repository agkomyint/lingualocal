"use client";
/** Find & jump: search what was said, show one person's pieces, or jump to a time. Port of FindBar.kt. */
import { Search, X } from "lucide-react";
import { cn } from "@/lib/cn";
import { label as personLabel } from "@/lib/people";
import { eeeDMon, hm, dMon, hms } from "@/lib/format";
import type { ClockHit, TimeMark } from "@/lib/find";

function FindChip({ text, selected, strong, onClick }: { text: string; selected: boolean; strong?: boolean; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "h-8 shrink-0 whitespace-nowrap rounded-2xl border px-3 text-[13px]",
        selected ? "border-mine-line bg-selected font-semibold text-english" : "border-line-strong bg-raised",
        !selected && (strong ? "font-semibold text-text" : "text-muted"),
      )}
    >
      {text}
    </button>
  );
}

export function FindBar(props: {
  query: string; onQuery: (q: string) => void;
  person: number | null; onPerson: (p: number | null) => void;
  mine: boolean; onMine: (m: boolean) => void;
  people: number[]; hasMine: boolean; names: Record<number, string>;
  marks: TimeMark[]; onJump: (m: TimeMark) => void;
  results: number | null; onClose: () => void;
  clock: ClockHit[] | null; onClockGo: (h: ClockHit) => void; onClockPlay: (h: ClockHit) => void;
}) {
  const { query, onQuery, person, onPerson, mine, onMine, people, hasMine, names, marks, onJump, results, onClose, clock, onClockGo, onClockPlay } = props;
  return (
    <>
      <div className="w-full bg-surface px-4 py-2.5">
        <div className="flex items-center">
          <label className="flex h-11 flex-1 items-center rounded-[22px] border border-line-strong bg-raised px-3.5">
            <Search size={18} className="shrink-0 text-muted" />
            <input
              autoFocus value={query} onChange={(e) => onQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Escape") onClose(); }}
              placeholder="Search words, or type a time like 8:35" aria-label="Search words, or type a time"
              className="ml-2.5 h-full min-w-0 flex-1 text-[15px] text-text caret-english"
            />
            {query && (
              <button aria-label="Clear" onClick={() => onQuery("")} className="rounded-full text-muted"><X size={20} /></button>
            )}
          </label>
          <button aria-label="Close find" onClick={onClose} className="ml-2 flex h-10 w-10 items-center justify-center rounded-full text-text hover:bg-raised">
            <X size={22} />
          </button>
        </div>
        {(people.length > 0 || hasMine) && (
          <div className="no-scrollbar mt-2.5 flex gap-2 overflow-x-auto">
            <FindChip text="Everyone" selected={person == null && !mine} onClick={() => { onPerson(null); onMine(false); }} />
            {people.map((n) => (
              <FindChip key={n} text={personLabel(n, names)} selected={person === n} onClick={() => { onMine(false); onPerson(person === n ? null : n); }} />
            ))}
            {hasMine && <FindChip text="Me (push-to-talk)" selected={mine} onClick={() => { onPerson(null); onMine(!mine); }} />}
          </div>
        )}
        {clock != null ? (
          <div className="mt-2.5">
            {clock.length === 0 && <div className="text-[13px] text-muted">Nothing was recorded at that time</div>}
            {clock.map((hit) => (
              <div key={hit.time + "-" + hit.session} className="flex items-center gap-2 py-[3px]">
                <div className="min-w-0 flex-1">
                  <div className="text-[15px] font-semibold text-text">{eeeDMon(hit.time)} · {hm(hit.time)}</div>
                  <div className="text-[12px] text-muted">Session {hit.session} · started {hm(hit.sessionStart)}</div>
                </div>
                <FindChip text="Go to" selected={false} strong onClick={() => onClockGo(hit)} />
                <FindChip text="▶  Play from here" selected onClick={() => onClockPlay(hit)} />
              </div>
            ))}
          </div>
        ) : results != null ? (
          <div className="mt-2.5 text-[13px] text-muted">
            {results === 0 ? "Nothing found" : `${results} ${results === 1 ? "piece" : "pieces"} · tap “Go to” to see it in the conversation`}
          </div>
        ) : marks.length > 0 ? (
          <div className="mt-2.5">
            <div className="text-[11px] font-semibold tracking-[1px] text-faint">JUMP TO</div>
            <div className="no-scrollbar mt-1.5 flex gap-2 overflow-x-auto">
              {marks.map((m) => <FindChip key={m.label + m.time} text={m.label} selected={false} strong={m.session} onClick={() => onJump(m)} />)}
              <FindChip text="Latest" selected={false} strong onClick={() => onJump({ label: "Latest", time: Number.MAX_SAFE_INTEGER, session: true })} />
            </div>
          </div>
        ) : null}
      </div>
      <div className="h-px w-full bg-line" />
    </>
  );
}

/** Above a search result: its time, and a button to see it in the whole conversation. */
export function GoToRow({ time, onGo }: { time: number; onGo: () => void }) {
  return (
    <div className="flex items-center">
      <span className="flex-1 text-[12px] text-faint">{dMon(time)} · {hms(time)}</span>
      <button onClick={onGo} className="rounded-lg px-2 py-1 text-[13px] font-semibold text-english hover:bg-raised">Go to →</button>
    </div>
  );
}
