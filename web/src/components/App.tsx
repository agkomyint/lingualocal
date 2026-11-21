"use client";
/** The app: loading screen, then projects and the conversation side by side or one at a time. Port of LinguaScreen.kt + MainActivity. */
import { useEffect, useState } from "react";
import { useApp } from "@/lib/state";
import { firebase } from "@/lib/firebase";
import { loadCore } from "@/lib/audio";
import { startAutoSave, uploadPending, watchProjects } from "@/lib/store";
import { startVoiceCatchUp } from "@/lib/speak";
import { startNamer } from "@/lib/namer";
import { refreshStatus } from "@/lib/hybrid";
import { forProject } from "@/lib/ask";
import { Conversation } from "./Conversation";
import { EmptyConversation, ProjectDialogsHost, ProjectsHome, Sidebar } from "./Home";
import { Logo, Toasts } from "./ui";

const MIN_SPLASH_MS = 1200;
const MAX_SPLASH_MS = 10_000;

let started: Promise<void> | null = null;

/** Firebase (App Check, anonymous sign-in), the audio core, then the projects; background jobs after. */
function startup(onProgress: (p: number) => void): Promise<void> {
  return (started ??= (async () => {
    await firebase();
    onProgress(0.4);
    await loadCore();
    onProgress(0.7);
    await watchProjects();
    onProgress(1);
    startAutoSave();
    startVoiceCatchUp();
    startNamer();
    refreshStatus();
    uploadPending();
    addEventListener("online", () => { uploadPending(); refreshStatus(); });
    addEventListener("offline", () => refreshStatus());
  })());
}

function useWindowSize() {
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const f = () => setSize({ w: innerWidth, h: innerHeight });
    f();
    addEventListener("resize", f);
    return () => removeEventListener("resize", f);
  }, []);
  return size;
}

/** Logo with a soft breathing glow and a thin progress line. No text. */
function Loading({ progress }: { progress: number }) {
  return (
    <div className="flex h-dvh w-full flex-col items-center justify-center bg-bg">
      <div className="relative flex h-40 w-40 items-center justify-center">
        <div
          className="absolute inset-0 rounded-full animate-[ll-breathe_1600ms_ease-in-out_infinite_alternate]"
          style={{ background: "radial-gradient(circle, color-mix(in srgb, var(--ll-english) 28%, transparent) 0%, transparent 70%)" }}
        />
        <Logo size={88} />
      </div>
      <div className="mt-6 h-[3px] w-[120px] overflow-hidden rounded-full bg-line">
        <div className="h-full rounded-full bg-english transition-[width] duration-500 ease-out" style={{ width: `${Math.max(0.08, progress) * 100}%` }} />
      </div>
    </div>
  );
}

export default function App() {
  const [ready, setReady] = useState(false);
  const [progress, setProgress] = useState(0);
  const viewId = useApp((s) => s.viewId);
  const project = useApp((s) => s.projects.find((p) => p.id === s.viewId));
  const { w, h } = useWindowSize();

  useEffect(() => {
    const t0 = Date.now();
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      setTimeout(() => setReady(true), Math.max(0, MIN_SPLASH_MS - (Date.now() - t0)));
    };
    const cap = setTimeout(finish, MAX_SPLASH_MS);
    startup(setProgress).catch((e) => console.error("startup", e)).finally(() => { clearTimeout(cap); finish(); });
    return () => clearTimeout(cap);
  }, []);

  // Ask's chats follow the project on screen.
  useEffect(() => { if (ready) forProject(viewId); }, [ready, viewId]);

  if (!ready || w === 0) return <Loading progress={progress} />;

  // Sidebar on wide screens (desktop, landscape tablets); otherwise one screen at a time.
  const sidebar = w >= 1200 || (w >= 840 && w > h);
  return (
    <div className="flex h-dvh w-full overflow-hidden bg-bg text-text">
      {sidebar ? (
        <>
          <Sidebar />
          <div className="h-full w-px shrink-0 bg-line" />
          <main className="min-w-0 flex-1">
            {project ? <Conversation key={project.id} project={project} showBack={false} /> : <EmptyConversation />}
          </main>
        </>
      ) : (
        <main className="min-w-0 flex-1">
          {project ? <Conversation key={project.id} project={project} showBack /> : <ProjectsHome />}
        </main>
      )}
      <ProjectDialogsHost />
      <Toasts />
    </div>
  );
}
