"use client";
/** Projects home (phones, portrait tablets), the sidebar (wide screens), settings and the project dialogs. Port of LinguaScreen.kt. */
import { useEffect, useState } from "react";
import { create } from "zustand";
import { DropdownMenu } from "radix-ui";
import { Mic, Plus, Search, Settings as SettingsIcon, MoreVertical } from "lucide-react";
import { cn } from "@/lib/cn";
import { useApp, setApp, app } from "@/lib/state";
import { PAIRS, pairOf, langColor } from "@/lib/lang";
import { deleteProject, renameProject } from "@/lib/store";
import * as nav from "@/lib/nav";
import { ageGroup, dMonYear, hm, length } from "@/lib/format";
import type { Project } from "@/lib/types";
import { IconClose, KeyValue, Logo, Modal, NameDialog, SectionLabel, Switch, TextButton } from "./ui";

// ---------- Project dialogs ----------

interface Dialogs { naming: boolean; renaming: Project | null; deleting: Project | null; settings: boolean }
export const useDialogs = create<Dialogs>(() => ({ naming: false, renaming: null, deleting: null, settings: false }));
const setD = (p: Partial<Dialogs>) => useDialogs.setState(p);

export function ProjectDialogsHost() {
  const { naming, renaming, deleting, settings } = useDialogs();
  return (
    <>
      <NameDialog open={naming} title="New project" initial="" onClose={() => setD({ naming: false })}
        onDone={(n) => { setD({ naming: false }); nav.createAndOpen(n); }} />
      <NameDialog open={!!renaming} title="Rename project" initial={renaming?.name ?? ""} onClose={() => setD({ renaming: null })}
        onDone={(n) => { if (renaming) renameProject(renaming, n); setD({ renaming: null }); }} />
      <Modal open={!!deleting} onClose={() => setD({ deleting: null })} title={`Delete "${deleting?.name ?? ""}"?`}>
        <p className="text-[14px] leading-[21px] text-muted">
          All {deleting?.items ?? 0} recordings and translations in this project are deleted from this device. This can’t be undone.
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <TextButton onClick={() => setD({ deleting: null })}>Cancel</TextButton>
          <TextButton color="text-danger" onClick={() => {
            const p = deleting;
            if (!p) return;
            // Listening in it stops first; listening in another project carries on.
            if (app().running && app().liveId === p.id) nav.stopListening();
            if (app().viewId === p.id) nav.close();
            deleteProject(p);
            setD({ deleting: null });
          }}>Delete</TextButton>
        </div>
      </Modal>
      <SettingsDialog open={settings} onClose={() => setD({ settings: false })} />
    </>
  );
}

// ---------- Settings ----------

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

function HybridStatusCard() {
  const online = useOnline();
  const device = useApp((s) => s.offlineStatus.startsWith("Ready"));
  return (
    <div className="flex flex-col gap-2 rounded-[14px] border border-line bg-surface p-3">
      <div className="flex items-center gap-2">
        <span className={cn("h-2 w-2 rounded-full", online ? "bg-ok" : "bg-thai")} />
        <span className="text-[13px] font-semibold text-text">Hybrid translation</span>
      </div>
      <KeyValue k="Cloud" v={online ? "Gemini, online" : "Offline"} />
      <KeyValue k="Offline fallback" v={device ? "On-device, ready" : "Preparing…"} />
    </div>
  );
}

function SettingsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const speak = useApp((s) => s.speakAloud);
  return (
    <Modal open={open} onClose={onClose} title="Settings" hideTitle>
      <div className="flex flex-col gap-3.5">
        <div className="flex items-center">
          <div className="flex-1 text-[20px] font-bold text-text">Settings</div>
          <IconClose onClick={onClose} label="Close" />
        </div>
        <SectionLabel>Translation</SectionLabel>
        <HybridStatusCard />
        <SectionLabel>Voice</SectionLabel>
        <div className="flex items-center gap-3">
          <div className="flex-1">
            <div className="text-[15px] text-text">Speak translations</div>
            <div className="text-[12px] text-muted">Read each translation aloud as it arrives</div>
          </div>
          <Switch checked={speak} onChange={(v) => setApp({ speakAloud: v })} label="Speak translations" />
        </div>
        <div className="text-[12px] text-faint">LinguaLocal {process.env.NEXT_PUBLIC_APP_VERSION ?? "web"}</div>
      </div>
    </Modal>
  );
}

// ---------- Shared pieces ----------

/** The language pair for the next new project. Both directions are detected on their own. */
export function PairPicker() {
  const picked = useApp((s) => s.nextPair);
  const first = useApp((s) => s.projects[0]?.pair);
  const current = picked ?? pairOf(first);
  return (
    <div className="flex gap-0.5 rounded-[20px] bg-raised p-[3px]" role="radiogroup" aria-label="Language pair">
      {PAIRS.map((p) => {
        const on = p.code === current.code;
        return (
          <button
            key={p.code} role="radio" aria-checked={on} aria-label={p.label}
            onClick={(e) => { e.stopPropagation(); setApp({ nextPair: p }); }}
            className={cn("flex h-[34px] items-center rounded-[17px] px-3.5 text-[13px] font-bold", on && "bg-surface")}
          >
            <span style={{ color: on ? langColor(p.a) : "var(--ll-faint)" }}>{p.a.toUpperCase()}</span>
            <span className="font-normal text-faint">&nbsp;⇄&nbsp;</span>
            <span style={{ color: on ? langColor(p.b) : "var(--ll-faint)" }}>{p.b.toUpperCase()}</span>
          </button>
        );
      })}
    </div>
  );
}

function SearchField({ query, onChange, autoFocus }: { query: string; onChange: (q: string) => void; autoFocus?: boolean }) {
  return (
    <label className="mb-3 flex min-h-11 w-full items-center gap-2 rounded-[12px] border border-line bg-surface px-3">
      <Search size={18} className="shrink-0 text-faint" />
      <input
        autoFocus={autoFocus} value={query} onChange={(e) => onChange(e.target.value)} placeholder="Search projects…"
        aria-label="Search projects" className="h-11 flex-1 text-[14px] text-text caret-english"
      />
    </label>
  );
}

const shownTitle = (p: Project) => (p.name.startsWith("Untitled · ") ? "Untitled" : p.name);

/** A project: title, date and time underneath, length on the right. Right-click or ⋮ to rename or delete. */
function SideRow({ p, selected, live }: { p: Project; selected: boolean; live: boolean }) {
  const [menu, setMenu] = useState(false);
  return (
    <div className="group relative">
      <DropdownMenu.Root open={menu} onOpenChange={setMenu}>
        <div
          role="button" tabIndex={0}
          onClick={() => nav.open(p)}
          onKeyDown={(e) => { if (e.key === "Enter") nav.open(p); }}
          onContextMenu={(e) => { e.preventDefault(); setMenu(true); }}
          className={cn("flex w-full cursor-pointer items-center rounded-[10px] px-3 py-2.5 text-left hover:bg-raised/60", selected && "bg-raised hover:bg-raised")}
        >
          <div className="min-w-0 flex-1">
            <div className="truncate text-[15px] font-semibold text-text">{shownTitle(p)}</div>
            <div className="mt-0.5 flex items-center text-[12px]">
              <span className="flex-1 truncate text-faint">{dMonYear(p.created)} · {hm(p.created)}</span>
              {live ? (
                <>
                  <span className="mr-1.5 h-[7px] w-[7px] rounded-full bg-live" />
                  <span className="text-live">Live</span>
                  {p.totalMs > 0 && <span className="text-faint">&nbsp;· {length(p.totalMs)}</span>}
                </>
              ) : p.totalMs > 0 ? <span className="text-faint">{length(p.totalMs)}</span> : null}
            </div>
          </div>
          <DropdownMenu.Trigger asChild>
            <button
              aria-label={`More for ${shownTitle(p)}`} onClick={(e) => e.stopPropagation()}
              className="-mr-1 ml-1 flex h-8 w-8 items-center justify-center rounded-full text-faint opacity-0 hover:bg-control focus:opacity-100 group-hover:opacity-100 pointer-coarse:opacity-100"
            >
              <MoreVertical size={16} />
            </button>
          </DropdownMenu.Trigger>
        </div>
        <DropdownMenu.Portal>
          <DropdownMenu.Content align="end" sideOffset={4} className="z-50 min-w-[160px] rounded-[12px] bg-raised py-1.5 shadow-xl">
            <DropdownMenu.Item onSelect={() => setD({ renaming: p })} className="cursor-pointer px-4 py-2.5 text-[14px] text-text outline-none data-[highlighted]:bg-control">Rename</DropdownMenu.Item>
            <DropdownMenu.Item onSelect={() => setD({ deleting: p })} className="cursor-pointer px-4 py-2.5 text-[14px] text-danger outline-none data-[highlighted]:bg-control">Delete</DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
      <div className="mx-3 h-px bg-line" />
    </div>
  );
}

function StartCard() {
  return (
    <div
      role="button" tabIndex={0} onClick={() => nav.quickStart()} onKeyDown={(e) => { if (e.key === "Enter") nav.quickStart(); }}
      className="flex w-full cursor-pointer flex-col items-center rounded-[28px] border border-line-strong bg-surface px-5 pb-6 pt-7 hover:bg-[#171a21]"
    >
      <div className="relative flex h-32 w-32 items-center justify-center">
        <div className="absolute h-32 w-32 rounded-full bg-english/5" />
        <div className="absolute h-[108px] w-[108px] rounded-full bg-english/12" />
        <div className="relative flex h-[88px] w-[88px] items-center justify-center rounded-full bg-english">
          <Mic size={36} className="text-bg" />
        </div>
      </div>
      <div className="mt-3 text-[20px] font-bold text-text">Start listening</div>
      <div className="mt-5"><PairPicker /></div>
    </div>
  );
}

// ---------- Projects home ----------

export function ProjectsHome() {
  const projects = useApp((s) => s.projects);
  const [query, setQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const shown = projects.filter((p) => !query.trim() || p.name.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto w-full max-w-[720px] px-4 pb-4">
        <div className="flex items-center pb-3 pt-4">
          <Logo size={32} />
          <h1 className="ml-2.5 flex-1 text-[22px] font-bold tracking-[-0.4px] text-text">LinguaLocal</h1>
          <button aria-label="Search projects" onClick={() => { setSearching(!searching); if (searching) setQuery(""); }}
            className="flex h-12 w-12 items-center justify-center rounded-full bg-surface text-text hover:bg-raised">
            <Search size={22} />
          </button>
          <button aria-label="Settings" onClick={() => setD({ settings: true })}
            className="ml-2 flex h-12 w-12 items-center justify-center rounded-full bg-surface text-text hover:bg-raised">
            <SettingsIcon size={22} />
          </button>
        </div>
        {searching && <SearchField query={query} onChange={setQuery} autoFocus />}
        <StartCard />
        <div className="flex items-center pl-1 pt-5">
          <SectionLabel className="flex-1">Recent</SectionLabel>
          <TextButton color="text-english" bold onClick={() => setD({ naming: true })}>New project</TextButton>
        </div>
        {shown.length === 0 && (
          <p className="px-1 py-4 text-[14px] leading-[21px] text-muted">
            {query.trim() ? `No project matches "${query}".`
              : "No projects yet. A project keeps one situation together, like \"Landlord\" or \"Clinic visit\": every recording and translation, ready to play back."}
          </p>
        )}
        {shown.map((p) => <SideRow key={p.id} p={p} selected={false} live={false} />)}
      </div>
    </div>
  );
}

// ---------- Sidebar ----------

export function Sidebar() {
  const projects = useApp((s) => s.projects);
  const viewId = useApp((s) => s.viewId);
  const liveId = useApp((s) => (s.running ? s.liveId : null));
  const [query, setQuery] = useState("");
  const shown = projects.filter((p) => !query.trim() || p.name.toLowerCase().includes(query.trim().toLowerCase()));
  const groups = new Map<string, Project[]>();
  for (const p of shown) {
    const g = ageGroup(p.created);
    groups.set(g, [...(groups.get(g) ?? []), p]);
  }
  return (
    <aside className="flex h-full w-[300px] shrink-0 flex-col bg-side px-3.5 pb-3.5 pt-4">
      <div className="flex items-center px-1.5">
        <Logo size={28} />
        <div className="ml-2.5 flex-1 text-[17px] font-bold text-text">LinguaLocal</div>
        <button aria-label="Settings" onClick={() => setD({ settings: true })}
          className="flex h-12 w-12 items-center justify-center rounded-full text-muted hover:bg-raised">
          <SettingsIcon size={22} />
        </button>
        <button aria-label="New project" onClick={() => setD({ naming: true })}
          className="ml-1 flex h-11 w-11 items-center justify-center rounded-[12px] border border-line-strong bg-surface text-text hover:bg-raised">
          <Plus size={22} />
        </button>
      </div>
      <button onClick={() => nav.quickStart()}
        className="mt-4 flex h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-english text-[15px] font-semibold text-bg hover:brightness-110">
        <Mic size={20} /> Quick start
      </button>
      <div className="mt-3"><SearchField query={query} onChange={setQuery} /></div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {[...groups].map(([label, list]) => (
          <div key={label}>
            <SectionLabel className="pb-1 pl-2 pt-2.5">{label}</SectionLabel>
            {list.map((p) => <SideRow key={p.id} p={p} selected={p.id === viewId} live={p.id === liveId} />)}
          </div>
        ))}
        {shown.length === 0 && <div className="p-2 text-[13px] text-muted">{query.trim() ? "No match." : "No projects yet."}</div>}
      </div>
    </aside>
  );
}

export function EmptyConversation() {
  return (
    <div className="flex h-full flex-col items-center justify-center px-4">
      <Logo size={72} />
      <div className="mt-4 text-center text-[18px] font-semibold text-text">Pick a project, or start listening</div>
      <button onClick={() => nav.quickStart()}
        className="mt-4 flex h-12 items-center gap-2 rounded-full bg-english px-5 text-[15px] font-semibold text-bg hover:brightness-110">
        <Mic size={20} /> Start listening
      </button>
      <div className="mt-4"><PairPicker /></div>
    </div>
  );
}
