"use client";
/** Chat with Gemini about the open project. It reads the transcript, re-listens to recordings and searches the web. Port of AskPanel.kt. */
import { Fragment, useEffect, useRef, useState, type ReactNode } from "react";
import { History, Plus, Send, Sparkles, X } from "lucide-react";
import { cn } from "@/lib/cn";
import { useAsk, ask, newChat, openThread, setEngine, type Engine, type Msg, type Thread } from "@/lib/ask";
import { projectOf, useApp } from "@/lib/state";
import { pairOf } from "@/lib/lang";
import { dMon, hm } from "@/lib/format";
import { Spinner } from "./ui";

/** cloud: needs cloud Gemini (listens to recordings), so it's off on Nano. */
const suggestions = [
  { text: "Summarise this conversation" },
  { text: "What was their tone? Listen to the recordings", cloud: true },
  { text: "Check the transcripts against the recordings and fix mistakes", cloud: true },
  { text: "What should I reply in Thai?" },
];

function useOnline() {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    const f = () => setOnline(navigator.onLine);
    f();
    addEventListener("online", f); addEventListener("offline", f);
    return () => { removeEventListener("online", f); removeEventListener("offline", f); };
  }, []);
  return online;
}

export function AskPanel({ onClose, className }: { onClose: () => void; className?: string }) {
  const { messages, busy, activity, liveSteps, threads, currentThread, engine } = useAsk();
  const nanoReady = useApp((s) => s.nanoReady);
  const nano = nanoReady && engine === "nano";
  const burmeseOnly = useApp((s) => pairOf(projectOf(s.viewId)?.pair).transcribeOnly);
  const [showChats, setShowChats] = useState(false);
  const [input, setInput] = useState("");
  const online = useOnline();
  const usable = online || nano;
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = listRef.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
  }, [messages.length, busy, liveSteps.length]);

  const send = (text: string) => {
    if (!text.trim() || busy) return;
    ask(text.trim());
    setInput("");
  };
  const canSend = !!input.trim() && !busy;

  return (
    <div className={cn("flex flex-col bg-surface", className)}>
      <div className="flex min-h-16 items-center py-2 pl-[18px] pr-2">
        <div className="flex h-8 w-8 items-center justify-center rounded-full bg-selected"><Sparkles size={18} className="text-english" /></div>
        <div className="ml-2.5 flex-1">
          <div className="text-[16px] font-semibold text-text">Ask</div>
          <div className={cn("text-[12px]", usable ? "text-faint" : "text-thai")}>
            {nano ? "Gemini Nano · on this device" : online ? "Gemini · reads and listens to this project" : "Offline · needs internet"}
          </div>
        </div>
        {threads.length > 0 && (
          <button aria-label="Chats" disabled={busy} onClick={() => setShowChats(!showChats)}
            className={cn("flex h-12 w-12 items-center justify-center rounded-full hover:bg-raised disabled:opacity-40", showChats ? "text-english" : "text-muted")}>
            <History size={22} />
          </button>
        )}
        {(messages.length > 0 || showChats) && (
          <button aria-label="New chat" disabled={busy} onClick={() => { newChat(); setShowChats(false); }}
            className="flex h-12 w-12 items-center justify-center rounded-full text-muted hover:bg-raised disabled:opacity-40">
            <Plus size={22} />
          </button>
        )}
        <button aria-label="Close Ask" onClick={onClose} className="flex h-12 w-12 items-center justify-center rounded-full text-muted hover:bg-raised">
          <X size={22} />
        </button>
      </div>
      <div className="h-px w-full bg-line" />
      {nanoReady && !showChats && <EngineSwitch engine={engine} busy={busy} />}

      {showChats ? (
        <ChatList threads={threads} current={currentThread} onOpen={(id) => { openThread(id); setShowChats(false); }} />
      ) : (
        <div ref={listRef} className="flex min-h-0 flex-1 flex-col gap-3.5 overflow-y-auto p-4">
          {messages.length === 0 && (
            <div>
              <div className="text-[15px] font-medium text-text">Ask anything about this conversation</div>
              <p className="mt-1 text-[13px] leading-[18px] text-muted">
                Gemini can read every piece, listen to the original voices again for tone and missed words, and look things up.
              </p>
              <div className="mt-3.5 flex flex-wrap gap-2">
                {suggestions.map((x) => (burmeseOnly && x.text.includes("in Thai") ? { ...x, text: "What should I reply in Burmese?" } : x)).map((s) => (
                  <button key={s.text} disabled={!usable || busy || (nano && s.cloud)} onClick={() => send(s.text)}
                    className="rounded-2xl border border-line-strong bg-raised px-3 py-2 text-left text-[13px] text-english-soft hover:bg-control disabled:opacity-50">
                    {s.text}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m, i) => <AskMessage key={i} m={m} />)}
          {busy && (
            <div>
              {liveSteps.length > 0 && <div className="mb-2.5 flex flex-wrap gap-1.5">{liveSteps.map((s, i) => <StepChip key={i} label={s} />)}</div>}
              <div className="flex items-center gap-2.5"><Spinner /><span className="text-[13px] text-muted">{activity || "Thinking…"}</span></div>
            </div>
          )}
        </div>
      )}

      <form className="flex items-end gap-2 p-3" onSubmit={(e) => { e.preventDefault(); send(input); }}>
        <textarea
          value={input} rows={1} onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(input); } }}
          placeholder={usable ? "Ask about this conversation…" : "Needs internet"} aria-label="Ask about this conversation"
          className="field-sizing-content max-h-[132px] min-h-11 flex-1 resize-none rounded-[22px] border border-line-strong bg-raised px-4 py-[11px] text-[15px] text-text caret-english"
        />
        <button type="submit" aria-label="Send" disabled={!canSend}
          className={cn("flex h-11 w-11 shrink-0 items-center justify-center rounded-full", canSend ? "bg-english text-bg" : "bg-control text-faint")}>
          <Send size={20} />
        </button>
      </form>
    </div>
  );
}

/** Cloud Gemini or Gemini Nano; shown only when Chrome has Nano ready. */
function EngineSwitch({ engine, busy }: { engine: Engine; busy: boolean }) {
  const opt = (e: Engine, label: string) => (
    <button role="radio" aria-checked={engine === e} disabled={busy} onClick={() => setEngine(e)}
      className={cn("h-6 rounded-full px-2.5 text-[12px] font-medium disabled:opacity-50",
        engine === e ? "bg-english text-bg forced-colors:outline-2 forced-colors:outline-solid forced-colors:outline-[Highlight] forced-colors:font-bold" : "text-muted hover:text-text")}>
      {label}
    </button>
  );
  return (
    <div className="flex items-center gap-2.5 border-b border-line px-4 py-1.5">
      <div role="radiogroup" aria-label="Ask model" className="inline-flex shrink-0 rounded-full border border-line-strong bg-raised p-px">
        {opt("cloud", "Cloud")}
        {opt("nano", "Nano")}
      </div>
      {engine === "nano" && <span className="truncate text-[11px] text-faint">Text only · no listening or web</span>}
    </div>
  );
}

/** This project's saved chats, newest first. */
function ChatList({ threads, current, onOpen }: { threads: Thread[]; current: number | null; onOpen: (id: number) => void }) {
  return (
    <div className="min-h-0 flex-1 overflow-y-auto py-2">
      {threads.map((t) => {
        const n = t.messages.filter((m) => m.role === "USER").length;
        return (
          <button key={t.id} onClick={() => onOpen(t.id)}
            className={cn("block w-full px-[18px] py-3 text-left hover:bg-raised", t.id === current ? "bg-selected" : "bg-surface")}>
            <div className="line-clamp-2 text-[15px] text-text">{t.title || "Chat"}</div>
            <div className="mt-0.5 text-[12px] text-faint">{dMon(t.updated)} · {hm(t.updated)} · {n} question{n === 1 ? "" : "s"}</div>
          </button>
        );
      })}
    </div>
  );
}

function AskMessage({ m }: { m: Msg }) {
  if (m.role === "USER") {
    return (
      <div className="flex justify-end">
        <div className="max-w-[320px] whitespace-pre-wrap rounded-[18px_6px_18px_18px] border border-mine-line bg-mine-bubble px-3.5 py-2.5 text-[15px] leading-[21px] text-text">
          {m.text}
        </div>
      </div>
    );
  }
  return (
    <div>
      {!!m.steps?.length && <div className="mb-2 flex flex-wrap gap-1.5">{m.steps.map((s, i) => <StepChip key={i} label={s} />)}</div>}
      <div className={cn("select-text whitespace-pre-wrap break-words text-[15px] leading-[22px]", m.error ? "text-danger" : "text-text")}>
        {markdown(m.text)}
      </div>
      {m.source && <div className="mt-1 text-[12px] text-faint">— {m.source}</div>}
    </div>
  );
}

function StepChip({ label }: { label: string }) {
  const color = label.startsWith("Corrected") ? "bg-ok" : label.startsWith("Listened") ? "bg-thai" : "bg-english";
  return (
    <div className="flex max-w-full items-center rounded-xl bg-raised px-[9px] py-[5px]">
      <span className={cn("h-1.5 w-1.5 shrink-0 rounded-full", color)} />
      <span className="ml-1.5 truncate text-[12px] text-muted">{label}</span>
    </div>
  );
}

/** Just enough Markdown for Gemini's answers: **bold**, *italic*, bullets and headings. */
function markdown(src: string): ReactNode {
  return src.split("\n").map((raw, i) => {
    let line = raw;
    const heading = line.trimStart().startsWith("#");
    if (heading) line = line.trimStart().replace(/^#+/, "").trimStart();
    const bullet = /^(\s*)[*-] /.exec(line);
    if (bullet) line = bullet[1] + "•  " + line.slice(bullet[0].length);
    const body = line.split("**").map((part, j) => {
      const inner = part.split("*").map((p, k) => (k % 2 === 1 ? <em key={k}>{p}</em> : <Fragment key={k}>{p}</Fragment>));
      return j % 2 === 1 ? <strong key={j} className="font-semibold">{inner}</strong> : <Fragment key={j}>{inner}</Fragment>;
    });
    return <Fragment key={i}>{i > 0 && "\n"}{heading ? <strong className="font-semibold">{body}</strong> : body}</Fragment>;
  });
}
