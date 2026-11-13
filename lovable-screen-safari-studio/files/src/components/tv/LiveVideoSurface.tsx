import { useEffect, useRef, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import type { DirectPlayCandidate } from "@/lib/live-gateway";

export interface AudioTrackOption {
  index: number;
  label: string;
}

interface ShakaRequest {
  uris: string[];
  headers: Record<string, string>;
}

interface ShakaResponse {
  uri: string;
  data: ArrayBuffer;
  headers: Record<string, string>;
}

interface ShakaNetworkingEngine {
  registerRequestFilter: (filter: (type: number, request: ShakaRequest) => void) => void;
  registerResponseFilter: (filter: (type: number, response: ShakaResponse) => void) => void;
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
  getNetworkingEngine?: () => ShakaNetworkingEngine | null;
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
      net?: {
        NetworkingEngine?: {
          RequestType?: { MANIFEST?: number; SEGMENT?: number };
        };
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
 * a hex parser. Converting everything (including UUID forms with hyphens) to 32
 * lowercase hex chars removes any ambiguity.
 */
function toHexKeyId(value: string): string | null {
  const v = (value ?? "").trim();
  if (!v) return null;
  const cleanUuid = v.replace(/-/g, "");
  if (/^[0-9a-fA-F]{32}$/.test(cleanUuid)) return cleanUuid.toLowerCase();
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

/** Ensure `no-referrer` is active in `document.head` so Jio CDN never sees a third-party Referer (HTTP 451). */
function ensureNoReferrerMeta(): void {
  if (typeof document === "undefined") return;
  let meta = document.querySelector('meta[name="referrer"]') as HTMLMetaElement | null;
  if (!meta) {
    meta = document.createElement("meta");
    meta.name = "referrer";
    document.head.appendChild(meta);
  }
  if (meta.content !== "no-referrer") meta.content = "no-referrer";
}

let scriptsPromise: Promise<void> | null = null;

function ensurePlayerEnginesLoaded(): Promise<void> {
  if (typeof window === "undefined") return Promise.resolve();
  ensureNoReferrerMeta();
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
  /** Set when a concrete fault has already been reported; the 15s timer must never overwrite it. */
  const specificErrorRef = useRef(false);
  /** True when playing directly against upstream CDN from the browser (no server segment log). */
  const directModeRef = useRef(false);

  const reportError = (msg: string) => {
    specificErrorRef.current = true;
    setHasError(true);
    setStatusMsg(msg);
  };

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
        // Never overwrite a specific HTTP/Shaka/HLS error with a generic watchdog guess.
        if (specificErrorRef.current) return;
        // When streaming browser-direct to cdn.jio.com, segments never pass through /api/live/segment.
        if (directModeRef.current) {
          setStatusMsg(
            "Direct CDN stream is taking too long to deliver video frames. Press Retry to switch token/server.",
          );
          return;
        }
        const asked = `/api/live/diagnose?id=${encodeURIComponent(channelId)}`;
        void fetch(asked)
          .then((r) => (r.ok ? r.json() : null))
          .then((report: unknown) => {
            if (specificErrorRef.current) return;
            const segs = (report as { recentSegments?: Array<{ status?: number; bytes?: number }> } | null)
              ?.recentSegments;
            if (!segs || segs.length === 0) {
              setStatusMsg(
                "The stream started but no video segments arrived in time. Press Retry to reconnect.",
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
            if (!specificErrorRef.current) {
              setStatusMsg(
                "The stream started but no video arrived. The channel's segments were refused or the format is unsupported.",
              );
            }
          });
        return;
      }
      settled = true;
      clearInterval(poll);
    }, 18000);

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

    const syncShakaAudioAndPlay = async (player: ShakaPlayerInstance) => {
      if (cancelled) return;
      const langs = player.getAudioLanguages() ?? [];
      if (langs.length > 1) {
        const hindi = langs.find((l) => /^hi/i.test(l));
        if (hindi) player.selectAudioLanguage(hindi);
      }
      const opts: AudioTrackOption[] = langs.map((name, idx) => ({ index: idx, label: name }));
      onAudioTracksChange?.(opts, Math.max(langs.indexOf(player.getAudioLanguage() ?? ""), 0));
      await safePlay();
    };

    /**
     * Browser-direct playback against `jiotvmblive.cdn.jio.com` / `jiotvpllive.cdn.jio.com`.
     *
     * Cloudflare Workers (`AS13335`) are geo/ASN-blocked on media streams (`HTTP 403` on maxTV,
     * `HTTP 450/451` on Jio CDN), whereas the viewer's own Indian browser IP receives `200 OK`
     * with `Access-Control-Allow-Origin: *` when:
     *   1. `no-referrer` is set,
     *   2. Shaka's `registerRequestFilter` appends `?__hdnea__=<token>` to MANIFEST & SEGMENT URIs,
     *   3. ClearKey hex pairs are normalized and bound to the MPD's `cenc:default_KID`.
     */
    const tryBrowserDirect = async (forceRefresh: boolean): Promise<boolean> => {
      if (cancelled || !videoRef.current) return false;
      ensureNoReferrerMeta();
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), 9000);
      let candidates: DirectPlayCandidate[] = [];
      try {
        const q = `/api/live/direct-config?id=${encodeURIComponent(channelId)}&name=${encodeURIComponent(channelName)}${forceRefresh ? "&refresh=1" : ""}`;
        const res = await fetch(q, { signal: ctrl.signal });
        if (!res.ok) return false;
        const data = (await res.json().catch(() => null)) as { candidates?: DirectPlayCandidate[] } | null;
        if (!data || !Array.isArray(data.candidates) || data.candidates.length === 0) return false;
        candidates = data.candidates;
      } catch {
        return false;
      } finally {
        clearTimeout(t);
      }

      if (cancelled || !videoRef.current) return false;

      const shaka = window.shaka;
      const manifestType = shaka?.net?.NetworkingEngine?.RequestType?.MANIFEST ?? 0;
      const segmentType = shaka?.net?.NetworkingEngine?.RequestType?.SEGMENT ?? 1;

      for (const cand of candidates) {
        if (cancelled || !videoRef.current) return false;

        if (cand.container === "hls") {
          directModeRef.current = true;
          const signedHls = cand.cookie
            ? `${cand.url}${cand.url.includes("?") ? "&" : "?"}${cand.cookie}`
            : cand.url;
          startHls(signedHls, true);
          return true;
        }

        if (!shaka) continue;
        shaka.polyfill.installAll();
        if (!shaka.Player.isBrowserSupported()) continue;
        shaka.log?.setLevel(shaka.LogLevel?.NONE ?? 0);

        const normalizedKeys: Record<string, string> = {};
        for (const [rawKid, rawK] of Object.entries(cand.clearKeys || {})) {
          const kid = toHexKeyId(rawKid);
          const k = toHexKeyId(rawK);
          if (kid && k) normalizedKeys[kid] = k;
        }

        // Mirror TgPlayer's failover: try `{ url, cookie }` first, then `{ url, cookie: "" }`.
        const cookieAttempts = cand.cookie ? [cand.cookie, ""] : [""];
        for (const activeCookie of cookieAttempts) {
          if (cancelled || !videoRef.current) return false;
          cleanup();
          directModeRef.current = true;
          onContainerDetected?.("DASH");

          const player = new shaka.Player();
          shakaRef.current = player;
          player.configure({
            drm: {
              ...(Object.keys(normalizedKeys).length ? { clearKeys: normalizedKeys } : {}),
              advanced: {},
            },
            streaming: {
              lowLatencyMode: true,
              rebufferingGoal: 2,
              bufferingGoal: 20,
              retryParameters: { maxAttempts: 2, baseDelay: 400, backoffFactor: 2, timeout: 12000 },
            },
            manifest: {
              retryParameters: { maxAttempts: 2, baseDelay: 400, backoffFactor: 2, timeout: 12000 },
            },
          });

          const net = player.getNetworkingEngine?.();
          if (net && activeCookie) {
            net.registerRequestFilter((type, request) => {
              if ((type === manifestType || type === segmentType) && request.uris?.[0]) {
                const u = request.uris[0];
                if (!u.includes("allinonereborn") && !u.includes("__hdnea__=")) {
                  const base = u.split("?")[0]!;
                  request.uris[0] = `${base}?${activeCookie}`;
                }
              }
            });
          }

          if (net && Object.keys(normalizedKeys).length > 0) {
            net.registerResponseFilter((type, response) => {
              if (type !== manifestType || !response.data) return;
              try {
                let xml = new TextDecoder().decode(response.data);
                if (!/<MPD[\s>]/i.test(xml)) return;
                const mpdKids = [...xml.matchAll(/default_KID\s*=\s*"([^"]+)"/gi)]
                  .map((m) => toHexKeyId(m[1]!))
                  .filter((x): x is string => Boolean(x));
                const pairs = Object.entries(normalizedKeys);
                if (pairs.length === 1 && mpdKids.length > 0 && !normalizedKeys[mpdKids[0]!]) {
                  const expanded: Record<string, string> = { ...normalizedKeys };
                  for (const mk of mpdKids) expanded[mk] = pairs[0]![1];
                  player.configure({ drm: { clearKeys: expanded, advanced: {} } });
                }
                // Strip Widevine/PlayReady descriptors so ClearKey EME is selected cleanly on all browsers.
                xml = xml.replace(
                  /<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*>[\s\S]*?<\/ContentProtection>/gi,
                  "",
                );
                xml = xml.replace(
                  /<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*\/>/gi,
                  "",
                );
                response.data = new TextEncoder().encode(xml).buffer;
              } catch {
                // leave manifest untouched if decoding fails
              }
            });
          }

          try {
            await player.attach(videoRef.current);
            await player.load(cand.url);
            if (cancelled) return false;

            // Attach runtime error handler after initial manifest+init load succeeds.
            player.addEventListener("error", (event) => {
              if (cancelled) return;
              const detail = event as { detail?: { code?: number; data?: unknown[] } } | undefined;
              const code = detail?.detail?.code;
              const payload = Array.isArray(detail?.detail?.data) ? detail.detail.data : [];
              const numeric = payload.find((d): d is number => typeof d === "number");
              const status = code === 1001 ? numeric : undefined;
              reportError(
                status
                  ? `Direct CDN segment returned HTTP ${status}. Press Retry to refresh token.`
                  : `Live stream interrupted${code ? ` (Shaka ${code})` : ""}. Press Retry.`,
              );
            });

            await syncShakaAudioAndPlay(player);
            return true;
          } catch {
            // Try next cookie mode / next server candidate!
          }
        }
      }

      directModeRef.current = false;
      return false;
    };

    const startDash = (url: string, matched?: Array<{ kid: string; k: string }>) => {
      if (cancelled || !videoRef.current) return;
      cleanup();
      directModeRef.current = false;
      const shaka = window.shaka;
      if (!shaka) {
        reportError("DASH playback engine did not load.");
        return;
      }
      onContainerDetected?.("DASH");
      try {
        shaka.polyfill.installAll();
        if (!shaka.Player.isBrowserSupported()) {
          reportError("This browser cannot play protected DASH streams.");
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
          const uris = payload.find((d): d is string[] => Array.isArray(d) && typeof d[0] === "string");
          const numeric = payload.find((d): d is number => typeof d === "number");
          const status = code === 1001 ? numeric : undefined;
          const firstUri = uris?.[0];
          reportError(
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
            await syncShakaAudioAndPlay(player);
          } catch {
            if (!cancelled) {
              const rescued = await tryBrowserDirect(true);
              if (!rescued && !cancelled) reportError("DASH playback failed to start.");
            }
          }
        })();
      } catch {
        reportError("DASH playback failed to start.");
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
            if (!triedDirectFallback) {
              void (async () => {
                if (await tryBrowserDirect(false)) return;
                if (!cancelled && directUrl && url !== directUrl) {
                  startHls(directUrl, true);
                  return;
                }
                if (!cancelled) {
                  reportError(
                    activeSource === "jio"
                      ? "This JioTV stream was interrupted. Press Retry to reload it with a fresh CDN token."
                      : activeSource === "tgtv"
                        ? "This TG TV stream was interrupted. Press Retry to reconnect."
                        : "Live HLS stream interrupted. Press Retry to request a fresh stream token.",
                  );
                }
              })();
              return;
            }
            reportError(
              activeSource === "jio"
                ? "This JioTV stream was interrupted. Press Retry to reload it with a fresh CDN token."
                : activeSource === "tgtv"
                  ? "This TG TV stream was interrupted. Press Retry to reconnect."
                  : "Live HLS stream interrupted. Press Retry to request a fresh stream token.",
            );
          }
        });
      } else if (videoRef.current.canPlayType("application/vnd.apple.mpegurl")) {
        videoRef.current.src = url;
        void safePlay();
      } else {
        reportError("HLS playback is not supported in this browser.");
      }
    };

    const startMpegTs = (url: string, fallbackToHls: boolean) => {
      if (cancelled || !videoRef.current) return;
      cleanup();
      directModeRef.current = false;
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
          void (async () => {
            if (await tryBrowserDirect(false)) return;
            if (!cancelled) reportError("Stream interrupted. Click Retry to request a fresh live link.");
          })();
        }
      });
    };

    async function run() {
      specificErrorRef.current = false;
      directModeRef.current = false;
      setHasError(false);
      setStatusMsg(`Connecting to ${channelName}…`);
      await ensurePlayerEnginesLoaded();
      if (cancelled) return;

      const forceRefresh = localRetry > 0 || reloadNonce > 0;
      const preferDirectFirst =
        activeSource === "jio" ||
        activeSource === "tgtv" ||
        /^(?:jio|tg):/i.test(channelId);

      // JioTV and TG TV streams must play directly from the viewer's browser IP because
      // Cloudflare Worker datacenter egress IPs receive HTTP 450/451 from cdn.jio.com.
      if (preferDirectFirst) {
        const startedDirect = await tryBrowserDirect(forceRefresh);
        if (startedDirect || cancelled) return;
      }

      const probeCtrl = new AbortController();
      const timer = setTimeout(() => probeCtrl.abort(), 8000);
      const wantDash = (container || "").toLowerCase() === "dash";
      const probeExt = wantDash ? ".mpd" : ".ts";
      try {
        const res = await fetch(streamUrl(probeExt, true), { signal: probeCtrl.signal });
        clearTimeout(timer);
        if (!res.ok) {
          const j = (await res.json().catch(() => ({}))) as { error?: string };
          probeCtrl.abort();
          // When maxTV/Cloudflare Worker proxy gets HTTP 403/502, rescue the channel immediately
          // via Browser-Direct CDN playback matched by channel name/ID!
          if (!cancelled) {
            const rescued = await tryBrowserDirect(forceRefresh);
            if (rescued || cancelled) return;
          }
          if (!cancelled && directUrl) {
            startHls(directUrl, true);
            return;
          }
          if (!cancelled) {
            reportError(j.error || `Stream unavailable (HTTP ${res.status})`);
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
          const rescued = await tryBrowserDirect(forceRefresh);
          if (rescued || cancelled) return;
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
