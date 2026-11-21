"use client";
import dynamic from "next/dynamic";

// Browser-only: mic, WebAssembly, IndexedDB and Firebase App Check all need window.
const App = dynamic(() => import("@/components/App"), { ssr: false });

export default function Page() {
  return <App />;
}
