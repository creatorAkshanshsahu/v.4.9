import { useEffect, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";

export interface AudioTrackOption {
  index: number;
  label: string;
}

interface LiveVideoSurfaceProps {
  channelId: string;
  channelName: string;
  muted: boolean;
  reloadNonce: number;
  selectedAudioTrack: number;
  onAudioTracksChange?: (tracks: AudioTrackOption[], activeIndex: number) => void;
  onContainerDetected?: (kind: string) => void;
}

declare global {
  interface Window {
    mpegts?: {
      isSupported: () => boolean;
      createPlayer: (mediaDataSource: Record<string, unknown>, config?: Record<string, unknown>) => {
        attachMediaElement: (el: HTMLVideoElement) => void;
        load: () => void;
        play: () => Promise<void> | void;
        unload: () => void;
        detachMediaElement: () => void;
        destroy: () => void;
        on: (event: string, cb: (...args: unknown[]) => void) => void;
      };
      Events: { ERROR: string };
    };
    Hls?: {
      isSupported: () => boolean;
      Events: { MANIFEST_PARSED: string; AUDIO_TRACKS_UPDATED: string; ERROR: string };
      new (cfg?: Record<string, unknown>): {
        loadSource: (url: string) => void;
        attachMedia: (el: HTMLVideoElement) => void;
        destroy: () => void;
        audioTracks: Array<{ name?: string; lang?: string }>;
        audioTrack: number;
        on: (event: string, cb: (...args: unknown[]) => void) => void;
      };
    };
  }
}

let scriptsPromise: Promise<void> | null = null;

function ensurePlayerEnginesLoaded(): Promise<void> {
  if (typeof window === "undefined") return Promise.resolve();
  if (window.mpegts && window.Hls) return Promise.resolve();
  if (scriptsPromise) return scriptsPromise;

  const loadWithFallback = (primary: string, fallback: string, checkGlobal: () => boolean) =>
    new Promise<void>((resolve) => {
      if (checkGlobal()) {
        resolve();
        return;
      }
      const trySrc = (src: string, next?: string) => {
        const el = document.createElement("script");
        el.src = src;
        el.async = true;
        el.onload = () => {
          if (checkGlobal() || !next) resolve();
          else trySrc(next);
        };
        el.onerror = () => {
          if (next) trySrc(next);
          else resolve();
        };
        document.head.appendChild(el);
      };
      trySrc(primary, fallback);
    });

  scriptsPromise = Promise.all([
    loadWithFallback(
      "/vendor/mpegts.js",
      "https://cdn.jsdelivr.net/npm/mpegts.js@1.8.0/dist/mpegts.js",
      () => Boolean(window.mpegts),
    ),
    loadWithFallback(
      "/vendor/hls.min.js",
      "https://cdn.jsdelivr.net/npm/hls.js@1.5.18/dist/hls.min.js",
      () => Boolean(window.Hls),
    ),
  ]).then(() => undefined);
  return scriptsPromise;
}

export function LiveVideoSurface({
  channelId,
  channelName,
  muted,
  reloadNonce,
  selectedAudioTrack,
  onAudioTracksChange,
  onContainerDetected,
}: LiveVideoSurfaceProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<InstanceType<NonNullable<Window["Hls"]>> | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>("Connecting to live stream…");
  const [hasError, setHasError] = useState(false);
  const [localRetry, setLocalRetry] = useState(0);

  useEffect(() => {
    if (videoRef.current) videoRef.current.muted = muted;
  }, [muted]);

  useEffect(() => {
    if (hlsRef.current && selectedAudioTrack >= 0) {
      hlsRef.current.audioTrack = selectedAudioTrack;
    }
  }, [selectedAudioTrack]);

  useEffect(() => {
    let cancelled = false;
    let mpegtsInstance: ReturnType<NonNullable<Window["mpegts"]>["createPlayer"]> | null = null;

    const cleanup = () => {
      if (mpegtsInstance) {
        try {
          mpegtsInstance.unload();
          mpegtsInstance.detachMediaElement();
          mpegtsInstance.destroy();
        } catch {
          // ignore teardown errors
        }
        mpegtsInstance = null;
      }
      if (hlsRef.current) {
        try {
          hlsRef.current.destroy();
        } catch {
          // ignore teardown errors
        }
        hlsRef.current = null;
      }
    };

    const streamUrl = (ext: ".ts" | ".m3u8") =>
      `/api/live/stream/${encodeURIComponent(channelId)}${ext}?_t=${Date.now()}`;

    const startHls = (url: string) => {
      if (cancelled || !videoRef.current) return;
      cleanup();
      onContainerDetected?.("HLS");
      const HlsCtor = window.Hls;
      if (HlsCtor && HlsCtor.isSupported()) {
        const hls = new HlsCtor({ lowLatencyMode: true, backBufferLength: 30 });
        hlsRef.current = hls;
        hls.loadSource(url);
        hls.attachMedia(videoRef.current);

        const syncTracks = () => {
          const list = hls.audioTracks || [];
          const opts: AudioTrackOption[] = list.map((t, idx) => ({
            index: idx,
            label: [t.name, t.lang].filter(Boolean).join(" · ") || `Track ${idx + 1}`,
          }));
          let preferred = hls.audioTrack;
          const hindiIdx = list.findIndex((t) => /hin|^hi$/i.test(`${t.name ?? ""} ${t.lang ?? ""}`));
          if (hindiIdx >= 0) {
            hls.audioTrack = hindiIdx;
            preferred = hindiIdx;
          }
          onAudioTracksChange?.(opts, preferred);
        };

        hls.on(HlsCtor.Events.MANIFEST_PARSED, () => {
          syncTracks();
          videoRef.current?.play().catch(() => undefined);
        });
        hls.on(HlsCtor.Events.AUDIO_TRACKS_UPDATED, syncTracks);
        hls.on(HlsCtor.Events.ERROR, (_ev, data) => {
          const d = data as { fatal?: boolean } | undefined;
          if (d?.fatal && !cancelled) {
            setHasError(true);
            setStatusMsg("Live HLS stream interrupted. Click Retry for a fresh maxTV token.");
          }
        });
      } else if (videoRef.current.canPlayType("application/vnd.apple.mpegurl")) {
        videoRef.current.src = url;
        videoRef.current.play().catch(() => undefined);
      } else {
        setHasError(true);
        setStatusMsg("HLS playback is not supported in this browser.");
      }
    };

    const startMpegTs = (url: string, fallbackToHls: boolean) => {
      if (cancelled || !videoRef.current) return;
      cleanup();
      onAudioTracksChange?.([], -1);
      if (!window.mpegts || !window.mpegts.isSupported()) {
        startHls(streamUrl(".m3u8"));
        return;
      }
      onContainerDetected?.("MPEG-TS");
      mpegtsInstance = window.mpegts.createPlayer(
        { type: "mpegts", isLive: true, url },
        { enableWorker: true, enableStashBuffer: false, liveBufferLatencyChasing: true },
      );
      mpegtsInstance.attachMediaElement(videoRef.current);
      mpegtsInstance.load();
      Promise.resolve(mpegtsInstance.play()).catch(() => undefined);
      mpegtsInstance.on(window.mpegts.Events.ERROR, () => {
        if (cancelled) return;
        if (fallbackToHls) startHls(streamUrl(".m3u8"));
        else {
          setHasError(true);
          setStatusMsg("Stream interrupted. Click Retry to request a fresh maxTV link.");
        }
      });
    };

    async function run() {
      setHasError(false);
      setStatusMsg(`Connecting to ${channelName}…`);
      await ensurePlayerEnginesLoaded();
      if (cancelled) return;

      const probeCtrl = new AbortController();
      const timer = setTimeout(() => probeCtrl.abort(), 8000);
      try {
        const res = await fetch(streamUrl(".ts"), { signal: probeCtrl.signal });
        clearTimeout(timer);
        if (!res.ok) {
          const j = (await res.json().catch(() => ({}))) as { error?: string };
          probeCtrl.abort();
          if (!cancelled) {
            setHasError(true);
            setStatusMsg(j.error || `Stream unavailable (HTTP ${res.status})`);
          }
          return;
        }
        const kind = (res.headers.get("x-stream-container") || "").toLowerCase();
        probeCtrl.abort();
        if (kind === "hls") startHls(streamUrl(".m3u8"));
        else startMpegTs(streamUrl(".ts"), true);
      } catch {
        clearTimeout(timer);
        if (!cancelled) startMpegTs(streamUrl(".ts"), true);
      }
    }

    void run();

    return () => {
      cancelled = true;
      cleanup();
    };
  }, [channelId, channelName, reloadNonce, localRetry, onAudioTracksChange, onContainerDetected]);

  return (
    <div className="absolute inset-0 bg-black">
      <video
        ref={videoRef}
        playsInline
        autoPlay
        muted={muted}
        onPlaying={() => {
          setStatusMsg(null);
          setHasError(false);
        }}
        onWaiting={() => setStatusMsg("Buffering live broadcast…")}
        className="h-full w-full object-contain"
      />
      {statusMsg && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-black/65 p-6 text-center">
          <p className="max-w-md text-sm text-muted-foreground">{statusMsg}</p>
          {hasError && (
            <Button variant="secondary" onClick={() => setLocalRetry((n) => n + 1)} className="glass rounded-full px-5">
              <RefreshCw className="mr-2 size-4" /> Retry Live Stream
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
