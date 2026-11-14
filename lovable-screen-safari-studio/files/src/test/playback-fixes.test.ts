import { afterEach, describe, expect, it, vi } from "vitest";
import {
  extractHdneaParam,
  isHlsPlaylistUrl,
  sameUrlHost,
  withHdneaToken,
} from "@/lib/direct-token";

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

describe("Browser-Direct CDN playback (/api/live/direct-config) for Cloudflare Worker ASN bypass", () => {
  const GIO_CATALOGUE = JSON.stringify([
    {
      id: "892",
      name: "Sony SAB HD",
      category: "Entertainment",
      logo: "https://cdn.example/sab.png",
      url: "https://jiotvmblive.cdn.jio.com/bpk-tv//Sony_SAB_HD_MOB/WDVLive/index.mpd",
      keys: [{ keyId: "400131994b445d8c8817202248760fda", key: "2d56ef4bfcfb9fb0ae172f29b9cd6bfe" }],
    },
    {
      id: "165",
      name: "Star Sports 1 Hindi HD",
      category: "Sports",
      logo: "https://cdn.example/ss1h.png",
      url: "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd",
      keyId: "a8e437645c175bc29ab021807569e5f9",
      key: "6ab0c15a3f11f89bfdd4a05ec1b1fc3c",
    },
    {
      id: "166",
      name: "Star Sports 2 HD",
      category: "Sports",
      logo: "https://cdn.example/ss2.png",
      url: "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD2_BTS/WDVLive/index.mpd",
      keyId: "11111111111111111111111111111111",
      key: "22222222222222222222222222222222",
    },
  ]);

  const REGULAR_COOKIES = JSON.stringify([
    { cookie: "__hdnea__=st=1794000000~exp=1999999999~acl=/*~hmac=regular_token_abc" },
  ]);

  const STAR_COOKIES = JSON.stringify({
    failed_results: [
      {
        channel_id: "165",
        error_details: {
          final_url:
            "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd?__hdnea__=st=1794000000~exp=1999999999~acl=/*~hmac=star_165_token_xyz",
        },
      },
    ],
  });

  function makeFramedTgRpcResponse(payloadObj: unknown): Response {
    const jsonBytes = new TextEncoder().encode(JSON.stringify(payloadObj));
    const framed = new Uint8Array(9 + jsonBytes.byteLength);
    const view = new DataView(framed.buffer);
    view.setUint8(0, 0);
    view.setInt32(1, 0, false);
    view.setInt32(5, jsonBytes.byteLength, false);
    framed.set(jsonBytes, 9);
    return new Response(framed, {
      status: 200,
      headers: { "content-type": "application/x-tss-framed" },
    });
  }

  it("decodes TG _serverFn x-tss-framed RPC + GioTV tokens and normalizes bpk-tv// URLs", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("giotv-6op.pages.dev/jstr4web.json")) return new Response(GIO_CATALOGUE, { status: 200 });
      if (u.includes("allinonereborn2.online/jstrweb2/cookies.json")) return new Response(REGULAR_COOKIES, { status: 200 });
      if (u.includes("livetgtv.lovable.app/_serverFn/")) {
        return makeFramedTgRpcResponse({
          result: {
            urls: ["https://jiotvmblive.cdn.jio.com/bpk-tv//Sony_SAB_HD_MOB/WDVLive/index.mpd"],
            cookie: "__hdnea__=st=1794000000~exp=1999999999~acl=/*~hmac=tg_srv1_hmac",
            clearKeys: { "40013199-4b44-5d8c-8817-202248760fda": "2d56ef4bfcfb9fb0ae172f29b9cd6bfe" },
            backups: [
              {
                urls: ["https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/Fallback/index.mpd"],
                cookie: "__hdnea__=st=1794000000~exp=1999999999~acl=/*~hmac=tg_srv2_hmac",
              },
            ],
          },
        });
      }
      return new Response("", { status: 404 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/direct-config?id=jio%3A892&refresh=1"),
      {},
    );
    expect(res!.status).toBe(200);
    const body = (await res!.json()) as {
      ok: boolean;
      matchedId: string;
      candidates: Array<{ label: string; url: string; cookie: string; clearKeys: Record<string, string> }>;
    };
    expect(body.ok).toBe(true);
    expect(body.matchedId).toBe("892");
    expect(body.candidates.length).toBeGreaterThanOrEqual(3);
    // Double slash normalized to single slash so Jio CDN never returns 404
    expect(body.candidates[0]!.url).toBe("https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/WDVLive/index.mpd");
    expect(body.candidates[0]!.cookie).toContain("tg_srv1_hmac");
    // Hyphenated UUID KID normalized to 32-char hex
    expect(body.candidates[0]!.clearKeys["400131994b445d8c8817202248760fda"]).toBe("2d56ef4bfcfb9fb0ae172f29b9cd6bfe");
  });

  it("rescues a maxTV channel (st:1001, Star Sports 1 Hindi HD) via Star per-channel cookie without matching Star Sports 2", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("giotv-6op.pages.dev/jstr4web.json")) return new Response(GIO_CATALOGUE, { status: 200 });
      if (u.includes("giotv-6op.pages.dev/cookie.json")) return new Response(STAR_COOKIES, { status: 200 });
      return new Response("", { status: 403 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request(
        "https://screen-safari-studio.lovable.app/api/live/direct-config?id=st%3A1001&name=IN%3A%20Star%20Sports%201%20Hindi%20HD&refresh=1",
      ),
      {},
    );
    expect(res!.status).toBe(200);
    const body = (await res!.json()) as {
      matchedId: string;
      matchedName: string;
      candidates: Array<{ url: string; cookie: string; clearKeys: Record<string, string> }>;
    };
    expect(body.matchedId).toBe("165");
    expect(body.matchedName).toBe("Star Sports 1 Hindi HD");
    expect(body.candidates[0]!.url).toBe(
      "https://jiotvpllive.cdn.jio.com/bpk-tv/Star_Sports_HD1_Hindi_BTS/WDVLive/index.mpd",
    );
    expect(body.candidates[0]!.cookie).toContain("star_165_token_xyz");
    expect(body.candidates[0]!.clearKeys["a8e437645c175bc29ab021807569e5f9"]).toBe("6ab0c15a3f11f89bfdd4a05ec1b1fc3c");
  });

  it("serves JioTV catalogue from giotv-6op.pages.dev/jstr4web.json when allinonereborn2.online blocks Cloudflare Workers", async () => {
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("allinonereborn2.online")) return new Response("", { status: 450 });
      if (u.includes("giotv-6op.pages.dev/jstr4web.json")) return new Response(GIO_CATALOGUE, { status: 200 });
      return new Response("", { status: 404 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/channels?source=jio&filter=all&refresh=1"),
      {},
    );
    const body = (await res!.json()) as {
      servedBy: string;
      channels: Array<{ id: string; name: string; container?: string }>;
    };
    expect(body.servedBy).toBe("jio");
    expect(body.channels.length).toBe(3);
    expect(body.channels.map((c) => c.id)).toContain("jio:892");
    expect(body.channels.map((c) => c.id)).toContain("jio:165");
    expect(body.channels[0]!.container).toBe("dash");
  });

  it("prunes expired embedded tokens and orders freshest candidates first", async () => {
    const EXPIRED_CATALOGUE = JSON.stringify([
      {
        id: "892",
        name: "Sony SAB HD",
        category: "Entertainment",
        logo: "https://cdn.example/sab.png",
        url: "https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/WDVLive/index.mpd?__hdnea__=st=1~exp=1000000000~acl=/*~hmac=expired_embedded",
        keys: [{ keyId: "400131994b445d8c8817202248760fda", key: "2d56ef4bfcfb9fb0ae172f29b9cd6bfe" }],
      },
    ]);
    vi.stubGlobal("fetch", async (url: string) => {
      const u = String(url);
      if (u.includes("giotv-6op.pages.dev/jstr4web.json")) return new Response(EXPIRED_CATALOGUE, { status: 200 });
      if (u.includes("allinonereborn2.online/jstrweb2/cookies.json")) return new Response(REGULAR_COOKIES, { status: 200 });
      if (u.includes("livetgtv.lovable.app/_serverFn/")) {
        return makeFramedTgRpcResponse({
          result: {
            urls: ["https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/TGServer/index.mpd"],
            cookie: "__hdnea__=st=1~exp=1000000000~acl=/*~hmac=expired_tg",
            clearKeys: { "400131994b445d8c8817202248760fda": "2d56ef4bfcfb9fb0ae172f29b9cd6bfe" },
          },
        });
      }
      return new Response("", { status: 404 });
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("https://screen-safari-studio.lovable.app/api/live/direct-config?id=jio%3A892&refresh=1"),
      {},
    );
    expect(res!.status).toBe(200);
    const body = (await res!.json()) as {
      candidates: Array<{ label: string; url: string; cookie: string }>;
    };
    // Expired embedded token pruned: the same verbatim URL keeps only its fresh-token variant.
    const gioBase = "https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/WDVLive/index.mpd";
    const gioVariants = body.candidates.filter((c) => c.url.split("?")[0] === gioBase);
    expect(gioVariants.length).toBe(1);
    expect(gioVariants[0]!.url).toBe(
      "https://jiotvmblive.cdn.jio.com/bpk-tv/Sony_SAB_HD_MOB/WDVLive/index.mpd?__hdnea__=st=1~exp=1000000000~acl=/*~hmac=expired_embedded",
    );
    expect(gioVariants[0]!.cookie).toContain("regular_token_abc");
    // Freshest-first ordering: fresh Gio candidate precedes the expired TG fallback.
    expect(body.candidates[0]!.cookie).toContain("regular_token_abc");
    expect(body.candidates[body.candidates.length - 1]!.cookie).toContain("expired_tg");
  });
});

describe("App-faithful direct token policy (StreamRequestPolicy port)", () => {
  const FRESH = "__hdnea__=st=1794000000~exp=1999999999~acl=/*~hmac=fresh";

  it("appends the token while preserving sibling params and fragments", () => {
    expect(withHdneaToken("https://cdn.example/a/index.mpd", FRESH, false)).toBe(
      `https://cdn.example/a/index.mpd?${FRESH}`,
    );
    expect(withHdneaToken("https://cdn.example/a/index.mpd?id=5", FRESH, false)).toBe(
      `https://cdn.example/a/index.mpd?id=5&${FRESH}`,
    );
    expect(withHdneaToken("https://cdn.example/a/index.mpd#frag", FRESH, false)).toBe(
      `https://cdn.example/a/index.mpd?${FRESH}#frag`,
    );
  });

  it("never overwrites a token already on a segment URL, but replaces it on the root manifest", () => {
    const signed = "https://cdn.example/a/seg-1.m4s?__hdnea__=st=1~exp=2~acl=/a/*~hmac=minted&id=5";
    expect(withHdneaToken(signed, FRESH, false)).toBe(signed);
    expect(withHdneaToken(signed, FRESH, true)).toBe(`https://cdn.example/a/seg-1.m4s?id=5&${FRESH}`);
  });

  it("extracts raw token pairs for redirect learning and scopes tokens to their own host", () => {
    expect(extractHdneaParam("https://cdn.example/a.mpd?__hdnea__=st=1~exp=2&x=1")).toBe("__hdnea__=st=1~exp=2");
    expect(extractHdneaParam("no token here")).toBeNull();
    expect(sameUrlHost("https://JIOTVMBLIVE.cdn.jio.com/a", "https://jiotvmblive.cdn.jio.com/b")).toBe(true);
    expect(sameUrlHost("https://a.example/x", "https://b.example/x")).toBe(false);
    expect(sameUrlHost("not a url", "https://a.example/")).toBe(false);
    expect(isHlsPlaylistUrl("https://cdn.example/a/index.m3u8?__hdnea__=x")).toBe(true);
    expect(isHlsPlaylistUrl("https://cdn.example/a/seg-1.ts")).toBe(false);
    expect(isHlsPlaylistUrl("https://cdn.example/a/seg-1.m4s")).toBe(false);
  });
});

