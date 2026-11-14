import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import {
  StalkerClient,
  keepsIndianHindi,
  rewriteHlsManifest,
  verifyProxyToken,
  createGatewayServer
} from "./server.mjs";

test("StalkerClient performs handshake, refreshes expired token, and resolves create_link", async () => {
  let handshakes = 0;
  let linkCalls = 0;
  const fakeTransport = async (url, headers) => {
    const u = new URL(url);
    const action = u.searchParams.get("action");
    assert.ok(headers.Cookie.includes("mac="));
    assert.ok(headers["User-Agent"].includes("MAG200"));
    if (action === "handshake") {
      handshakes += 1;
      return JSON.stringify({ js: { token: `tok_${handshakes}` } });
    }
    if (action === "get_profile") {
      return JSON.stringify({ js: { id: 1 } });
    }
    if (action === "get_genres") {
      return JSON.stringify({
        js: [
          { id: "*", title: "All" },
          { id: "10", title: "INDIA | ENTERTAINMENT" },
          { id: "20", title: "UK | ENTERTAINMENT" }
        ]
      });
    }
    if (action === "get_all_channels") {
      return JSON.stringify({
        js: {
          data: [
            { id: "501", name: "Star Plus HD", cmd: "ffrt http://localhost/ch/501", tv_genre_id: "10", logo: "/logos/sp.png" },
            { id: "502", name: "Sky Showcase", cmd: "ffrt http://localhost/ch/502", tv_genre_id: "20", logo: "" }
          ]
        }
      });
    }
    if (action === "create_link") {
      linkCalls += 1;
      if (linkCalls === 1) {
        return JSON.stringify({ js: { not_valid: 1 } });
      }
      return JSON.stringify({
        js: { cmd: "ffmpeg http://edge.example.com:8080/play/live.php?stream=501&extension=ts|User-Agent=MAG" }
      });
    }
    throw new Error(`Unexpected action: ${action}`);
  };

  const client = new StalkerClient(
    {
      maxtvPortal: "http://new.maxtv.be/c",
      maxtvMac: "00:1A:79:EE:03:9F",
      maxtvSerial: "SN1",
      maxtvDeviceId: "DEV1"
    },
    fakeTransport
  );

  const channels = await client.fetchCatalogue();
  assert.equal(channels.length, 2);
  assert.equal(channels[0].id, "st:501");
  assert.equal(channels[0].category, "INDIA | ENTERTAINMENT");
  assert.equal(channels[0].logo, "http://new.maxtv.be/logos/sp.png");

  const resolved = await client.resolveLink(channels[0].cmd);
  assert.equal(resolved, "http://edge.example.com:8080/play/live.php?stream=501&extension=ts");
  assert.equal(handshakes, 2, "must re-handshake automatically after not_valid=1");
});

test("keepsIndianHindi keeps Indian Hindi + Cricket and excludes foreign & non-Hindi regional", () => {
  assert.equal(keepsIndianHindi("Star Plus HD", "INDIA | ENTERTAINMENT"), true);
  assert.equal(keepsIndianHindi("Star Sports 1 Hindi HD", "SPORTS | CRICKET"), true);
  assert.equal(keepsIndianHindi("Aaj Tak HD", "INDIA | NEWS"), true);
  assert.equal(keepsIndianHindi("Star Vijay HD", "INDIA | TAMIL"), false);
  assert.equal(keepsIndianHindi("Sky Sports Main Event", "UK | SPORTS"), false);
  assert.equal(keepsIndianHindi("Midnight Club", "FOR Adults"), false);
});

test("rewriteHlsManifest signs segment and key URLs through /api/segment", () => {
  const secret = "unit-test-secret";
  const raw = [
    "#EXTM3U",
    '#EXT-X-KEY:METHOD=AES-128,URI="keys/k1.key"',
    "#EXTINF:4.0,",
    "seg001.ts?tok=abc"
  ].join("\n");

  const out = rewriteHlsManifest(raw, "http://edge.example.com:8080/live/501/index.m3u8", "mag", "https://og.example", secret);
  const lines = out.split("\n");
  assert.ok(lines[1].includes("https://og.example/api/segment?t="));
  assert.ok(lines[3].startsWith("https://og.example/api/segment?t="));

  const tok = decodeURIComponent(lines[3].split("?t=")[1]);
  const verified = verifyProxyToken(tok, secret);
  assert.equal(verified.u, "http://edge.example.com:8080/live/501/seg001.ts?tok=abc");
  assert.equal(verified.m, "mag");
});

test("HTTP Gateway serves /api/channels, /api/playlist.m3u, /embed/:id, and streams MPEG-TS via /api/stream/:id", async () => {
  // Start a local mock MPEG-TS edge server sending 0x47 sync bytes
  const tsPayload = Buffer.alloc(376, 0xaa);
  tsPayload[0] = 0x47;
  tsPayload[188] = 0x47;

  const mockEdge = http.createServer((req, res) => {
    res.writeHead(200, { "Content-Type": "video/mp2t" });
    res.end(tsPayload);
  });
  await new Promise((r) => mockEdge.listen(0, "127.0.0.1", r));
  const edgePort = mockEdge.address().port;

  const fakeStalker = {
    async fetchCatalogue() {
      return [
        {
          id: "st:777",
          rawId: "777",
          source: "maxtv",
          name: "Star Sports 1 Hindi HD",
          category: "SPORTS | CRICKET",
          logo: "http://new.maxtv.be/logo.png",
          cmd: "ffrt http://localhost/ch/777"
        },
        {
          id: "st:888",
          rawId: "888",
          source: "maxtv",
          name: "Sky Cinema UK",
          category: "UK | MOVIES",
          logo: "",
          cmd: "ffrt http://localhost/ch/888"
        }
      ];
    },
    async resolveLink() {
      return `http://127.0.0.1:${edgePort}/live/777.ts`;
    }
  };

  const gateway = createGatewayServer({
    stalkerClient: fakeStalker,
    xtreamClient: { configured: false, async fetchCatalogue() { return []; } }
  });
  await new Promise((r) => gateway.listen(0, "127.0.0.1", r));
  const gwPort = gateway.address().port;
  const base = `http://127.0.0.1:${gwPort}`;

  try {
    // 1. /api/channels (default filter=hindi keeps Star Sports 1 Hindi HD and drops Sky Cinema UK)
    const chRes = await fetch(`${base}/api/channels?source=maxtv&filter=hindi`);
    assert.equal(chRes.status, 200);
    assert.equal(chRes.headers.get("access-control-allow-origin"), "*");
    const chJson = await chRes.json();
    assert.equal(chJson.channels.length, 1);
    assert.equal(chJson.channels[0].id, "st:777");
    assert.ok(chJson.channels[0].iframeCode.includes("/embed/st%3A777"));

    // 2. /api/playlist.m3u
    const m3uRes = await fetch(`${base}/api/playlist.m3u?source=maxtv&filter=hindi`);
    const m3uText = await m3uRes.text();
    assert.ok(m3uText.startsWith("#EXTM3U"));
    assert.ok(m3uText.includes("Star Sports 1 Hindi HD"));
    assert.ok(m3uText.includes("/api/stream/st%3A777.ts"));

    // 3. /embed/st:777
    const embedRes = await fetch(`${base}/embed/st%3A777`);
    assert.equal(embedRes.status, 200);
    assert.ok(embedRes.headers.get("content-security-policy")?.includes("frame-ancestors *"));
    const embedHtml = await embedRes.text();
    assert.ok(embedHtml.includes("Star Sports 1 Hindi HD"));

    // 4. /api/stream/st:777.ts proxies live MPEG-TS bytes
    const streamRes = await fetch(`${base}/api/stream/st%3A777.ts`);
    assert.equal(streamRes.status, 200);
    assert.equal(streamRes.headers.get("x-stream-container"), "mpegts");
    const buf = Buffer.from(await streamRes.arrayBuffer());
    assert.equal(buf[0], 0x47);
    assert.equal(buf.length, tsPayload.length);
  } finally {
    gateway.close();
    mockEdge.close();
  }
});
