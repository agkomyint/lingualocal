"use client";
import { useEffect, useState, type ReactNode } from "react";
import { Dialog, Switch as RSwitch } from "radix-ui";
import { X } from "lucide-react";
import { cn } from "@/lib/cn";
import { useApp } from "@/lib/state";

/** The brand mark (two speech bubbles), cropped like the Android LogoMark. */
export function Logo({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="28.3 28.3 51.4 51.4" aria-hidden className="shrink-0">
      <g transform="translate(54 54) scale(0.9) translate(-54 -54)">
        <path fill="#F4B860" d="M36,27 H56 A10,10 0 0 1 66,37 V47 A10,10 0 0 1 56,57 H46 L36,66 L37,57 H36 A10,10 0 0 1 26,47 V37 A10,10 0 0 1 36,27 Z" />
        <path fill="#7AB4FF" stroke="#0D0F13" strokeWidth={3} strokeLinejoin="round" d="M52,43 H72 A10,10 0 0 1 82,53 V63 A10,10 0 0 1 72,73 H71 L72,82 L62,73 H52 A10,10 0 0 1 42,63 V53 A10,10 0 0 1 52,43 Z" />
        <path stroke="#0D0F13" strokeWidth={3.2} strokeLinecap="round" fill="none" d="M52,55 V61 M57,52 V64 M62,49 V67 M67,52 V64 M72,55 V61" />
      </g>
    </svg>
  );
}

export function SectionLabel({ children, className }: { children: string; className?: string }) {
  return <div className={cn("text-[12px] font-semibold uppercase tracking-[0.8px] text-faint", className)}>{children}</div>;
}

export function KeyValue({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex w-full gap-3 text-[12px]">
      <span className="flex-1 text-muted">{k}</span>
      <span className="text-right text-text">{v}</span>
    </div>
  );
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <RSwitch.Root
      checked={checked} onCheckedChange={onChange} aria-label={label}
      className="relative h-8 w-[52px] shrink-0 rounded-full border-2 border-line-strong bg-control transition-colors data-[state=checked]:border-english data-[state=checked]:bg-english"
    >
      <RSwitch.Thumb className="block h-4 w-4 translate-x-[6px] rounded-full bg-muted transition-all data-[state=checked]:h-6 data-[state=checked]:w-6 data-[state=checked]:translate-x-[22px] data-[state=checked]:bg-bg" />
    </RSwitch.Root>
  );
}

/** Centered dialog on the app's surface colour. */
export function Modal({ open, onClose, title, children, className, hideTitle }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode; className?: string; hideTitle?: boolean;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/60" />
        <Dialog.Content
          aria-describedby={undefined}
          className={cn(
            "fixed left-1/2 top-1/2 z-50 w-[calc(100%-32px)] max-w-[460px] -translate-x-1/2 -translate-y-1/2 rounded-[24px] border border-line-strong bg-surface p-5 animate-[ll-fade-in_150ms_ease-out]",
            className,
          )}
        >
          <Dialog.Title className={hideTitle ? "sr-only" : "mb-4 text-[20px] font-semibold text-text"}>{title}</Dialog.Title>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** Bottom sheet (ModalBottomSheet on Android). */
export function Sheet({ open, onClose, title, children, className }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode; className?: string;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={(o) => { if (!o) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/60" />
        <Dialog.Content
          aria-describedby={undefined}
          className={cn(
            "fixed inset-x-0 bottom-0 z-50 mx-auto max-h-[92dvh] w-full max-w-[640px] overflow-hidden rounded-t-[28px] bg-surface animate-[ll-sheet-in_220ms_ease-out]",
            className,
          )}
        >
          <Dialog.Title className="sr-only">{title}</Dialog.Title>
          {children}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

export function TextButton({ children, onClick, color = "text-text", bold }: { children: ReactNode; onClick: () => void; color?: string; bold?: boolean }) {
  return (
    <button onClick={onClick} className={cn("h-10 rounded-full px-3 text-[14px] hover:bg-raised", color, bold && "font-semibold")}>
      {children}
    </button>
  );
}

export function NameDialog({ open, title, initial, onClose, onDone, placeholder = "e.g. Landlord · lease renewal", hint = "Leave blank to name it by date" }: {
  open: boolean; title: string; initial: string; onClose: () => void; onDone: (name: string) => void; placeholder?: string; hint?: string;
}) {
  const [name, setName] = useState(initial);
  useEffect(() => { if (open) setName(initial); }, [open, initial]);
  return (
    <Modal open={open} onClose={onClose} title={title}>
      <form onSubmit={(e) => { e.preventDefault(); onDone(name); }}>
        <input
          autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder={placeholder}
          className="h-14 w-full rounded-[14px] border border-line-strong bg-bg px-4 text-[16px] text-text caret-english focus:border-english"
        />
        <div className="mt-1 px-4 text-[12px] text-faint">{hint}</div>
        <div className="mt-5 flex justify-end gap-2">
          <TextButton onClick={onClose}>Cancel</TextButton>
          <button type="submit" className="h-10 rounded-full px-3 text-[14px] font-semibold text-english hover:bg-raised">Save</button>
        </div>
      </form>
    </Modal>
  );
}

/** Short messages at the bottom, like Android toasts. */
export function Toasts() {
  const t = useApp((s) => s.toast);
  const [shown, setShown] = useState<typeof t>(null);
  useEffect(() => {
    if (!t) return;
    setShown(t);
    const h = setTimeout(() => setShown((s) => (s === t ? null : s)), 2600);
    return () => clearTimeout(h);
  }, [t]);
  if (!shown) return null;
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-28 z-[60] flex justify-center px-4">
      <div key={shown.at} className="max-w-[420px] rounded-full bg-control px-4 py-2.5 text-center text-[14px] text-text shadow-lg animate-[ll-fade-in_150ms_ease-out]">
        {shown.text}
      </div>
    </div>
  );
}

export function IconClose({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button onClick={onClick} aria-label={label} className="flex h-10 w-10 items-center justify-center rounded-full text-muted hover:bg-raised">
      <X size={22} />
    </button>
  );
}

/** Three pulsing dots and a label ("Translating…"). */
export function PendingLine({ text, color }: { text: string; color: string }) {
  return (
    <div className="flex items-center">
      {[0, 1, 2].map((i) => (
        <span
          key={i} className="mr-1 h-1.5 w-1.5 rounded-full"
          style={{ background: color, animation: `ll-dot 1200ms linear ${i * 400}ms infinite`, opacity: 0.3 }}
        />
      ))}
      <span className="ml-1.5 text-[15px] text-muted">{text}…</span>
    </div>
  );
}

export function Spinner({ size = 14 }: { size?: number }) {
  return <span className="inline-block shrink-0 rounded-full border-2 border-english border-t-transparent" style={{ width: size, height: size, animation: "ll-spin 800ms linear infinite" }} />;
}
