import { describe, expect, it } from "vitest";
import { maybeHandleLiveRequest } from "@/lib/live-gateway";

describe("Live TV & OG Embed Gateway", () => {
  it("returns CORS-enabled channel JSON on /api/live/channels", async () => {
    const res = await maybeHandleLiveRequest(
      new Request("https://streamr.lovable.app/api/live/channels?source=maxtv&filter=hindi"),
    );
    expect(res).not.toBeNull();
    expect(res!.status).toBe(200);
    expect(res!.headers.get("access-control-allow-origin")).toBe("*");
    const json = (await res!.json()) as { ok: boolean; channels: Array<{ id: string; iframeCode: string }> };
    expect(json.ok).toBe(true);
    expect(json.channels.length).toBeGreaterThan(0);
    expect(json.channels[0]!.iframeCode).toContain("https://streamr.lovable.app/embed/");
  });

  it("generates dynamic M3U playlist on /api/live/playlist.m3u", async () => {
    const res = await maybeHandleLiveRequest(
      new Request("https://streamr.lovable.app/api/live/playlist.m3u?source=maxtv&filter=hindi"),
    );
    expect(res).not.toBeNull();
    const text = await res!.text();
    expect(text.startsWith("#EXTM3U")).toBe(true);
    expect(text).toContain("https://streamr.lovable.app/api/live/stream/");
  });

  it("serves standalone iframe player HTML on /embed/:id without X-Frame-Options block", async () => {
    const res = await maybeHandleLiveRequest(new Request("https://streamr.lovable.app/embed/maxtv_seed-ss1h"));
    expect(res).not.toBeNull();
    expect(res!.status).toBe(200);
    expect(res!.headers.get("content-security-policy")).toContain("frame-ancestors *");
    const html = await res!.text();
    expect(html).toContain("/vendor/mpegts.js");
  });
});
