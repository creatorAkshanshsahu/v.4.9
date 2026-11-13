import { afterEach, describe, expect, it, vi } from "vitest";

afterEach(() => {
  delete (globalThis as { __env__?: unknown }).__env__;
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe("Cloudflare Worker Nitro environment resolution", () => {
  it("reads MAXTV_MAC and MAXTV_PORTAL from globalThis.__env__ when Nitro calls fetch(req) with env=undefined", async () => {
    const seenCookies: string[] = [];
    vi.stubGlobal("fetch", async (_url: string, init?: { headers?: Record<string, string> }) => {
      const ck = init?.headers?.["Cookie"];
      if (ck) seenCookies.push(ck);
      return new Response(JSON.stringify({ js: { token: "TOK" } }), { status: 200 });
    });

    (globalThis as { __env__?: unknown }).__env__ = {
      MAXTV_PORTAL: "http://cf-worker-portal.example/c/",
      MAXTV_MAC: "00:1A:79:CF:00:01",
    };

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    await maybeHandleLiveRequest(new Request("https://screen-safari-studio.lovable.app/api/live/channels?source=maxtv"), undefined);

    expect(seenCookies.some((c) => c.includes(encodeURIComponent("00:1A:79:CF:00:01")))).toBe(true);
  });
});

describe("Single-use create_link probe-to-player handoff", () => {
  it("calls Stalker create_link only ONCE when ?probe=1 is followed by HLS .m3u8 playback", async () => {
    let createLinkCount = 0;
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("action=handshake")) {
        return new Response(JSON.stringify({ js: { token: "TOK1" } }), { status: 200 });
      }
      if (u.includes("action=get_profile") || u.includes("action=get_genres")) {
        return new Response(JSON.stringify({ js: [] }), { status: 200 });
      }
      if (u.includes("action=get_all_channels")) {
        return new Response(
          JSON.stringify({
            js: {
              data: [{ id: "501", name: "Star Sports 1 Hindi HD", cmd: "ffrt http://localhost/ch/501", tv_genre_id: "1" }],
            },
          }),
          { status: 200 },
        );
      }
      if (u.includes("action=create_link")) {
        createLinkCount += 1;
        return new Response(
          JSON.stringify({
            js: { cmd: `ffmpeg http://edge.maxtv.example/play/live.php?stream=501&play_token=ONCE_${createLinkCount}` },
          }),
          { status: 200 },
        );
      }
      if (u.includes("edge.maxtv.example/play/live.php")) {
        // Single-use token: second call would be rejected with 403 by real Stalker panels!
        if (!u.includes("play_token=ONCE_1")) {
          return new Response("token already consumed", { status: 403 });
        }
        return new Response("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg01.ts\n", {
          status: 200,
          headers: { "content-type": "application/vnd.apple.mpegurl" },
        });
      }
      return new Response("", { status: 404 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");

    // 1. Container probe (?probe=1)
    const probeRes = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/stream/st%3A501.ts?probe=1"),
      {},
    );
    expect(probeRes!.status).toBe(200);
    expect(probeRes!.headers.get("x-stream-container")).toBe("hls");

    // 2. Immediate HLS.js manifest load (without probe=1) must reuse the handoff manifest
    //    without burning a second create_link token!
    const playRes = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/stream/st%3A501.m3u8"),
      {},
    );
    expect(playRes!.status).toBe(200);
    expect(await playRes!.text()).toContain("/api/live/segment?t=");
    expect(createLinkCount).toBe(1);
  });
});

describe("Catalogue-level maxTV identity fallback", () => {
  it("falls back to the secondary MAC when the first MAC handshakes but returns zero channels", async () => {
    vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
      const u = String(url);
      const cookie = init?.headers?.["Cookie"] ?? "";
      const isSecondary = cookie.includes(encodeURIComponent("00:1A:79:BA:DF:D7"));
      if (u.includes("action=handshake")) {
        return new Response(JSON.stringify({ js: { token: isSecondary ? "TOK_SEC" : "TOK_PRI" } }), { status: 200 });
      }
      if (u.includes("action=get_all_channels")) {
        if (!isSecondary) {
          return new Response(JSON.stringify({ js: { data: [] } }), { status: 200 });
        }
        return new Response(
          JSON.stringify({
            js: { data: [{ id: "77", name: "Sony Max HD", cmd: "ffrt http://localhost/ch/77", tv_genre_id: "1" }] },
          }),
          { status: 200 },
        );
      }
      return new Response(JSON.stringify({ js: [] }), { status: 200 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/channels?source=maxtv&filter=all"),
      {},
    );
    const json = (await res!.json()) as { usingPreviewSeed: boolean; channels: Array<{ name: string }> };
    expect(json.usingPreviewSeed).toBe(false);
    expect(json.channels.map((c) => c.name)).toContain("Sony Max HD");
  });
});

describe("Widevine-only DASH manifest ClearKey normalization", () => {
  it("replaces Widevine ContentProtection with W3C ClearKey descriptors when ClearKeys are supplied", async () => {
    const PLAYLIST = `#EXTM3U
#EXTINF:-1 tvg-id="143" group-title="Hindi",Colors HD
#KODIPROP:inputstream.adaptive.license_type=clearkey
#KODIPROP:inputstream.adaptive.license_key=00112233445566778899aabbccddeeff:ffeeddccbbaa99887766554433221100
https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
`;
    const WIDEVINE_ONLY_MPD = `<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="dynamic">
  <Period id="0">
    <AdaptationSet mimeType="video/mp4">
      <ContentProtection value="cenc" schemeIdUri="urn:mpeg:dash:mp4protection:2011" cenc:default_KID="00112233-4455-6677-8899-aabbccddeeff"/>
      <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"><cenc:pssh>AAAA</cenc:pssh></ContentProtection>
      <Representation id="1" bandwidth="1200000">
        <SegmentTemplate media="seg_$Number$.m4s" initialization="init.mp4"/>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>`;

    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("jtv39.m3u8")) return new Response(PLAYLIST, { status: 200 });
      if (u.includes("jtv1.mpd")) {
        return new Response(WIDEVINE_ONLY_MPD, { status: 200, headers: { "content-type": "application/dash+xml" } });
      }
      return new Response("", { status: 404 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/stream/jio%3A143.mpd"),
      { JIO_PLAYLIST_URL: "https://allinonereborn2.online/jiotv-m3u/jtv39.m3u8" },
    );
    expect(res!.status).toBe(200);
    const body = await res!.text();
    // Widevine UUID stripped so Shaka does not fail with Error 6001 on browsers without Widevine
    expect(body).not.toContain("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");
    // W3C ClearKey descriptor injected alongside mp4protection
    expect(body).toContain("e2719d58-a985-b3c9-781a-b030af78d30e");
  });
});

describe("TG TV direct HLS live source & fallback", () => {
  const TGTV_M3U = `#EXTM3U
#EXTINF:-1 tvg-id="tg1" group-title="Sports",Star Sports 1 Hindi
https://cdn.tgtv.example/live/ss1h/index.m3u8
`;

  it("falls back to TG TV automatically when both maxTV and JioTV are unreachable", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("livetgtv.lovable.app")) {
        return new Response(TGTV_M3U, { status: 200 });
      }
      return new Response("", { status: 403 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/channels?source=maxtv&filter=hindi"),
      {},
    );
    const json = (await res!.json()) as {
      servedBy: string;
      usingPreviewSeed: boolean;
      channels: Array<{ id: string; name: string; directUrl?: string }>;
    };
    expect(json.usingPreviewSeed).toBe(false);
    expect(json.servedBy).toBe("tgtv");
    expect(json.channels[0]!.name).toBe("Star Sports 1 Hindi");
    expect(json.channels[0]!.directUrl).toBe("https://cdn.tgtv.example/live/ss1h/index.m3u8");
  });
});
