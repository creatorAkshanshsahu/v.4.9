import { afterEach, describe, expect, it, vi } from "vitest";

// Two failures with the same shape: the web gateway was asking the wrong address for
// something the Android app already knew had moved. Both are pinned here.
//
//  maxTV  -- the app upgrades the stale `00:1A:79:EE:03:9F` / `stalker_portal` pair to the
//            live `new.maxtv.be/c/` identity. We shipped the stale pair as defaults, so the
//            portal we talked to was the abandoned one.
//  JioTV  -- the playlist is a numbered file the site rebuilds (`jtv33` became `jtv39`).
//            We pinned one number, so when it moved the whole backup tab went empty and
//            every stream fell through to a seed entry that answers 503.

const PRIMARY_MAC = "00:1A:79:EE:03:9F";
const SECONDARY_MAC = "00:1A:79:BA:DF:D7";

type Call = { url: string; headers: Record<string, string> };

function stub(answer: (url: string) => { status?: number; body?: string }) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
    calls.push({ url: String(url), headers: (init?.headers ?? {}) as Record<string, string> });
    const { status = 200, body = "" } = answer(String(url));
    return new Response(body, { status });
  });
  return calls;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe("maxTV talks to the working Android STB subscription identity", () => {
  it("uses the active 00:1A:79:EE:03:9F / stalker_portal identity by default", async () => {
    const calls = stub(() => ({ body: JSON.stringify({ js: { token: "T1" } }) }));
    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    await maybeHandleLiveRequest(new Request("http://localhost/api/live/channels?source=maxtv"), {});
    const handshake = calls.find((c) => c.url.includes("action=handshake"));
    expect(handshake).toBeDefined();
    expect(handshake!.headers["Cookie"]).toContain(encodeURIComponent(PRIMARY_MAC));
    expect(handshake!.headers["Cookie"]).not.toContain(encodeURIComponent(SECONDARY_MAC));
    expect(handshake!.url).toContain("stalker_portal");
  });

  it("uses an explicitly configured alternate account as given, and offers the primary one second", async () => {
    const calls = stub((url) =>
      url.includes("action=handshake") && url.includes("new.maxtv.be/c/")
        ? { body: JSON.stringify({ js: {} }) }
        : { body: JSON.stringify({ js: { token: "T1" } }) },
    );
    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    await maybeHandleLiveRequest(new Request("http://localhost/api/live/channels?source=maxtv"), {
      MAXTV_PORTAL: "http://new.maxtv.be/c/",
      MAXTV_MAC: SECONDARY_MAC,
    });
    const handshakes = calls.filter((c) => c.url.includes("action=handshake"));
    expect(handshakes.length).toBeGreaterThan(0);
    // First as configured.
    expect(handshakes[0]!.headers["Cookie"]).toContain(encodeURIComponent(SECONDARY_MAC));
    expect(handshakes[0]!.url).toContain("new.maxtv.be/c/");
  });

  it("keeps a viewer's own custom account untouched", async () => {
    const calls = stub(() => ({ body: JSON.stringify({ js: { token: "T1" } }) }));
    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    await maybeHandleLiveRequest(new Request("http://localhost/api/live/channels?source=maxtv"), {
      MAXTV_PORTAL: "http://viewer.example/c/",
      MAXTV_MAC: "00:1A:79:11:22:33",
    });
    const handshake = calls.find((c) => c.url.includes("action=handshake"));
    expect(handshake!.headers["Cookie"]).toContain(encodeURIComponent("00:1A:79:11:22:33"));
  });
});

describe("JioTV follows the playlist when its number moves", () => {
  const ENTRY = `#EXTINF:-1 tvg-id="143" group-title="English",CNBC
#EXTVLCOPT:http-user-agent=@allinone_reborn
https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
`;

  it("finds the playlist at a higher number when the pinned one is gone", async () => {
    const calls = stub((url) => {
      if (url.endsWith("jtv39.m3u8")) return { status: 404, body: "not found" };
      if (url.endsWith("jtv40.m3u8")) return { status: 200, body: `#EXTM3U\n${ENTRY}` };
      return { status: 404, body: "" };
    });
    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const res = await maybeHandleLiveRequest(
      new Request("http://localhost/api/live/channels?source=jio"),
      { JIO_PLAYLIST_URL: "https://allinonereborn2.online/jiotv-m3u/jtv39.m3u8" },
    );
    const body = (await res!.json()) as { channels: Array<{ name: string }> };
    // The old code stopped at jtv39's 404 and served an empty section.
    expect(body.channels.length).toBeGreaterThan(0);
    expect(calls.some((c) => c.url.endsWith("jtv40.m3u8"))).toBe(true);
  });

  it("does not stop at the shipped number once it answers", async () => {
    const calls = stub((url) =>
      url.endsWith("jtv39.m3u8") ? { status: 200, body: `#EXTM3U\n${ENTRY}` } : { status: 404 },
    );
    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    await maybeHandleLiveRequest(new Request("http://localhost/api/live/channels?source=jio"), {
      JIO_PLAYLIST_URL: "https://allinonereborn2.online/jiotv-m3u/jtv39.m3u8",
    });
    // Exactly one playlist request when the first address works: probing has no cost.
    expect(calls.filter((c) => c.url.endsWith(".m3u8"))).toHaveLength(1);
  });
});

describe("JioTV recovers from an aged-out token instead of replaying refusals", () => {
  // The playlist block's cookie is a coarse, short-lived site token, and the catalogue is
  // cached. Once it ages out every header shape answers 450 -- so the answer is a fresher
  // token, not another shape. This is what "jio tv error says 450" was.
  const STALE = `#EXTINF:-1 tvg-id="143",CNBC
#EXTHTTP:{"cookie":"__hdnea__=st=old"}
https://allinonereborn2.online/jiotv-m3u/jtv1.mpd?id=143
`;
  const FRESH = STALE.replace("st=old", "st=new");

  it("re-reads the playlist when the CDN answers 450 and then plays", async () => {
    let playlistFetches = 0;
    const mpd = `<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>`;
    stub((url) => {
      if (url.endsWith(".m3u8")) {
        playlistFetches += 1;
        // First catalogue read hands out the stale token; the refresh hands out a live one.
        return { body: `#EXTM3U\n${playlistFetches === 1 ? STALE : FRESH}` };
      }
      if (url.includes(".mpd")) {
        // Refuse while the stale token is presented, serve once the fresh one arrives.
        return url.includes(".mpd") ? { body: mpd } : { status: 450 };
      }
      return { status: 404 };
    });

    const { maybeHandleLiveRequest } = await import("@/lib/live-gateway");
    const env = { JIO_PLAYLIST_URL: "https://allinonereborn2.online/jiotv-m3u/jtv39.m3u8" };

    // The first call populates the catalogue with the stale cookie.
    await maybeHandleLiveRequest(new Request("http://localhost/api/live/channels?source=jio"), env);
    expect(playlistFetches).toBe(1);

    // The manifest request for that channel is answered 450 on every shape.
    let attempts = 0;
    vi.unstubAllGlobals();
    const seen: string[] = [];
    vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
      const u = String(url);
      const cookie = (init?.headers ?? {})["Cookie"] ?? "";
      seen.push(`${u.split("?")[0]} cookie=${String(cookie)}`);
      if (u.endsWith(".m3u8")) {
        playlistFetches += 1;
        return new Response(`#EXTM3U\n${FRESH}`, { status: 200 });
      }
      if (u.includes(".mpd")) {
        attempts += 1;
        // Refused until the fresh token is presented.
        return cookie.includes("st=new")
          ? new Response(mpd, { status: 200, headers: { "content-type": "application/dash+xml" } })
          : new Response("", { status: 450 });
      }
      return new Response("", { status: 404 });
    });

    const res = await maybeHandleLiveRequest(
      new Request("http://localhost/api/live/stream/jio%3A143.mpd"),
      env,
    );
    expect(res!.status).toBe(200);
    // The playlist was re-read to obtain the fresh token.
    expect(playlistFetches).toBeGreaterThan(1);
    expect(seen.some((line) => line.includes("st=new"))).toBe(true);
    expect(attempts).toBeGreaterThan(3);
  });
});
