import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";
import { ArrowLeft, Play, Pause, RotateCcw, RotateCw, SkipForward, Subtitles, Volume2, Maximize, ListVideo } from "lucide-react";
import hero from "@/assets/hero.jpg";
import { titles, poster } from "@/lib/catalog";

export const Route = createFileRoute("/watch/$id")({
  head: () => ({
    meta: [
      { title: "Now Watching — Streamr" },
      { name: "description", content: "Movie and series player with episodes and controls." },
      { property: "og:title", content: "Now Watching — Streamr" },
      { property: "og:description", content: "Movie and series player with episodes and controls." },
    ],
  }),
  component: Player,
});

function Player() {
  const { id } = Route.useParams();
  const t = titles.find((x) => x.id === id) ?? titles[0]!;
  const [playing, setPlaying] = useState(true);
  const [eps, setEps] = useState(false);
  const pct = t.progress ?? 35;
  return (
    <main className="relative h-screen w-full overflow-hidden bg-scrim">
      <img src={hero} alt="" className="absolute inset-0 h-full w-full object-cover opacity-80" />
      <div className="bg-player-scrim absolute inset-0" />
      <div className="absolute inset-x-0 top-0 flex items-center gap-3 p-4 md:p-10">
        <Link to="/" aria-label="Back" className="glass rounded-full p-2.5"><ArrowLeft className="size-5" /></Link>
        <div>
          <p className="font-display text-lg font-bold md:text-2xl">{t.name}</p>
          {t.kind === "series" && <p className="text-xs text-muted-foreground md:text-sm">S1 · E3 “The Crossing”</p>}
        </div>
      </div>
      <div className="absolute inset-0 flex items-center justify-center gap-8 md:gap-16">
        <button aria-label="Back 10s" className="glass rounded-full p-3 md:p-4"><RotateCcw className="size-6 md:size-8" /></button>
        <button aria-label={playing ? "Pause" : "Play"} onClick={() => setPlaying(!playing)} className="tv-focus rounded-full bg-primary p-5 text-primary-foreground md:p-7">
          {playing ? <Pause className="size-8 fill-current md:size-10" /> : <Play className="size-8 fill-current md:size-10" />}
        </button>
        <button aria-label="Forward 10s" className="glass rounded-full p-3 md:p-4"><RotateCw className="size-6 md:size-8" /></button>
      </div>
      {eps && t.kind === "series" && (
        <div className="no-scrollbar absolute inset-x-0 bottom-36 flex gap-3 overflow-x-auto px-4 md:px-10">
          {[1, 2, 3, 4, 5, 6].map((e) => (
            <div key={e} className="tv-focus glass w-56 shrink-0 overflow-hidden rounded-xl">
              <div className="aspect-video" style={{ background: poster(t.hue + e * 15) }} />
              <div className="p-3"><p className="text-sm font-bold">E{e} · Episode {e}</p><p className="text-xs text-muted-foreground">48m</p></div>
            </div>
          ))}
        </div>
      )}
      <div className="absolute inset-x-0 bottom-0 space-y-4 p-4 md:p-10">
        <div className="flex items-center gap-3 text-xs md:text-sm">
          <span>{Math.round(pct * 1.3)}:12</span>
          <div className="relative h-1.5 flex-1 rounded-full bg-secondary">
            <div className="h-full rounded-full bg-live" style={{ width: `${pct}%` }} />
            <div className="absolute top-1/2 size-4 -translate-x-1/2 -translate-y-1/2 rounded-full bg-primary" style={{ left: `${pct}%` }} />
          </div>
          <span className="text-muted-foreground">2:14:00</span>
        </div>
        <div className="flex items-center gap-2 md:gap-4">
          <button className="glass inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold"><Volume2 className="size-4" /></button>
          <button className="glass inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold"><Subtitles className="size-4" /> <span className="hidden md:inline">Audio & Subtitles</span></button>
          <div className="flex-1" />
          {t.kind === "series" && (
            <>
              <button onClick={() => setEps(!eps)} className="glass inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold"><ListVideo className="size-4" /> Episodes</button>
              <button className="inline-flex items-center gap-2 rounded-lg bg-primary px-3 py-2 text-sm font-bold text-primary-foreground"><SkipForward className="size-4" /> Next</button>
            </>
          )}
          <button aria-label="Fullscreen" className="glass rounded-lg p-2"><Maximize className="size-4" /></button>
        </div>
      </div>
    </main>
  );
}
