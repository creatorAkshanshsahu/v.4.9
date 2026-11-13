import { createFileRoute, Link } from "@tanstack/react-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  ArrowLeft,
  ChevronUp,
  ChevronDown,
  Volume2,
  VolumeX,
  RefreshCw,
  Code2,
  Search,
  Copy,
  Check,
  Languages,
  Maximize,
} from "lucide-react";
import { LiveVideoSurface, type AudioTrackOption } from "@/components/tv/LiveVideoSurface";
import type { LiveChannelDto } from "@/lib/live-gateway";

export const Route = createFileRoute("/live")({
  head: () => ({
    meta: [
      { title: "Live TV — Streamr" },
      { name: "referrer", content: "no-referrer" },
      { name: "description", content: "Watch maxTV & StarShare live channels with Hindi audio & OG embed codes." },
      { property: "og:title", content: "Live TV — Streamr" },
      { property: "og:description", content: "Watch maxTV & StarShare live channels with Hindi audio & OG embed codes." },
      { property: "og:type", content: "website" },
      { name: "twitter:card", content: "summary_large_image" },
    ],
  }),
  component: LivePlayerContent,
});

export function LivePlayerContent() {
  // maxTV leads, with JioTV and TG TV available both as first-class sources and automatic fallbacks.
  const [source, setSource] = useState<"maxtv" | "jio" | "tgtv" | "all">("maxtv");
  const [filter, setFilter] = useState<"hindi" | "en_hi" | "all">("hindi");
  const [selectedCategory, setSelectedCategory] = useState<string>("");
  const [query, setQuery] = useState("");
  const [channels, setChannels] = useState<LiveChannelDto[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [activeId, setActiveId] = useState<string>("");
  const [loading, setLoading] = useState(true);
  const [muted, setMuted] = useState(false);
  const [autoMutedBanner, setAutoMutedBanner] = useState(false);
  const [reloadNonce, setReloadNonce] = useState(0);
  const [containerKind, setContainerKind] = useState("MPEG-TS");
  const [audioTracks, setAudioTracks] = useState<AudioTrackOption[]>([]);
  const [activeAudio, setActiveAudio] = useState(-1);
  const [showEmbedModal, setShowEmbedModal] = useState(false);
  const [copiedKey, setCopiedKey] = useState<string | null>(null);
  const [portalError, setPortalError] = useState<string | null>(null);
  const [servedBy, setServedBy] = useState<string>("");
  const [backupError, setBackupError] = useState<string | null>(null);

  useEffect(() => {
    if (typeof window === "undefined") return;
    const requested = new URLSearchParams(window.location.search).get("ch");
    if (requested) setActiveId(requested);
  }, []);

  const loadCatalogue = useCallback(
    async (force = false) => {
      setLoading(true);
      try {
        const res = await fetch(
          `/api/live/channels?source=${encodeURIComponent(source)}&filter=${encodeURIComponent(filter)}${force ? "&refresh=1" : ""}`,
        );
        const data = (await res.json()) as {
          channels?: LiveChannelDto[];
          categories?: string[];
          portalError?: string | null;
          servedBy?: string;
          backupError?: string | null;
        };
        const list = data.channels ?? [];
        setChannels(list);
        setCategories(data.categories ?? []);
        setPortalError(data.portalError ?? null);
        setServedBy(data.servedBy ?? "");
        setBackupError(data.backupError ?? null);
        setSelectedCategory("");
        if (list.length > 0) {
          setActiveId((prev) => (prev && list.some((c) => c.id === prev) ? prev : list[0]!.id));
        }
      } catch {
        // retain current list on transient error
      } finally {
        setLoading(false);
      }
    },
    [source, filter],
  );

  useEffect(() => {
    void loadCatalogue(false);
  }, [loadCatalogue]);

  const visibleChannels = useMemo(() => {
    const q = query.trim().toLowerCase();
    return channels.filter((c) => {
      if (selectedCategory && c.category !== selectedCategory) return false;
      if (!q) return true;
      return `${c.name} ${c.category}`.toLowerCase().includes(q);
    });
  }, [channels, selectedCategory, query]);

  const activeChannel = useMemo(
    () => channels.find((c) => c.id === activeId) ?? visibleChannels[0] ?? channels[0] ?? null,
    [channels, visibleChannels, activeId],
  );

  const stepChannel = (delta: number) => {
    const pool = visibleChannels.length > 0 ? visibleChannels : channels;
    if (pool.length === 0 || !activeChannel) return;
    const idx = Math.max(0, pool.findIndex((c) => c.id === activeChannel.id));
    const next = pool[(idx + delta + pool.length) % pool.length];
    if (next) setActiveId(next.id);
  };

  const handleTracks = useCallback((tracks: AudioTrackOption[], idx: number) => {
    setAudioTracks(tracks);
    setActiveAudio(idx);
  }, []);

  const copySnippet = (key: string, text: string) => {
    void navigator.clipboard.writeText(text).then(() => {
      setCopiedKey(key);
      setTimeout(() => setCopiedKey(null), 1600);
    });
  };

  const origin = typeof window !== "undefined" ? window.location.origin : "";
  const apiUrl = `${origin}/api/live/channels?source=${source}&filter=${filter}`;
  const m3uUrl = `${origin}/api/live/playlist.m3u?source=${source}&filter=${filter}`;
  const sdkCode = `<div id="og-live-tv"></div>\n<script src="${origin}/embed-sdk.js" data-target="#og-live-tv" data-source="${source}" data-filter="${filter}"></script>`;

  const handleAutoMuted = useCallback(() => {
    setMuted(true);
    setAutoMutedBanner(true);
  }, []);

  return (
    <main className="player-scene relative h-screen w-full overflow-hidden bg-black">
      {activeChannel && (
        <LiveVideoSurface
          channelId={activeChannel.id}
          channelName={activeChannel.name}
          directUrl={activeChannel.directUrl}
          drm={activeChannel.drm ?? null}
          container={activeChannel.container}
          activeSource={activeChannel.source}
          muted={muted}
          reloadNonce={reloadNonce}
          selectedAudioTrack={activeAudio}
          onAudioTracksChange={handleTracks}
          onContainerDetected={setContainerKind}
          onAutoMuted={handleAutoMuted}
        />
      )}

      {autoMutedBanner && muted && (
        <button
          type="button"
          onClick={() => {
            setMuted(false);
            setAutoMutedBanner(false);
          }}
          className="glass absolute left-1/2 top-24 z-30 -translate-x-1/2 rounded-full border border-accent/60 px-4 py-2 text-xs font-bold text-foreground shadow-lg transition hover:bg-accent hover:text-accent-foreground"
        >
          <VolumeX className="mr-1.5 inline size-4" /> Tap to Unmute Live Audio
        </button>
      )}

      <div className="bg-player-scrim pointer-events-none absolute inset-0" />

      {/* Top Bar */}
      <div className="absolute inset-x-0 top-0 z-20 flex flex-wrap items-center justify-between gap-3 p-4 md:p-8">
        <div className="flex items-center gap-3">
          <Link to="/" aria-label="Back" className="glass rounded-full p-2.5">
            <ArrowLeft className="size-5" />
          </Link>
          <span className="rounded bg-live px-2.5 py-0.5 text-xs font-bold tracking-widest text-white">LIVE</span>
          <div>
            <p className="font-display text-lg font-bold md:text-2xl">{activeChannel?.name ?? "Loading Live TV…"}</p>
            <p className="text-xs text-muted-foreground">
              {activeChannel
                ? `${activeChannel.source.toUpperCase()} · ${activeChannel.category} · ${containerKind}`
                : loading
                  ? "Loading channels…"
                  : "No channels available from this source"}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {/* This used to be a static "maxTV" pill, so the backup playlist could not be
              chosen at all — it only appeared when the portal happened to fail. */}
          <div className="glass flex rounded-full p-1 text-xs font-semibold">
            {(
              [
                ["maxtv", "maxTV"],
                ["jio", "JioTV"],
                ["tgtv", "TG TV"],
                ["all", "Both"],
              ] as const
            ).map(([val, label]) => (
              <button
                key={val}
                type="button"
                onClick={() => setSource(val)}
                className={`rounded-full px-3 py-1 transition ${source === val ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:text-foreground"}`}
              >
                {label}
              </button>
            ))}
          </div>

          <div className="glass flex rounded-full p-1 text-xs font-semibold">
            {(
              [
                ["hindi", "Indian Hindi"],
                ["en_hi", "India + Eng"],
                ["all", "All"],
              ] as const
            ).map(([val, label]) => (
              <button
                key={val}
                type="button"
                onClick={() => setFilter(val)}
                className={`rounded-full px-3 py-1 transition ${filter === val ? "bg-accent text-accent-foreground" : "text-muted-foreground hover:text-foreground"}`}
              >
                {label}
              </button>
            ))}
          </div>

          <Button
            variant="secondary"
            onClick={() => setShowEmbedModal((v) => !v)}
            className="glass rounded-full px-4 text-xs font-bold"
          >
            <Code2 className="mr-1.5 size-4" /> OG Embed &amp; API
          </Button>
        </div>
      </div>

      {/* Say plainly where the list came from; never let a dead seed look like a working catalogue. */}
      {(portalError || ((servedBy === "jio" || servedBy === "tgtv") && source === "maxtv") || ((source === "jio" || source === "tgtv") && !loading)) && (
        <div className="absolute left-4 right-4 top-20 z-30 rounded-xl border border-accent/40 bg-background/85 px-4 py-2.5 text-xs md:left-8 md:right-auto md:max-w-xl">
          {servedBy === "jio" && source === "maxtv" && (
            <p className="font-bold text-accent">maxTV unreachable — playing the JioTV backup playlist ({visibleChannels.length} channels).</p>
          )}
          {servedBy === "tgtv" && source === "maxtv" && (
            <p className="font-bold text-accent">maxTV &amp; JioTV unreachable — playing the TG TV live playlist ({visibleChannels.length} channels).</p>
          )}
          {portalError && (
            <p className="font-bold text-accent">No live source reachable — these {visibleChannels.length} sample channels will not play.</p>
          )}
          <p className="mt-0.5 text-muted-foreground">
            {portalError ??
              (source === "jio" ? backupError : null) ??
              ((source === "jio" || source === "tgtv") && visibleChannels.length === 0
                ? "This playlist returned no channels. Press Refresh to try again."
                : "Live playlist connected.")}
          </p>
        </div>
      )}

      {/* Desktop Channel Guide Drawer */}
      <aside className="glass absolute bottom-32 right-4 top-24 z-20 hidden w-88 flex-col gap-2.5 overflow-hidden rounded-2xl p-3 lg:right-8 lg:flex">
        <div className="flex items-center justify-between px-1">
          <p className="text-xs font-bold uppercase tracking-widest text-muted-foreground">
            {loading ? "Loading…" : `${visibleChannels.length} Channels`}
          </p>
          <button
            type="button"
            onClick={() => void loadCatalogue(true)}
            className="text-xs font-semibold text-accent hover:underline"
          >
            Refresh
          </button>
        </div>

        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-2.5 size-4 text-muted-foreground" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search Star Sports, Sony, News…"
            className="w-full rounded-full border border-border bg-background/70 py-1.5 pl-9 pr-3 text-xs outline-none focus:border-accent"
          />
        </div>

        <div className="no-scrollbar flex shrink-0 gap-1.5 overflow-x-auto pb-1">
          <button
            type="button"
            onClick={() => setSelectedCategory("")}
            className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ${selectedCategory === "" ? "bg-primary text-primary-foreground" : "bg-secondary/60 text-muted-foreground"}`}
          >
            All
          </button>
          {categories.map((cat) => (
            <button
              key={cat}
              type="button"
              onClick={() => setSelectedCategory(cat)}
              className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ${selectedCategory === cat ? "bg-primary text-primary-foreground" : "bg-secondary/60 text-muted-foreground"}`}
            >
              {cat}
            </button>
          ))}
        </div>

        <div className="flex-1 space-y-1.5 overflow-y-auto pr-1">
          {visibleChannels.slice(0, 250).map((ch) => {
            const selected = activeChannel?.id === ch.id;
            return (
              <Button
                variant="secondary"
                key={ch.id}
                onClick={() => setActiveId(ch.id)}
                className={`flex h-auto w-full items-center justify-start gap-3 rounded-xl p-2.5 text-left transition ${selected ? "bg-primary text-primary-foreground" : "hover:bg-secondary"}`}
              >
                <span className="grid size-9 shrink-0 place-items-center rounded-lg bg-background/40 font-display text-xs font-bold">
                  {ch.name.slice(0, 2).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-bold">{ch.name}</span>
                  <span className={`block truncate text-[11px] ${selected ? "opacity-80" : "text-muted-foreground"}`}>
                    {ch.category}
                  </span>
                </span>
              </Button>
            );
          })}
        </div>
      </aside>

      {/* OG Website Embed & API Modal */}
      {showEmbedModal && activeChannel && (
        <div className="glass absolute left-4 right-4 top-24 z-30 max-w-xl space-y-3 rounded-2xl p-5 md:left-8">
          <div className="flex items-center justify-between">
            <h2 className="font-display text-base font-bold">Embed on OG Website · {activeChannel.name}</h2>
            <Button variant="secondary" size="sm" onClick={() => setShowEmbedModal(false)} className="rounded-full text-xs">
              Close
            </Button>
          </div>

          {[
            { key: "iframe", label: "1. Channel <iframe> Embed", value: activeChannel.iframeCode },
            { key: "api", label: "2. Channels JSON API (CORS enabled)", value: apiUrl },
            { key: "m3u", label: "3. Dynamic maxTV M3U Playlist", value: m3uUrl },
            { key: "sdk", label: "4. Full Live TV Grid + Player Widget", value: sdkCode },
          ].map((item) => (
            <div key={item.key} className="space-y-1 rounded-xl border border-border bg-background/80 p-3">
              <div className="flex items-center justify-between text-xs font-semibold text-muted-foreground">
                <span>{item.label}</span>
                <button
                  type="button"
                  onClick={() => copySnippet(item.key, item.value)}
                  className="inline-flex items-center gap-1 text-accent hover:underline"
                >
                  {copiedKey === item.key ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
                  {copiedKey === item.key ? "Copied" : "Copy"}
                </button>
              </div>
              <pre className="overflow-x-auto whitespace-pre-wrap break-all font-mono text-[11px] text-foreground">
                {item.value}
              </pre>
            </div>
          ))}
        </div>
      )}

      {/* Bottom Controls */}
      <div className="absolute inset-x-0 bottom-0 z-20 space-y-3 p-4 md:p-8">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="secondary"
              onClick={() => {
                setAutoMutedBanner(false);
                setMuted((m) => !m);
              }}
              className="glass rounded-full px-4 text-xs font-bold"
            >
              {muted ? <VolumeX className="mr-1.5 size-4" /> : <Volume2 className="mr-1.5 size-4" />}
              {muted ? "Unmute" : "Mute"}
            </Button>

            <Button
              variant="secondary"
              onClick={() => setReloadNonce((n) => n + 1)}
              className="glass rounded-full px-4 text-xs font-bold"
            >
              <RefreshCw className="mr-1.5 size-4" /> Reload Link
            </Button>

            {audioTracks.length > 1 && (
              <label className="glass inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-bold">
                <Languages className="size-4 text-accent" />
                <select
                  value={activeAudio}
                  onChange={(e) => setActiveAudio(Number(e.target.value))}
                  className="bg-transparent text-xs outline-none"
                >
                  {audioTracks.map((t) => (
                    <option key={t.index} value={t.index} className="bg-background text-foreground">
                      Audio: {t.label}
                    </option>
                  ))}
                </select>
              </label>
            )}

            <Button
              variant="secondary"
              onClick={() => {
                if (document.fullscreenElement) void document.exitFullscreen();
                else void document.querySelector(".player-scene")?.requestFullscreen?.();
              }}
              className="glass rounded-full p-2.5"
              aria-label="Fullscreen"
            >
              <Maximize className="size-4" />
            </Button>
          </div>

          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => stepChannel(-1)} aria-label="Previous channel" className="glass rounded-full p-2.5">
              <ChevronUp className="size-5" />
            </Button>
            <Button variant="secondary" onClick={() => stepChannel(1)} aria-label="Next channel" className="glass rounded-full p-2.5">
              <ChevronDown className="size-5" />
            </Button>
          </div>
        </div>

        {/* Compact channel guide: every viewport below lg gets a horizontal rail so the
            channel list is never hidden behind a desktop-only drawer. */}
        <div className="space-y-2 lg:hidden">
          <div className="flex items-center justify-between gap-2">
            <p className="text-[11px] font-bold uppercase tracking-widest text-muted-foreground">
              {loading ? "Loading…" : `${visibleChannels.length} Channels`}
            </p>
            <button type="button" onClick={() => void loadCatalogue(true)} className="text-[11px] font-semibold text-accent hover:underline">
              Refresh
            </button>
          </div>

          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-2 size-3.5 text-muted-foreground" />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search channels…"
              aria-label="Search channels"
              className="w-full rounded-full border border-border bg-background/70 py-1 pl-8 pr-3 text-[11px] outline-none focus:border-accent"
            />
          </div>

          <div className="no-scrollbar flex shrink-0 gap-1.5 overflow-x-auto pb-0.5">
            <button
              type="button"
              onClick={() => setSelectedCategory("")}
              className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ${selectedCategory === "" ? "bg-primary text-primary-foreground" : "glass text-muted-foreground"}`}
            >
              All
            </button>
            {categories.map((cat) => (
              <button
                key={cat}
                type="button"
                onClick={() => setSelectedCategory(cat)}
                className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] font-semibold ${selectedCategory === cat ? "bg-primary text-primary-foreground" : "glass text-muted-foreground"}`}
              >
                {cat}
              </button>
            ))}
          </div>

          <div className="no-scrollbar flex gap-2 overflow-x-auto pb-1">
            {visibleChannels.length === 0 && (
              <p className="px-1 py-1.5 text-[11px] text-muted-foreground">
                {loading ? "Loading channels…" : query.trim() ? `No channels match “${query.trim()}”.` : "No channels from this source."}
              </p>
            )}
            {visibleChannels.map((ch) => (
              <Button
                variant="secondary"
                key={ch.id}
                onClick={() => setActiveId(ch.id)}
                className={`h-auto shrink-0 rounded-xl px-3 py-1.5 text-left text-xs font-bold ${activeChannel?.id === ch.id ? "bg-primary text-primary-foreground" : "glass"}`}
              >
                {ch.name}
              </Button>
            ))}
          </div>

          {!loading && visibleChannels.length === 0 && (
            <p className="text-xs text-muted-foreground">No channels match this filter. Try “All”.</p>
          )}
        </div>
      </div>
    </main>
  );
}
