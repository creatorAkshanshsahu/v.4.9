/**
 * Browser port of the Android app's `StreamRequestPolicy` token rules (`v.4.9`
 * `app/src/main/java/com/aktv/app/StreamRequestPolicy.kt`).
 *
 * The app plays these exact TG/JJTV URLs + cookies while the website stalled, and the
 * difference is entirely in *how* the `__hdnea__` token is applied per request:
 *
 * 1. The app *appends* the token, preserving every other query parameter, and **never**
 *    overwrites a token already present on a segment/child URL (`withToken(replace=false)`
 *    returns the URL untouched). The website used to rebuild every URL as
 *    `base + "?" + coarseCookie`, stripping redirect-minted tokens and sibling params.
 * 2. The app *learns* renewed tokens from each successful manifest's final wire URL
 *    (`observed()`: Jio replaces authentication on redirects) and uses them for the
 *    following segments. The website pinned the coarse cookie for the whole session, so
 *    segments went out with a stale token and never produced frames.
 * 3. Tokens are never copied to a different host (`target == origin` check).
 *
 * Kept dependency-free so both the Cloudflare Worker bundle and the browser component
 * can import it without pulling anything else in.
 */

export const HDNEA_PARAM = "__hdnea__";

/** Full `__hdnea__=<value>` pair found in a URL or cookie string, or null. Raw, never re-encoded. */
export function extractHdneaParam(urlOrCookie: string | undefined | null): string | null {
  if (!urlOrCookie) return null;
  const m = /(?:^|[?;&\s])(__hdnea__=[^;\s&"'<>]+)/.exec(String(urlOrCookie));
  return m?.[1] ?? null;
}

/** True when the URL already carries its own `__hdnea__` query parameter. */
export function hasHdneaParam(url: string): boolean {
  return /(?:^|[?;&])__hdnea__=/.test(url);
}

/**
 * The app's `withToken()`: work on the raw query (re-encoding a signed URL can invalidate
 * its signature), keep every sibling parameter and the `#fragment`.
 *
 * - `replace=false` (segments/children): a URL that already carries a token is returned
 *   untouched; otherwise the token is appended.
 * - `replace=true` (the advertised root manifest): any existing token is swapped for the
 *   supplied one, sibling parameters preserved.
 */
export function withHdneaToken(url: string, tokenParam: string, replace: boolean): string {
  const hashIndex = url.indexOf("#");
  const fragment = hashIndex >= 0 ? url.slice(hashIndex) : "";
  const base = hashIndex >= 0 ? url.slice(0, hashIndex) : url;
  const qIndex = base.indexOf("?");
  const params = (qIndex >= 0 ? base.slice(qIndex + 1) : "").split("&").filter((p) => p.length > 0);
  if (!replace && params.some((p) => p.split("=", 1)[0] === HDNEA_PARAM)) return url;
  const kept = params.filter((p) => p.split("=", 1)[0] !== HDNEA_PARAM);
  kept.push(tokenParam);
  const path = qIndex >= 0 ? base.slice(0, qIndex) : base;
  return `${path}?${kept.join("&")}${fragment}`;
}

/** Hostname equality for the app's `target == origin` token rule; unparseable URLs never match. */
export function sameUrlHost(a: string, b: string): boolean {
  try {
    return new URL(a).hostname.toLowerCase() === new URL(b).hostname.toLowerCase();
  } catch {
    return false;
  }
}

/** True for an HLS playlist URL (manifest/level/audio), as opposed to a media fragment. */
export function isHlsPlaylistUrl(url: string): boolean {
  return /\.m3u8(?:[?#]|$)/i.test(url);
}
