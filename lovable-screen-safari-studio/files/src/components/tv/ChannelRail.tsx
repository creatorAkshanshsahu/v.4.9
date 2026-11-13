import { useEffect, useState } from "react";
import { Link } from "@tanstack/react-router";
import { Radio, Play } from "lucide-react";
import { channels as fallbackChannels } from "@/lib/catalog";
import type { LiveChannelDto } from "@/lib/live-gateway";

export function ChannelRail() {
  const [liveItems, setLiveItems] = useState<LiveChannelDto[]>([]);

  useEffect(() => {
    fetch("/api/live/channels?source=maxtv&filter=hindi&limit=16")
      .then((r) => r.json())
      .then((d: { channels?: LiveChannelDto[] }) => {
        if (d.channels && d.channels.length > 0) setLiveItems(d.channels);
      })
      .catch(() => undefined);
  }, []);

  return (
    <section className="space-y-3">
      <div className="flex items-center justify-between px-4 md:px-12">
        <h2 className="flex items-center gap-3 text-lg font-semibold md:text-2xl">
          <Radio className="size-5 text-live" /> Live TV · maxTV
        </h2>
        <Link to="/live" className="text-sm font-semibold text-muted-foreground">
          Full Guide &amp; OG Embeds →
        </Link>
      </div>
      <div className="no-scrollbar flex gap-3 overflow-x-auto px-4 py-4 md:gap-5 md:px-12">
        {liveItems.length > 0
          ? liveItems.map((ch) => (
              <a
                href={`/live?ch=${encodeURIComponent(ch.id)}`}
                key={ch.id}
                className="tv-focus w-64 shrink-0 rounded-lg border border-border bg-card p-5 transition hover:border-accent md:w-72"
              >
                <div className="mb-4 flex items-center justify-between gap-2">
                  <span className="truncate font-display text-lg font-bold">{ch.name}</span>
                  <Play className="size-5 shrink-0 text-accent" />
                </div>
                <p className="mb-1 text-xs font-bold text-live">LIVE · {ch.source.toUpperCase()}</p>
                <p className="truncate text-sm text-muted-foreground">{ch.category}</p>
              </a>
            ))
          : fallbackChannels.map((channel) => (
              <Link
                to="/live"
                key={channel.no}
                className="tv-focus w-64 shrink-0 rounded-lg border border-border bg-card p-5 md:w-72"
              >
                <div className="mb-5 flex items-center justify-between">
                  <span className="font-display text-xl font-bold">{channel.name}</span>
                  <Play className="size-5 text-accent" />
                </div>
                <p className="mb-2 text-xs font-bold text-live">LIVE · {channel.no}</p>
                <p className="truncate font-semibold">{channel.now}</p>
                <p className="mt-1 truncate text-sm text-muted-foreground">Next · {channel.next}</p>
              </Link>
            ))}
      </div>
    </section>
  );
}
