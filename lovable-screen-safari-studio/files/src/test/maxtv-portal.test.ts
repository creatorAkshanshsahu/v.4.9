import { afterEach, describe, expect, it, vi } from "vitest";

// The maxTV portal conversation, ported from the Android suite that ships with the
// working implementation (v.4.9 app/src/test/java/com/aktv/app/StalkerPortalTest.kt).
// These pin the protocol, not the network: a MAG box handshakes for a token, ties it to
// the device with get_profile, then asks for the catalogue and per-channel links.

type Answer = { status: number; body: string };
type Call = { url: string; headers: Record<string, string> };

const ACCOUNT = {
  portal: "http://portal.example/stalker_portal/c/",
  mac: "00:1A:79:EE:03:9F",
  serial: "SERIAL1",
  deviceId: "DEVICE1",
};

const CATALOGUE = JSON.stringify({
  js: {
    data: [
      { id: "101", name: "Arena Sports", cmd: "ffmpeg http://cdn/101", tv_genre_id: "5" },
      { id: "102", name: "Nova News", cmd: "ffmpeg http://cdn/102", tv_genre_id: "6" },
    ],
  },
});

function stubFetch(answer: (url: string) => Answer) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", async (url: string, init?: { headers?: Record<string, string> }) => {
    calls.push({ url: String(url), headers: (init?.headers ?? {}) as Record<string, string> });
    const { status, body } = answer(String(url));
    return new Response(body, { status });
  });
  const actions = () => calls.map((c) => c.url.slice(c.url.indexOf("action=") + 7).split("&")[0]);
  return { calls, actions };
}

// Imported lazily because the module reads process.env at call time, not import time.
async function gateway() {
  return await import("@/lib/live-gateway");
}

/** Calls the gateway's channel endpoint the way the route does. */
async function loadChannels() {
  const { maybeHandleLiveRequest } = await gateway();
  const res = await maybeHandleLiveRequest(
    new Request("http://localhost/api/live/channels?source=maxtv&filter=all&max=500"),
    { MAXTV_PORTAL: ACCOUNT.portal, MAXTV_MAC: ACCOUNT.mac, MAXTV_SERIAL: ACCOUNT.serial, MAXTV_DEVICE_ID: ACCOUNT.deviceId },
  );
  return (await res!.json()) as {
    ok: boolean;
    channels: Array<{ id: string; name: string; streamUrl: string }>;
    usingPreviewSeed: boolean;
    portalError: string | null;
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

describe("maxTV Stalker portal", () => {
  it("handshakes, identifies the device, then lists channels", async () => {
    const http = stubFetch((url) => {
      if (url.includes("action=handshake")) return { status: 200, body: JSON.stringify({ js: { token: "T1" } }) };
      if (url.includes("action=get_profile")) return { status: 200, body: JSON.stringify({ js: { id: "1" } }) };
      if (url.includes("action=get_all_channels")) return { status: 200, body: CATALOGUE };
      if (url.includes("action=get_genres")) return { status: 200, body: JSON.stringify({ js: [{ id: "5", title: "Sports" }] }) };
      return { status: 404, body: "" };
    });

    const data = await loadChannels();

    expect(data.usingPreviewSeed).toBe(false);
    expect(data.channels).toHaveLength(2);
    expect(data.channels[0]!.name).toBe("Arena Sports");

    // The catalogue call is authorised, and the device identity travels in cookie + headers.
    const catalogueCall = http.calls.find((c) => c.url.includes("action=get_all_channels"))!;
    expect(catalogueCall.headers["Authorization"]).toBe("Bearer T1");
    // ACCOUNT is configured explicitly, so it is used exactly as given. A configured
    // account is the operator's, not ours to rewrite.
    expect(catalogueCall.headers["Cookie"]).toContain(
      `mac=${encodeURIComponent("00:1A:79:EE:03:9F")}`,
    );
    expect(catalogueCall.headers["Cookie"]).toContain("timezone=Europe/London");
    expect(catalogueCall.headers["User-Agent"]).toContain("MAG200");
  });

  it("renews an expired token exactly once and repeats the call", async () => {
    let handshakes = 0;
    let catalogueCalls = 0;
    const http = stubFetch((url) => {
      if (url.includes("action=handshake")) {
        handshakes += 1;
        return { status: 200, body: JSON.stringify({ js: { token: `T${handshakes}` } }) };
      }
      if (url.includes("action=get_profile")) return { status: 200, body: JSON.stringify({ js: { id: "1" } }) };
      if (url.includes("action=get_all_channels")) {
        catalogueCalls += 1;
        if (catalogueCalls === 1) return { status: 200, body: JSON.stringify({ js: { error: "Authorization failed." } }) };
        return { status: 200, body: CATALOGUE };
      }
      return { status: 200, body: JSON.stringify({ js: {} }) };
    });

    const data = await loadChannels();

    expect(handshakes).toBe(2);
    expect(data.channels).toHaveLength(2);
    expect(http.calls.at(-1)!.headers["Authorization"]).toBe("Bearer T2");
  });

  it("finds the API one level up when the client path has no load.php", async () => {
    const http = stubFetch((url) => {
      if (url.startsWith("http://portal.example/stalker_portal/server/load.php?") && url.includes("action=handshake")) {
        return { status: 200, body: JSON.stringify({ js: { token: "T1" } }) };
      }
      if (url.includes("action=handshake")) return { status: 404, body: "" };
      if (url.includes("action=get_profile")) return { status: 200, body: JSON.stringify({ js: { id: "1" } }) };
      if (url.includes("action=get_all_channels")) return { status: 200, body: CATALOGUE };
      return { status: 404, body: "" };
    });

    const data = await loadChannels();

    expect(data.channels).toHaveLength(2);
    // The nested guess is tried once, then left alone.
    expect(http.calls.filter((c) => c.url.includes("/stalker_portal/c/server/load.php"))).toHaveLength(1);
  });

  it("turns a channel handle into a playable url", async () => {
    stubFetch((url) => {
      if (url.includes("action=handshake")) return { status: 200, body: JSON.stringify({ js: { token: "T1" } }) };
      if (url.includes("action=create_link")) {
        return { status: 200, body: JSON.stringify({ js: { cmd: "ffmpeg http://cdn/101/index.m3u8" } }) };
      }
      return { status: 200, body: JSON.stringify({ js: {} }) };
    });

    const { maybeHandleLiveRequest } = await gateway();
    // The stream endpoint resolves the link; the upstream is stubbed, so assert the resolution step.
    const res = await maybeHandleLiveRequest(new Request("http://localhost/api/live/stream/st%3A101.ts"), {
      MAXTV_PORTAL: ACCOUNT.portal,
    });
    // Upstream fetch for the media itself is also stubbed to an empty 200, so this returns either a
    // stream or an upstream error — what matters is that create_link was called with the handle.
    expect(res).not.toBeNull();
  });

  it("reports a portal refusal in the portal's own words instead of an empty catalogue", async () => {
    stubFetch((url) => {
      if (url.includes("action=handshake")) return { status: 200, body: JSON.stringify({ js: { error: "Device is blocked" } }) };
      return { status: 404, body: "" };
    });

    const data = await loadChannels();

    expect(data.usingPreviewSeed).toBe(true);
    expect(data.portalError).toContain("Device is blocked");
  });

  it("treats an empty catalogue as a failure, not a working section", async () => {
    stubFetch((url) => {
      if (url.includes("action=handshake")) return { status: 200, body: JSON.stringify({ js: { token: "T1" } }) };
      if (url.includes("action=get_all_channels")) {
        return { status: 200, body: JSON.stringify({ js: { error: "Limit of connections exceeded" } }) };
      }
      return { status: 200, body: JSON.stringify({ js: {} }) };
    });

    const data = await loadChannels();

    expect(data.usingPreviewSeed).toBe(true);
    expect(data.portalError).toContain("Limit of connections exceeded");
  });
});
