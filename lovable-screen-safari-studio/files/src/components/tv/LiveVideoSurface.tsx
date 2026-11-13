import { useEffect, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";

export interface AudioTrackOption {
  index: number;
  label: string;
}

interface ShakaPlayerInstance {
  configure: (config: Record<string, unknown>) => void;
  attach: (el: HTMLMediaElement) => Promise<void>;
  load: (url: string, startTime?: number) => Promise<void>;
  destroy: () => Promise<void>;
  addEventListener: (type: string, cb: (event: unknown) => void) => void;
  getAudioLanguages: () => string[];
  selectAudioLanguage: (language: string, role?: string) => void;
  getAudioLanguage: () => string;
}

export interface ChannelDrm {
  type: "clearkey";
  keys: Array<{ kid: string; k: string }>;
}

interface LiveVideoSurfaceProps {
  channelId: string;
  channelName: string;
  /** Optional direct HTTPS HLS stream URL for browser-side fallback when proxy ASN is geo-blocked. */
  directUrl?: string | undefined;
  /** ClearKey material for DRM channels, handed over by the catalogue. */
  drm?: ChannelDrm | null | undefined;
  /** Stated by the catalogue: "dash" | "hls". */
  container?: string | undefined;
  /** Which catalogue the channel came from, so errors name the right provider. */
  activeSource?: string | undefined;
  muted: boolean;
  reloadNonce: number;
  selectedAudioTrack: number;
  onAudioTracksChange?: (tracks: AudioTrackOption[], activeIndex: number) => void;
  onContainerDetected?: (kind: string) => void;
  onAutoMuted?: () => void;
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
    shaka?: {
      /** Constructor, with Shaka's static capability probe hung off it. */
      Player: {
        new (): ShakaPlayerInstance;
        isBrowserSupported: () => boolean;
      };
      polyfill: { installAll: () => void };
      log?: { setLevel: (level: number) => void };
      LogLevel?: { NONE: number };
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

/**
 * Normalise a ClearKey key id or key into 32 hex chars.
 *
 * Shaka accepts either 32 hex chars or 22-char unpadded base64url, and decides
 * which by *length alone*: exactly 22 means "base64", anything else goes through
 * a hex parser. A 16-byte key encoded as base64url is 22 chars, but a 16-byte
 * key id we already emit is 22 only when unpadded — and a padded 24-char form, or
 * a 32-char base64 string, fails the length test and gets parsed as hex, quietly
 * producing a wrong key. Converting everything to hex removes the ambiguity.
 */
function toHexKeyId(value: string): string | null {
  const v = (value ?? "").trim();
  if (!v) return null;
  if (/^[0-9a-fA-F]{32}$/.test(v)) return v.toLowerCase();
  const normalised = v.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
  if (!/^[A-Za-z0-9+/]+$/.test(normalised)) return null;
  const padded = normalised + "===".slice((normalised.length + 3) % 4);
  try {
    const bin = atob(padded);
    if (bin.length !== 16) return null;
    let hex = "";
    for (let i = 0; i < bin.length; i++) hex += bin.charCodeAt(i).toString(16).padStart(2, "0");
    return hex;
  } catch {
    return null;
  }
}

/** `kid:key,kid:key` from the proxy's X-Clear-Keys header. */
function parseKeyHeader(value: string | null): Array<{ kid: string; k: string }> {
  if (!value) return [];
  const out: Array<{ kid: string; k: string }> = [];
  for (const token of value.split(",")) {
    const i = token.indexOf(":");
    if (i <= 0) continue;
    const kid = token.slice(0, i).trim();
    const k = token.slice(i + 1).trim();
    if (kid && k) out.push({ kid, k });
  }
  return out;
}

let scriptsPromise: Promise<void> | null = null;

function ensurePlayerEnginesLoaded(): Promise<void> {
  if (typeof window === "undefined") return Promise.resolve();
  if (window.mpegts && window.Hls && window.shaka) return Promise.resolve();
  if (scriptsPromise) return scriptsPromise;

  const loadScript = (src: string) =>
    new Promise<void>((resolve) => {
      if (document.querySelector(`script[src="${src}"]`)) {
        resolve();
        return;
      }
      const el = document.createElement("script");
      el.src = src;
      el.async = true;
      el.onload = () => resolve();
      el.onerror = () => resolve();
      document.head.appendChild(el);
    });

  scriptsPromise = Promise.all([
    loadScript("/vendor/mpegts.js"),
    loadScript("/vendor/hls.min.js"),
    loadScript("/vendor/shaka-player.compiled.js"),
  ]).then(() => undefined);
  return scriptsPromise;
}

export function LiveVideoSurface({
  channelId,
  channelName,
  directUrl,
  drm,
  container,
  activeSource,
  muted,
  reloadNonce,
  selectedAudioTrack,
  onAudioTracksChange,
  onContainerDetected,
  onAutoMuted,
}: LiveVideoSurfaceProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<InstanceType<NonNullable<Window["Hls"]>> | null>(null);
  const shakaRef = useRef<InstanceType<NonNullable<Window["shaka"]>["Player"]> | null>(null);
  const [statusMsg, setStatusMsg] = useState<string | null>("Connecting to live stream…");
  const [hasError, setHasError] = useState(false);
  const [localRetry, setLocalRetry] = useState(0);
  /** Set when Shaka has named a concrete fault; the no-video timer must not overwrite it. */
  const specificErrorRef = useRef(false);

  const clearOverlayIfRendering = () => {
    const el = videoRef.current;
    if (!el) return false;
    if ((el.videoWidth > 0 && el.videoHeight > 0 && !el.paused) || (!el.paused && el.currentTime > 0.6)) {
      setStatusMsg(null);
      setHasError(false);
      return true;
    }
    return false;
  };

  useEffect(() => {
    const el = videoRef.current;
    if (!el) return;
    let settled = false;

    const check = () => {
      if (settled) return;
      if (el.videoWidth > 0 && el.videoHeight > 0) {
        settled = true;
        setStatusMsg(null);
        setHasError(false);
        clearInterval(poll);
        clearTimeout(deadline);
      }
    };

    const poll = setInterval(check, 500);
    const deadline = setTimeout(() => {
      if (settled) return;
      if (el.videoWidth === 0 || el.videoHeight === 0) {
        setHasError(true);
        settled = true;
        clearInterval(poll);
        // A specific refusal from Shaka is worth more than anything this timer can infer.
        // Overwriting it with "no video arrived" is how a 450 turned into a mystery.
        if (specificErrorRef.current) return;
        // Ask the gateway what the CDN actually answered for the segments the player
        // requested. Without that, "no video arrived" is six different faults wearing one
        // message, and every round trip becomes a guess.
        const asked = `/api/live/diagnose?id=${encodeURIComponent(channelId)}`;
        void fetch(asked)
          .then((r) => (r.ok ? r.json() : null))
          .then((report: unknown) => {
            const segs = (report as { recentSegments?: Array<{ status?: number; bytes?: number }> } | null)
              ?.recentSegments;
            if (!segs || segs.length === 0) {
              setStatusMsg(
                "The player requested no segments at all, so the manifest it received could not be used.",
              );
              return;
            }
            const refused = segs.filter((x) => (x.status ?? 0) >= 400);
            if (refused.length) {
              const codes = [...new Set(refused.map((x) => x.status))].join(", ");
              setStatusMsg(
                `The CDN refused this channel's segments (HTTP ${codes}). Retry once; if it repeats, the source has rotated its token.`,
              );
              return;
            }
            const bytes = segs.reduce((n, x) => n + (x.bytes ?? 0), 0);
            setStatusMsg(
              bytes > 0
                ? "Segments arrived but produced no picture. The key or the codec is wrong for this channel, not the network."
                : "Segments were requested and answered without data.",
            );
          })
          .catch(() => {
            setStatusMsg(
              "The stream started but no video arrived. The channel's segments were refused or the format is unsupported.",
            );
          });
        return;
      }
      settled = true;
      clearInterval(poll);
    }, 15000);

    check();
    return () => {
      settled = true;
      clearInterval(poll);
      clearTimeout(deadline);
    };
  }, [channelId, reloadNonce, localRetry]);

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
      if (shakaRef.current) {
        try {
          void shakaRef.current.destroy();
        } catch {
          // ignore teardown errors
        }
        shakaRef.current = null;
      }
    };

    const streamUrl = (ext: ".ts" | ".m3u8" | ".mpd", probe = false) =>
      `/api/live/stream/${encodeURIComponent(channelId)}${ext}?_t=${Date.now()}${probe ? "&probe=1" : ""}`;

    const safePlay = async () => {
      const el = videoRef.current;
      if (!el) return;
      try {
        await el.play();
      } catch {
        // Browsers block unmuted autoplay on initial page load with NotAllowedError.
        // Fall back to muted playback and notify parent so the stream plays immediately.
        el.muted = true;
        onAutoMuted?.();
        await el.play().catch(() => undefined);
      }
    };

    // DASH + ClearKey through Shaka. Shaka wants each key as 32 hex chars or as
    // *unpadded* 22-char base64url; a 32-char base64url string (which is what a
    // default_KID produces) is silently misread as hex, so normalise both forms
    // to hex and let Shaka encode them itself.
    const startDash = (url: string, matched?: Array<{ kid: string; k: string }>) => {
      if (cancelled || !videoRef.current) return;
      cleanup();
      const shaka = window.shaka;
      if (!shaka) {
        setHasError(true);
        setStatusMsg("DASH playback engine did not load.");
        return;
      }
      onContainerDetected?.("DASH");
      try {
        shaka.polyfill.installAll();
        if (!shaka.Player.isBrowserSupported()) {
          setHasError(true);
          setStatusMsg("This browser cannot play protected DASH streams.");
          return;
        }
        shaka.log?.setLevel(shaka.LogLevel?.NONE ?? 0);
        const player = new shaka.Player();
        shakaRef.current = player;

        const clearKeys: Record<string, string> = {};
        const source = matched && matched.length > 0 ? matched : (drm?.keys ?? []);
        for (const pair of source) {
          const kid = toHexKeyId(pair.kid);
          const key = toHexKeyId(pair.k);
          if (kid && key) clearKeys[kid] = key;
        }

        player.configure({
          drm: {
            ...(Object.keys(clearKeys).length ? { clearKeys } : {}),
            // Jio's CDN serves a clear-key licence; never block on a server cert.
            advanced: {},
          },
          streaming: {
            lowLatencyMode: true,
            rebufferingGoal: 2,
            bufferingGoal: 20,
            retryParameters: { maxAttempts: 4, baseDelay: 500, backoffFactor: 2, timeout: 20000 },
          },
          manifest: { retryParameters: { maxAttempts: 3, baseDelay: 500, backoffFactor: 2, timeout: 20000 } },
        });

        player.addEventListener("error", (event) => {
          if (cancelled) return;
          const detail = event as { detail?: { code?: number; data?: unknown[] } } | undefined;
          const code = detail?.detail?.code;
          const payload = Array.isArray(detail?.detail?.data) ? detail.detail.data : [];
          // Shaka passes positional arguments, not named fields: BAD_HTTP_STATUS is
          // [uris, status]. Searching for an object with `.status` never matched anything.
          const uris = payload.find((d): d is string[] => Array.isArray(d) && typeof d[0] === "string");
          const numeric = payload.find((d): d is number => typeof d === "number");
          const status = code === 1001 ? numeric : undefined;
          const firstUri = uris?.[0];
          setHasError(true);
          specificErrorRef.current = true;
          setStatusMsg(
            status
              ? `The CDN refused a segment (HTTP ${status}).${firstUri ? ` ${firstUri.split("?")[0]}` : ""}`
              : code === 6001 || code === 6002
                ? "This channel is DRM protected and the key was rejected."
                : `Playback failed${code ? ` (Shaka error ${code})` : ""}. Press Retry.`,
          );
        });

        void (async () => {
          try {
            await player.attach(videoRef.current!);
            await player.load(url);
            if (cancelled) return;
            const langs = player.getAudioLanguages() ?? [];
            if (langs.length > 1) {
              const hindi = langs.find((l) => /^hi/i.test(l));
              if (hindi) player.selectAudioLanguage(hindi);
            }
            const opts: AudioTrackOption[] = langs.map((name, idx) => ({ index: idx, label: name }));
            onAudioTracksChange?.(opts, Math.max(langs.indexOf(player.getAudioLanguage() ?? ""), 0));
            await safePlay();
          } catch {
            if (!cancelled) {
              setHasError(true);
              setStatusMsg("DASH playback failed to start.");
            }
          }
        })();
      } catch {
        setHasError(true);
        setStatusMsg("DASH playback failed to start.");
      }
    };

    const startHls = (url: string, triedDirectFallback = false) => {
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
          void safePlay();
        });
        hls.on(HlsCtor.Events.AUDIO_TRACKS_UPDATED, syncTracks);
        hls.on(HlsCtor.Events.ERROR, (_ev, data) => {
          const d = data as { fatal?: boolean } | undefined;
          if (d?.fatal && !cancelled) {
            if (!triedDirectFallback && directUrl && url !== directUrl) {
              startHls(directUrl, true);
              return;
            }
            setHasError(true);
            setStatusMsg(
              activeSource === "jio"
                ? "This JioTV stream was interrupted. Press Retry to reload it with a fresh CDN token."
                : activeSource === "tgtv"
                  ? "This TG TV stream was interrupted. Press Retry to reconnect."
                  : "Live HLS stream interrupted. Press Retry to request a fresh maxTV token.",
            );
          }
        });
      } else if (videoRef.current.canPlayType("application/vnd.apple.mpegurl")) {
        videoRef.current.src = url;
        void safePlay();
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
      Promise.resolve(mpegtsInstance.play())
        .then(() => safePlay())
        .catch(() => {
          void safePlay();
        });
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
      specificErrorRef.current = false;
      setHasError(false);
      setStatusMsg(`Connecting to ${channelName}…`);
      await ensurePlayerEnginesLoaded();
      if (cancelled) return;

      const probeCtrl = new AbortController();
      const timer = setTimeout(() => probeCtrl.abort(), 8000);
      // DASH manifests are not MPEG-TS: ask for the container the catalogue advertised.
      const wantDash = (container || "").toLowerCase() === "dash";
      const probeExt = wantDash ? ".mpd" : ".ts";
      try {
        const res = await fetch(streamUrl(probeExt, true), { signal: probeCtrl.signal });
        clearTimeout(timer);
        if (!res.ok) {
          const j = (await res.json().catch(() => ({}))) as { error?: string };
          probeCtrl.abort();
          if (!cancelled && directUrl) {
            startHls(directUrl, true);
            return;
          }
          if (!cancelled) {
            setHasError(true);
            setStatusMsg(j.error || `Stream unavailable (HTTP ${res.status})`);
          }
          return;
        }
        const kind = (res.headers.get("x-stream-container") || "").toLowerCase();
        const matched = parseKeyHeader(res.headers.get("x-clear-keys"));
        probeCtrl.abort();
        if (kind === "dash") startDash(streamUrl(".mpd"), matched);
        else if (kind === "hls") startHls(streamUrl(".m3u8"));
        else startMpegTs(streamUrl(".ts"), true);
      } catch {
        clearTimeout(timer);
        if (!cancelled) {
          if (directUrl) startHls(directUrl, true);
          else if (wantDash) startDash(streamUrl(".mpd"));
          else startMpegTs(streamUrl(".ts"), true);
        }
      }
    }

    void run();

    return () => {
      cancelled = true;
      cleanup();
    };
  }, [channelId, channelName, directUrl, drm, container, activeSource, reloadNonce, localRetry, onAudioTracksChange, onContainerDetected, onAutoMuted]);

  return (
    <div className="absolute inset-0 bg-black">
      <video
        ref={videoRef}
        playsInline
        autoPlay
        muted={muted}
        onPlaying={() => {
          clearOverlayIfRendering();
        }}
        onLoadedData={() => {
          clearOverlayIfRendering();
        }}
        onResize={() => {
          clearOverlayIfRendering();
        }}
        onTimeUpdate={() => {
          clearOverlayIfRendering();
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
