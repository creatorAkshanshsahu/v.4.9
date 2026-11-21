import { Link } from "@tanstack/react-router";
import { Play, Info } from "lucide-react";
import hero from "@/assets/hero.jpg";
import { poster, type Title } from "@/lib/catalog";

export function Hero({ title = "Echoes of Dust", id = "dune-echo" }: { title?: string; id?: string }) {
  return (
    <section className="relative h-[70vh] min-h-[460px] w-full overflow-hidden">
      <img src={hero} alt="" className="absolute inset-0 h-full w-full object-cover" />
      <div className="bg-hero-scrim absolute inset-0" />
      <div className="relative flex h-full max-w-2xl flex-col justify-end gap-4 px-4 pb-16 md:px-12">
        <span className="text-xs font-bold uppercase tracking-[0.3em] text-accent">Original Film</span>
        <h1 className="text-4xl font-bold leading-none md:text-7xl">{title}</h1>
        <p className="text-sm text-muted-foreground md:text-lg">2026 · Sci‑Fi · 2h 14m · 4K HDR</p>
        <p className="line-clamp-3 text-sm text-foreground/85 md:text-base">
          A stranded surveyor follows a signal across a dying planet — and finds the voice calling her is her own.
        </p>
        <div className="flex gap-3 pt-2">
          <Link to="/watch/$id" params={{ id }} className="tv-focus inline-flex items-center gap-2 rounded-lg bg-primary px-6 py-3 font-bold text-primary-foreground">
            <Play className="size-5 fill-current" /> Play
          </Link>
          <button className="tv-focus glass inline-flex items-center gap-2 rounded-lg px-6 py-3 font-bold">
            <Info className="size-5" /> More Info
          </button>
        </div>
      </div>
    </section>
  );
}

export function Rail({ label, items, wide }: { label: string; items: Title[]; wide?: boolean }) {
  return (
    <section className="space-y-3">
      <h2 className="px-4 text-lg font-semibold md:px-12 md:text-2xl">{label}</h2>
      <div className="no-scrollbar flex gap-3 overflow-x-auto px-4 py-4 md:gap-5 md:px-12">
        {items.map((t) => (
          <Link
            key={t.id + label}
            to="/watch/$id"
            params={{ id: t.id }}
            className={`tv-focus relative shrink-0 overflow-hidden rounded-xl ${wide ? "aspect-video w-64 md:w-80" : "aspect-[2/3] w-32 md:w-48"}`}
            style={{ background: poster(t.hue) }}
          >
            <div className="absolute inset-0 flex flex-col justify-end bg-gradient-to-t from-scrim/90 to-transparent p-3">
              <span className="text-[10px] font-bold uppercase tracking-widest text-accent">{t.kind}</span>
              <span className="font-display text-sm font-bold leading-tight md:text-lg">{t.name}</span>
            </div>
            {t.progress != null && (
              <div className="absolute inset-x-0 bottom-0 h-1 bg-secondary">
                <div className="h-full bg-live" style={{ width: `${t.progress}%` }} />
              </div>
            )}
          </Link>
        ))}
      </div>
    </section>
  );
}
