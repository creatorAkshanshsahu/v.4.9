import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";
import { ArrowLeft, ChevronUp, ChevronDown, Volume2, Subtitles } from "lucide-react";
import { channels } from "@/lib/catalog";

export const Route = createFileRoute("/live")({
  head: () => ({
    meta: [
      { title: "Live TV — Streamr" },
      { name: "description", content: "Watch live channels with a channel guide." },
      { property: "og:title", content: "Live TV — Streamr" },
      { property: "og:description", content: "Watch live channels with a channel guide." },
    ],
  }),
  component: LivePlayer,
});

function LivePlayer() {
  const [i, setI] = useState(1);
  const c = channels[i]!;
  const go = (d: number) => setI((i + d + channels.length) % channels.length);
  return (
    <main className="relative h-screen w-full overflow-hidden" style={{ background: `radial-gradient(circle at 60% 40%, oklch(0.45 0.12 ${c.hue}), oklch(0.1 0.02 ${c.hue}))` }}>
      <div className="bg-player-scrim absolute inset-0" />
      {/* Top bar */}
      <div className="absolute inset-x-0 top-0 flex items-center gap-3 p-4 md:p-10">
        <Link to="/" aria-label="Back" className="glass rounded-full p-2.5"><ArrowLeft className="size-5" /></Link>
        <span className="rounded bg-live px-2 py-0.5 text-xs font-bold tracking-widest">LIVE</span>
        <span className="font-display text-lg font-bold md:text-2xl">{c.no} · {c.name}</span>
      </div>
      {/* Channel guide */}
      <aside className="glass absolute bottom-40 right-4 top-20 hidden w-80 flex-col gap-2 overflow-y-auto rounded-2xl p-3 md:flex md:right-10">
        <p className="px-2 py-1 text-xs font-bold uppercase tracking-widest text-muted-foreground">Channels</p>
        {channels.map((ch, k) => (
          <button key={ch.no} onClick={() => setI(k)} className={`flex items-center gap-3 rounded-xl p-2 text-left transition ${k === i ? "bg-primary text-primary-foreground" : "hover:bg-secondary"}`}>
            <span className="grid size-12 shrink-0 place-items-center rounded-lg font-display font-bold" style={{ background: `oklch(0.5 0.14 ${ch.hue})` }}>{ch.no}</span>
            <span className="min-w-0">
              <span className="block truncate text-sm font-bold">{ch.name}</span>
              <span className={`block truncate text-xs ${k === i ? "opacity-70" : "text-muted-foreground"}`}>{ch.now}</span>
            </span>
          </button>
        ))}
      </aside>
      {/* Bottom info */}
      <div className="absolute inset-x-0 bottom-0 space-y-4 p-4 md:p-10">
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-xs font-bold uppercase tracking-widest text-accent">Now playing</p>
            <h1 className="text-2xl font-bold md:text-5xl">{c.now}</h1>
            <p className="text-sm text-muted-foreground">Up next: {c.next}</p>
          </div>
          <div className="flex flex-col gap-2">
            <button onClick={() => go(-1)} aria-label="Channel up" className="glass rounded-full p-2.5"><ChevronUp className="size-5" /></button>
            <button onClick={() => go(1)} aria-label="Channel down" className="glass rounded-full p-2.5"><ChevronDown className="size-5" /></button>
          </div>
        </div>
        <div className="flex items-center gap-3 text-xs text-muted-foreground">
          <span>20:00</span>
          <div className="h-1 flex-1 rounded-full bg-secondary"><div className="h-full rounded-full bg-live" style={{ width: `${c.pct}%` }} /></div>
          <span>21:00</span>
          <Volume2 className="size-5" /><Subtitles className="size-5" />
        </div>
        <div className="no-scrollbar flex gap-2 overflow-x-auto md:hidden">
          {channels.map((ch, k) => (
            <button key={ch.no} onClick={() => setI(k)} className={`shrink-0 rounded-lg px-3 py-2 text-xs font-bold ${k === i ? "bg-primary text-primary-foreground" : "glass"}`}>{ch.no} {ch.name}</button>
          ))}
        </div>
      </div>
    </main>
  );
}
