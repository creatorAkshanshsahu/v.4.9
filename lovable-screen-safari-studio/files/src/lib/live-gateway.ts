/**
 * Web-Standards Live TV Gateway (`maxTV` Stalker + optional `StarShare` Xtream) for TanStack Start / Cloudflare Workers.
 *
 * Uses only Web-standard `fetch`, `ReadableStream`, `TextEncoder`/`TextDecoder`, and `crypto.subtle`
 * (zero `node:*` imports) so `vite build` with Lovable's Nitro Cloudflare target succeeds cleanly.
 */

export interface LiveChannelItem {
  id: string;
  rawId: string;
  source: "maxtv" | "starshare" | "jio" | "tgtv";
  name: string;
  category: string;
  logo: string;
  cmd?: string;
  /** ClearKey material for DRM-protected playlist channels. */
  drm?: { type: "clearkey"; keys: ClearKeyPair[]; licenseUrl?: string };
  /** Transport hints the playlist itself declares. */
  userAgent?: string;
  cookie?: string;
  mediaType?: "hls" | "dash" | "mp4";
}

export interface DirectPlayCandidate {
  label: string;
  url: string;
  cookie: string;
  clearKeys: Record<string, string>;
  container: "dash" | "hls";
}

export interface LiveChannelDto {
  id: string;
  source: "maxtv" | "starshare" | "jio" | "tgtv";
  name: string;
  category: string;
  rawCategory: string;
  logo: string;
  embedUrl: string;
  iframeCode: string;
  streamUrl: string;
  /** Optional direct HTTPS stream URL for browser-side fallback if proxy ASN is geo-blocked. */
  directUrl?: string;
  /** Present only for DRM channels; the page hands these straight to Shaka. */
  drm?: { type: "clearkey"; keys: Array<{ kid: string; k: string }> };
  container?: "hls" | "dash" | "mp4";
}

const MAG_USER_AGENT =
  "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2110 Safari/533.3";
const MAG_DEVICE_UA = "Model: MAG254; Link: WiFi";
const XTREAM_USER_AGENT = "IPTVSmartersPlayer";
const CATALOGUE_TTL_MS = 15 * 60 * 1000;

const PREVIEW_SEED_CHANNELS: LiveChannelItem[] = [
  { id: "st:1001", rawId: "1001", source: "maxtv", name: "Star Sports 1 Hindi HD", category: "SPORTS | CRICKET", logo: "", cmd: "seed://1001" },
  { id: "st:1002", rawId: "1002", source: "maxtv", name: "Star Sports 2 HD", category: "SPORTS | CRICKET", logo: "", cmd: "seed://1002" },
  { id: "st:1003", rawId: "1003", source: "maxtv", name: "Sports18 1 HD", category: "SPORTS | INDIA", logo: "", cmd: "seed://1003" },
  { id: "st:1004", rawId: "1004", source: "maxtv", name: "Sony Sports Ten 1 HD", category: "SPORTS | CRICKET", logo: "", cmd: "seed://1004" },
  { id: "st:1005", rawId: "1005", source: "maxtv", name: "Sony Sports Ten 3 Hindi HD", category: "SPORTS | CRICKET", logo: "", cmd: "seed://1005" },
  { id: "st:1006", rawId: "1006", source: "maxtv", name: "Willow Cricket HD", category: "SPORTS | CRICKET", logo: "", cmd: "seed://1006" },
  { id: "st:1010", rawId: "1010", source: "maxtv", name: "Star Plus HD", category: "INDIA | ENTERTAINMENT", logo: "", cmd: "seed://1010" },
  { id: "st:1011", rawId: "1011", source: "maxtv", name: "Sony SET HD", category: "INDIA | ENTERTAINMENT", logo: "", cmd: "seed://1011" },
  { id: "st:1012", rawId: "1012", source: "maxtv", name: "Sony SAB HD", category: "INDIA | ENTERTAINMENT", logo: "", cmd: "seed://1012" },
  { id: "st:1013", rawId: "1013", source: "maxtv", name: "Colors HD", category: "INDIA | ENTERTAINMENT", logo: "", cmd: "seed://1013" },
  { id: "st:1014", rawId: "1014", source: "maxtv", name: "Zee TV HD", category: "INDIA | ENTERTAINMENT", logo: "", cmd: "seed://1014" },
  { id: "st:1020", rawId: "1020", source: "maxtv", name: "Sony Max HD", category: "INDIA | HINDI MOVIES", logo: "", cmd: "seed://1020" },
  { id: "st:1021", rawId: "1021", source: "maxtv", name: "Star Gold HD", category: "INDIA | HINDI MOVIES", logo: "", cmd: "seed://1021" },
  { id: "st:1022", rawId: "1022", source: "maxtv", name: "Zee Cinema HD", category: "INDIA | HINDI MOVIES", logo: "", cmd: "seed://1022" },
  { id: "st:1023", rawId: "1023", source: "maxtv", name: "Colors Cineplex HD", category: "INDIA | HINDI MOVIES", logo: "", cmd: "seed://1023" },
  { id: "st:1030", rawId: "1030", source: "maxtv", name: "Aaj Tak HD", category: "INDIA | NEWS", logo: "", cmd: "seed://1030" },
  { id: "st:1031", rawId: "1031", source: "maxtv", name: "NDTV India", category: "INDIA | NEWS", logo: "", cmd: "seed://1031" },
  { id: "st:1032", rawId: "1032", source: "maxtv", name: "India TV HD", category: "INDIA | NEWS", logo: "", cmd: "seed://1032" },
  { id: "st:1040", rawId: "1040", source: "maxtv", name: "Discovery Channel India HD", category: "INDIA | DOCUMENTARY", logo: "", cmd: "seed://1040" },
  { id: "st:1041", rawId: "1041", source: "maxtv", name: "National Geographic HD India", category: "INDIA | DOCUMENTARY", logo: "", cmd: "seed://1041" },
  { id: "st:1050", rawId: "1050", source: "maxtv", name: "Cartoon Network India", category: "INDIA | KIDS", logo: "", cmd: "seed://1050" },
  { id: "st:1051", rawId: "1051", source: "maxtv", name: "Hungama TV", category: "INDIA | KIDS", logo: "", cmd: "seed://1051" },
  { id: "st:1060", rawId: "1060", source: "maxtv", name: "9XM", category: "INDIA | MUSIC", logo: "", cmd: "seed://1060" },
  { id: "st:1061", rawId: "1061", source: "maxtv", name: "MTV Beats HD", category: "INDIA | MUSIC", logo: "", cmd: "seed://1061" },
];

type GatewayConfig = {
  maxtvPortal: string;
  maxtvMac: string;
  /** True when the value came from configuration rather than from our defaults. */
  maxtvMacExplicit: boolean;
  maxtvPortalExplicit: boolean;
  maxtvSerial: string;
  maxtvDeviceId: string;
  starshareServer: string;
  starshareFallback: string;
  starshareUser: string;
  starsharePass: string;
  jioPlaylistUrl: string;
  tgtvPlaylistUrl: string;
  proxySecret: string;
};

/**
 * Nitro's `cloudflare-module` preset invokes the SSR entry with a single `(request)` argument while
 * attaching Cloudflare Worker bindings to `globalThis.__env__` and `request.runtime.cloudflare.env`.
 * Reading only the `env` argument silently dropped every configured secret in production.
 */
function resolveEnvSources(env: unknown, request?: Request): Array<Record<string, unknown>> {
  const sources: Array<Record<string, unknown>> = [];
  if (env && typeof env === "object") sources.push(env as Record<string, unknown>);
  const cfEnv = (request as { runtime?: { cloudflare?: { env?: unknown } } } | undefined)?.runtime?.cloudflare?.env;
  if (cfEnv && typeof cfEnv === "object") sources.push(cfEnv as Record<string, unknown>);
  const globalEnv = (globalThis as { __env__?: unknown }).__env__;
  if (globalEnv && typeof globalEnv === "object") sources.push(globalEnv as Record<string, unknown>);
  const procEnv =
    typeof globalThis !== "undefined" && "process" in globalThis
      ? (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env
      : undefined;
  if (procEnv && typeof procEnv === "object") sources.push(procEnv as Record<string, unknown>);
  return sources;
}

function readEnv(env: unknown, key: string, fallback = "", request?: Request): string {
  for (const src of resolveEnvSources(env, request)) {
    const val = src[key];
    if (typeof val === "string" && val.trim()) return val.trim();
  }
  return fallback;
}

function getConfig(env: unknown, request?: Request): GatewayConfig {
  const explicit = (key: string): boolean =>
    resolveEnvSources(env, request).some((src) => typeof src[key] === "string" && (src[key] as string).trim() !== "");

  return {
    maxtvPortal: readEnv(env, "MAXTV_PORTAL", STALKER_DEFAULT_PORTAL, request),
    maxtvMac: readEnv(env, "MAXTV_MAC", STALKER_DEFAULT_MAC, request),
    maxtvMacExplicit: explicit("MAXTV_MAC"),
    maxtvPortalExplicit: explicit("MAXTV_PORTAL"),
    maxtvSerial: readEnv(env, "MAXTV_SERIAL", "92180365D21FE", request),
    maxtvDeviceId: readEnv(
      env,
      "MAXTV_DEVICE_ID",
      "3610DEAFB556781F84CBC8A6AF876A8F20637668862B28DEECFACE00D67A6070",
      request,
    ),
    starshareServer: readEnv(env, "STARSHARE_SERVER", "http://livebox.pro:8080", request),
    starshareFallback: readEnv(env, "STARSHARE_FALLBACK", "http://starshare.fans:8080", request),
    starshareUser: readEnv(env, "STARSHARE_USER", "", request),
    starsharePass: readEnv(env, "STARSHARE_PASS", "", request),
    jioPlaylistUrl: readEnv(env, "JIO_PLAYLIST_URL", "https://allinonereborn2.online/jiotv-m3u/jtv39.m3u8", request),
    tgtvPlaylistUrl: readEnv(env, "TGTV_PLAYLIST_URL", "https://livetgtv.lovable.app/api/public/playlist.m3u", request),
    proxySecret: readEnv(env, "LIVE_PROXY_SECRET", "streamr-maxtv-live-gateway-v1", request),
  };
}

// ---------- Indian Hindi + Sports/Cricket + Dual-Audio Filter ----------

const ADULT_RE = /\b(?:XXX|ADULT|ADULTS|FOR\s+ADULTS|18\+|PORN|PLAYBOY|BRAZZERS|EROTIC|ONLYFANS)\b/i;
const HINDI_EXPLICIT_RE = /\b(?:HINDI|BOLLYWOOD|HIN\s*DUB|HINDI\s*DUB(?:BED)?|DUAL\s*AUDIO|DESI|BIGG\s*BOSS)\b/i;
const INDIA_TAG_RE =
  /(?:^|[|\[(\{\s:\-])(?:IN|IND|INDIA|INDIAN|BHARAT|HINDUSTAN|TATA\s*PLAY|TATA\s*SKY|JIO(?:\s*TV|\s*CINEMA|\s*HOTSTAR)?|AIRTEL|DISH\s*TV|D2H|DOORDARSHAN|HOTSTAR|ZEE5|SONYLIV|SONY\s*LIV|MX\s*PLAYER|TVF)(?:$|[|\])\}\s:\-])/i;
const CRICKET_RE =
  /\b(?:CRICKET|IPL|BCCI|ICC|WPL|ASIA\s*CUP|WILLOW|ASTRO\s*CRICKET|STAR\s*SPORTS|SPORTS\s*18|SPORTS18|SONY\s*(?:SPORTS|TEN)|TEN\s*SPORTS|DD\s*SPORTS|FANCODE|FAN\s*CODE)\b/i;
const INDIAN_SPORTS_RE = /\b(?:PRO\s*KABADDI|ISL|INDIAN\s*SUPER\s*LEAGUE|EUROSPORT\s*INDIA)\b/i;
const REGIONAL_NON_HINDI_RE =
  /\b(?:TAMIL|TAMIZH|TELUGU|TELGU|MALAYALAM|MALYALAM|KERALAM|KANNADA|MARATHI|BENGALI|BANGLA|PUNJABI|GUJARATI|GUJRATI|ODIA|ORIYA|ASSAMESE|ASSAM|BHOJPURI|URDU|NEPALI|SINHALA|SUN\s*TV|SUN\s*MUSIC|KTV|ADITHYA|UDAYA|SURYA\s*TV|GEMINI|STAR\s*(?:MAA|VIJAY|JALSHA|SUVARNA|PRAVAH|KIRAN)|ASIANET|MAZHAVIL|KAIRALI|MANORAMA|KALAIGNAR|POLIMER|THANTHI|JAYA\s*TV|ZEE\s*(?:TAMIL|TELUGU|KANNADA|KERALAM|MARATHI|BANGLA|PUNJABI|SARTHAK|TALKIES|PICCHAR)|COLORS\s*(?:TAMIL|KANNADA|MARATHI|BANGLA|GUJARATI|ODIA)|SONY\s*(?:MARATHI|AATH)|PTC\s*PUNJABI)\b/i;
const FOREIGN_STRICT_RE =
  /(?:^|[|\[(\{\s:\-])(?:US|USA|UK|UNITED\s*KINGDOM|CA|CANADA|AU|AUSTRALIA|NZ|PK|PAK|PAKISTAN|BD|BANGLADESH|AF|AFGHANISTAN|AR|ARA|ARAB|ARABIC|AE|UAE|SAUDI|QATAR|OSN|MBC|TR|TURKEY|IR|IRAN|FR|FRANCE|DE|GERMANY|ES|SPAIN|LATAM|PT|PORTUGAL|BR|BRAZIL|IT|ITALY|NL|NETHERLANDS|PL|POLAND|RU|RUSSIA|RO|ROMANIA|GR|GREECE|AL|ALBANIA|EX[-\s]*YU|AFRICA|DSTV|PH|MY|SG|ID|TH|VN|CN|HK|TW|JP|KR|CARIBBEAN|NFL|NBA|NHL|MLB)(?:$|[|\])\}\s:\-])/i;
const FOREIGN_CHANNEL_PREFIX_RE =
  /^(?:US|USA|UK|CA|AU|NZ|PK|PAK|BD|AF|AR|AE|TR|IR|FR|DE|ES|PT|BR|IT|NL|PL|RU|RO|GR|AL|ZA|PH|MY|ID|TH|VN|CN|HK|JP|KR)\b\s*[:|\-]/i;
const INDIAN_BROADCASTER_RE =
  /\b(?:STAR\s*(?:PLUS|BHARAT|UTSAV|GOLD|MOVIES|WORLD)|SONY\s*(?:SET|SAB|MAX|PIX|PAL|WAH|YAY|BBC\s*EARTH)|SET\s*(?:INDIA|HD)|SAB\s*(?:TV|HD)|ZEE\s*(?:TV|CINEMA|BOLLYWOOD|ACTION|CLASSIC|ANMOL|CAFE|ZEST|NEWS|BUSINESS|BHARAT)|COLORS(?:\s*(?:HD|SD|TV|INFINITY|CINEPLEX|RISHTEY))?|&(?:\s*TV|\s*PICTURES|\s*FLICKS|\s*PRIVE)|AND\s*(?:TV|PICTURES|FLICKS)|DANGAL|SHEMAROO|GOLDMINES|B4U\s*(?:MOVIES|MUSIC|KADAK)|UTV\s*(?:MOVIES|ACTION|BINDASS)|BINDASS|EPIC\s*TV|AAJ\s*TAK|INDIA\s*TODAY|NDTV|INDIA\s*TV|ABP\s*NEWS|NEWS\s*18\s*INDIA|NEWS\s*24|REPUBLIC\s*(?:TV|BHARAT)|TIMES\s*NOW|MIRROR\s*NOW|CNBC\s*(?:AWAAZ|TV18)|WION|DD\s*(?:NATIONAL|NEWS|INDIA|BHARATI|KISAN|SPORTS)|SANSAD\s*TV|TV9\s*BHARATVARSH|DISCOVERY|ANIMAL\s*PLANET|TLC|HISTORY\s*TV\s*18|NAT\s*GEO|NATIONAL\s*GEOGRAPHIC|TRAVELXP|HUNGAMA|POGO|CARTOON\s*NETWORK|NICKELODEON|NICK|SONIC\s*NICKELODEON|DISNEY|9XM|9X\s*JALWA|MASTIII|ZOOM\s*TV|MTV\s*(?:BEATS|INDIA)|SHOWBOX|E24|ZING|AASTHA|SANSKAR|SADHNA)\b/i;

export function displayCategoryName(category = ""): string {
  const stripped = String(category)
    .trim()
    .replace(/^[|\[(\{\s]*(?:IN|IND|INDIA|INDIAN|VIP)[|\])\}\s:\-–—]+/i, "")
    .replace(/^[|:\-–—\s]+|[|:\-–—\s]+$/g, "")
    .replace(/\s{2,}/g, " ")
    .trim();
  return stripped || String(category).trim() || "Live TV";
}

export function categoryRank(category = ""): number {
  const lower = displayCategoryName(category).toLowerCase();
  const isIndia = INDIA_TAG_RE.test(category) || lower.includes("hindi") || lower.includes("bollywood");
  let base = 9;
  if (/cricket|ipl|sport/.test(lower)) base = 0;
  else if (/entertainment|gec|general|serial/.test(lower)) base = 1;
  else if (/movie|cinema|bollywood|film|dual audio/.test(lower)) base = 2;
  else if (/news|business/.test(lower)) base = 3;
  else if (/knowledge|infotainment|documentary|discovery|science/.test(lower)) base = 4;
  else if (/kid|cartoon|anime/.test(lower)) base = 5;
  else if (/music|youth/.test(lower)) base = 6;
  else if (/spiritual|devotional|religious|bhakti/.test(lower)) base = 7;
  return isIndia ? base : base + 12;
}

export function keepsIndianHindi(name = "", category = ""): boolean {
  const combined = `${category} ${name}`;
  if (ADULT_RE.test(combined)) return false;
  const explicitHindi = HINDI_EXPLICIT_RE.test(combined);
  if (!explicitHindi && REGIONAL_NON_HINDI_RE.test(combined)) return false;
  const isCricket = CRICKET_RE.test(combined);
  if (!explicitHindi && !INDIA_TAG_RE.test(category) && !isCricket) {
    if (FOREIGN_STRICT_RE.test(category) || FOREIGN_STRICT_RE.test(name)) return false;
  }
  if (!explicitHindi && !isCricket && FOREIGN_CHANNEL_PREFIX_RE.test(String(name).trim())) return false;
  return (
    explicitHindi ||
    INDIA_TAG_RE.test(combined) ||
    isCricket ||
    INDIAN_SPORTS_RE.test(combined) ||
    INDIAN_BROADCASTER_RE.test(name)
  );
}

export function keepsByFilter(name = "", category = "", filter = "hindi"): boolean {
  const f = String(filter || "hindi").toLowerCase();
  if (f === "all") return !ADULT_RE.test(`${category} ${name}`);
  if (f === "en_hi" || f === "english") {
    if (ADULT_RE.test(`${category} ${name}`)) return false;
    if (REGIONAL_NON_HINDI_RE.test(`${category} ${name}`) && !HINDI_EXPLICIT_RE.test(`${category} ${name}`)) return false;
    return !FOREIGN_CHANNEL_PREFIX_RE.test(String(name).trim());
  }
  return keepsIndianHindi(name, category);
}

// ---------- Stalker portal client (maxTV) ----------
//
// Ported from the working Android implementation in creatorAkshanshsahu/v.4.9
// (app/src/main/java/com/aktv/app/StalkerPortal.kt + StalkerProtocol.kt).
// The sequence a MAG set-top box performs is reproduced exactly:
//   handshake -> get_profile -> get_genres/get_all_channels -> create_link
// The address the portal's `server/load.php` actually lives at is discovered,
// not guessed, because the configured web-client path is usually not the API.

/** One channel exactly as the portal advertises it. `cmd` is the handle traded for a stream URL. */
interface StalkerChannel {
  id: string;
  name: string;
  cmd: string;
  genre: string;
  logo: string;
}

/** The device identity a portal recognises. Defaults are the working set from the Android app. */
interface StalkerAccount {
  portal: string;
  mac: string;
  serial: string;
  deviceId: string;
}

// Working Android STB subscription identity bound to serial 92180365D21FE / deviceId 3610DEAFB5...
const STALKER_DEFAULT_PORTAL = "http://new.maxtv.be/stalker_portal/c/";
const STALKER_DEFAULT_MAC = "00:1A:79:EE:03:9F";

/** Alternate web-embed identity; kept as a secondary fallback if the primary account is refused. */
const STALKER_LEGACY_PORTAL = "http://new.maxtv.be/c/";
const STALKER_LEGACY_MAC = "00:1A:79:BA:DF:D7";
const STALKER_DEFAULT_SERIAL = "92180365D21FE";
const STALKER_DEFAULT_DEVICE = "3610DEAFB556781F84CBC8A6AF876A8F20637668862B28DEECFACE00D67A6070";

/** `server/load.php` — the API every panel action is posted to. */
const STALKER_API = "server/load.php";

function stalkerOrigin(url: string): string {
  try {
    const u = new URL(url.trim());
    return `${u.protocol}//${u.host}`;
  } catch {
    return url.trim().replace(/\/+$/, "");
  }
}

/**
 * Every address a portal's API may live at, in the order the Android app tries them.
 *
 * The configured value is the *web client* path (`http://host/stalker_portal/c/`) and its
 * `server/load.php` is normally one level up, at `http://host/stalker_portal/server/load.php`.
 * Asking only for `<configured>/server/load.php` is what produces a 404 on most panels.
 */
function stalkerCandidates(portalUrl: string): string[] {
  const configured = (portalUrl || STALKER_DEFAULT_PORTAL).trim().replace(/\/+$/, "");
  const origin = stalkerOrigin(configured);
  const out = new Set<string>();
  const add = (url: string) => {
    if (url.startsWith("http")) out.add(url);
  };

  // Exactly as configured.
  add(`${configured}/${STALKER_API}`);
  // The web-client path is not the API: its load.php sits one level up.
  if (configured.endsWith("/c")) add(`${configured.slice(0, -2)}/${STALKER_API}`);
  add(`${configured}/portal.php`);
  // The same files on the bare host.
  add(`${origin}/stalker_portal/${STALKER_API}`);
  add(`${origin}/${STALKER_API}`);
  add(`${origin}/portal.php`);
  add(`${origin}/stalker_portal/portal.php`);
  // A panel that only answers over TLS.
  if (configured.startsWith("http://")) {
    const secure = `https://${configured.slice("http://".length)}`;
    add(`${secure}/${STALKER_API}`);
    if (secure.endsWith("/c")) add(`${secure.slice(0, -2)}/${STALKER_API}`);
  }
  return [...out];
}

/** A portal answered, but not with something usable. Carries the portal's own refusal when it gave one. */
class StalkerError extends Error {
  readonly status: number | null;
  readonly detail: string;
  constructor(status: number | null = null, detail = "") {
    super(status !== null ? `The portal refused the request (HTTP ${status})` : detail || "The portal did not answer with a usable response");
    this.name = "StalkerError";
    this.status = status;
    this.detail = detail;
  }
}

function parseEnvelope(body: string): Record<string, unknown> | unknown[] | null {
  const text = String(body || "").trim().replace(/^\/\*\*\//, "").trim();
  const brace = text.indexOf("{");
  const bracket = text.indexOf("[");
  const starts = [brace, bracket].filter((i) => i >= 0);
  if (!starts.length) return null;
  try {
    const at = Math.min(...starts);
    if (!Number.isFinite(at)) return null;
    return JSON.parse(text.slice(at)) as Record<string, unknown> | unknown[];
  } catch {
    return null;
  }
}

/** The `js` envelope, or the root object when the portal did not wrap its answer. */
function stalkerJs(body: string): Record<string, unknown> | null {
  const root = parseEnvelope(body);
  if (!root || Array.isArray(root)) return null;
  const js = root["js"];
  return js && typeof js === "object" && !Array.isArray(js) ? (js as Record<string, unknown>) : root;
}

/** The session token a handshake issues. */
function stalkerToken(body: string): string | null {
  const js = stalkerJs(body);
  const tok = js?.["token"];
  return typeof tok === "string" && tok.trim() ? tok.trim() : null;
}

/** The portal's own refusal text, when it sent one. */
function stalkerError(body: string): string | null {
  const js = stalkerJs(body);
  const root = parseEnvelope(body);
  const rootObj = root && !Array.isArray(root) ? root : null;
  const text =
    (typeof js?.["error"] === "string" && js["error"]) ||
    (typeof js?.["msg"] === "string" && js["msg"]) ||
    (typeof rootObj?.["error"] === "string" && rootObj["error"]) ||
    "";
  return String(text).trim() || null;
}

/** True when the portal says the token is gone, so the caller has to handshake again. */
function isStalkerExpired(body: string): boolean {
  const root = parseEnvelope(body);
  if (!root || Array.isArray(root)) return false;
  const js = (root["js"] && typeof root["js"] === "object" ? root["js"] : root) as Record<string, unknown>;
  const flag = js["not_valid"];
  if (flag === true) return true;
  if (typeof flag === "number" && flag !== 0) return true;
  const msg = `${String(js["error"] ?? "")} ${String(js["msg"] ?? "")} ${String(root["error"] ?? "")}`.toLowerCase();
  return msg.includes("authorization failed") || msg.includes("not valid") || (msg.includes("token") && msg.includes("expire"));
}

/** `get_all_channels`: the `js.data` array of `{id, name, cmd, tv_genre_id, logo}`. */
function stalkerChannels(body: string): StalkerChannel[] {
  const js = stalkerJs(body);
  const data = js?.["data"];
  if (!Array.isArray(data)) return [];
  const out: StalkerChannel[] = [];
  for (const item of data) {
    if (!item || typeof item !== "object") continue;
    const rec = item as Record<string, unknown>;
    const cmd = String(rec["cmd"] ?? "").trim();
    const name = String(rec["name"] ?? rec["title"] ?? "").trim();
    const id = String(rec["id"] ?? rec["ch_id"] ?? "").trim();
    // A row without a name or without a handle cannot be shown or played.
    if (!cmd || !name) continue;
    out.push({
      id: id || name,
      name,
      cmd,
      genre: String(rec["tv_genre_id"] ?? "").trim(),
      logo: String(rec["logo"] ?? "").trim(),
    });
  }
  return out;
}

/** `get_genres`: `[{id, title}]`, or the same array under `js`. */
function stalkerGenres(body: string): Map<string, string> {
  const root = parseEnvelope(body);
  let array: unknown[] | null = null;
  if (Array.isArray(root)) array = root;
  else if (root && typeof root === "object") {
    const jsRaw = root["js"];
    if (jsRaw && typeof jsRaw === "object" && !Array.isArray(jsRaw)) {
      const nested = (jsRaw as Record<string, unknown>)["data"];
      if (Array.isArray(nested)) array = nested;
    } else if (Array.isArray(jsRaw)) array = jsRaw;
    if (!array) {
      const data = root["data"];
      if (Array.isArray(data)) array = data;
    }
  }
  const out = new Map<string, string>();
  if (!array) return out;
  for (const item of array) {
    if (!item || typeof item !== "object") continue;
    const rec = item as Record<string, unknown>;
    const id = String(rec["id"] ?? "").trim();
    const title = String(rec["title"] ?? "").trim();
    if (id && title) out.set(id, title);
  }
  return out;
}

/** `create_link`: the raw `js.cmd` handle, still carrying the player's `ffmpeg ` prefix. */
function stalkerLink(body: string): string | null {
  const js = stalkerJs(body);
  const cmd = js?.["cmd"];
  return typeof cmd === "string" && cmd.trim() ? cmd.trim() : null;
}

/**
 * The URL inside a portal command. Portals answer `ffmpeg http://host/…/index.m3u8`, and some append
 * Kodi-style `|User-Agent=…` parameters that players cannot use, so both are cut away here.
 */
function extractStalkerStreamUrl(cmd: string): string | null {
  const clean = String(cmd || "")
    .trim()
    .replace(/^ffmpeg/i, "")
    .trim()
    .split("|")[0]
    ?.trim() ?? "";
  return /^(?:https?|rtsp):\/\//i.test(clean) ? clean : null;
}

async function fetchTextTimeout(url: string, headers: Record<string, string>, timeoutMs = 20000): Promise<string> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, signal: ctrl.signal });
    if (res.status !== 200) throw new StalkerError(res.status);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Remember a source that recently failed outright, so the catalogue does not spend
 * eight 20-second timeouts on every page load while the host is unreachable.
 */
const sourceBackoff: { maxtvUntil: number; maxtvReason: string | null } = {
  maxtvUntil: 0,
  maxtvReason: null,
};
const SOURCE_BACKOFF_MS = 30_000;

const stalkerSession: {
  endpoint: string | null;
  token: string | null;
  profiled: boolean;
  /** The identity that last answered; pinned so later calls keep the same MAC and portal. */
  account: StalkerAccount | null;
} = { endpoint: null, token: null, profiled: false, account: null };

/**
 * The identity the app would use for this configuration.
 *
 * The app upgrades a stale legacy MAC and portal to the active pair before anything else
 * happens, so a device that once stored the old values keeps working without the viewer
 * touching settings. We ship those old values as defaults, so the same upgrade has to run
 * here or the web gateway permanently asks the abandoned portal.
 */
function migrateStalkerAccount(cfg: GatewayConfig): StalkerAccount {
  const legacyMac = cfg.maxtvMac.trim().toUpperCase() === STALKER_LEGACY_MAC.toUpperCase();
  const legacyPortal =
    cfg.maxtvPortal.trim().replace(/\/+$/, "") === STALKER_LEGACY_PORTAL.replace(/\/+$/, "");

  // Only a *shipped default* is migrated. The app migrates a stored preference because it
  // wrote that preference itself as a default; an operator who configured an account has
  // chosen it, and silently swapping their MAC for another is how a working portal becomes
  // "maxTV is failing" with no explanation.
  const migrateMac = legacyMac && !cfg.maxtvMacExplicit;
  const migratePortal = legacyPortal && !cfg.maxtvPortalExplicit;

  return {
    portal: migratePortal ? STALKER_DEFAULT_PORTAL : cfg.maxtvPortal,
    mac: migrateMac ? STALKER_DEFAULT_MAC : cfg.maxtvMac,
    serial: cfg.maxtvSerial,
    deviceId: cfg.maxtvDeviceId,
  };
}

/**
 * The identities worth trying, primary first. The migrated one is the live portal; the
 * legacy pair is only reached if the primary is refused outright, so it costs a failing
 * portal nothing and rescues one that still honours the old account.
 */
function stalkerAccountCandidates(cfg: GatewayConfig): StalkerAccount[] {
  const primary = migrateStalkerAccount(cfg);
  // The configured identity is always tried first. The other published identity is tried
  // second, whichever one that is: a portal that still honours the old account is rescued,
  // and one that has moved on is not asked twice.
  const alternate: StalkerAccount =
    primary.mac.trim().toUpperCase() === STALKER_DEFAULT_MAC.toUpperCase()
      ? { portal: STALKER_LEGACY_PORTAL, mac: STALKER_LEGACY_MAC, serial: primary.serial, deviceId: primary.deviceId }
      : { portal: STALKER_DEFAULT_PORTAL, mac: STALKER_DEFAULT_MAC, serial: primary.serial, deviceId: primary.deviceId };
  return stalkerSameAccount(primary, alternate) ? [primary] : [primary, alternate];
}

function stalkerSameAccount(a: StalkerAccount, b: StalkerAccount): boolean {
  return (
    a.mac.trim().toUpperCase() === b.mac.trim().toUpperCase() &&
    a.portal.trim().replace(/\/+$/, "") === b.portal.trim().replace(/\/+$/, "")
  );
}

/** The account in force: whichever identity last proved it works, else the migrated one. */
function stalkerAccount(cfg: GatewayConfig): StalkerAccount {
  return stalkerSession.account ?? migrateStalkerAccount(cfg);
}

function stalkerHeaders(_cfg: GatewayConfig, account: StalkerAccount, auth: boolean): Record<string, string> {
  const h: Record<string, string> = {
    Cookie: `mac=${encodeURIComponent(account.mac)}; stb_lang=en; timezone=Europe/London`,
    "User-Agent": MAG_USER_AGENT,
    "X-User-Agent": MAG_DEVICE_UA,
    Accept: "*/*",
    // The web-client path of the active account, matching Android StalkerPortal.kt.
    Referer: `${account.portal.replace(/\/+$/, "")}/`,
  };
  if (auth && stalkerSession.token) h["Authorization"] = `Bearer ${stalkerSession.token}`;
  return h;
}

function buildStalkerUrl(base: string, type: string, action: string, params: Record<string, string>): string {
  const url = new URL(base);
  url.searchParams.set("type", type);
  url.searchParams.set("action", action);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  url.searchParams.set("JsHttpRequest", "1-xml");
  return url.toString();
}

async function stalkerRaw(
  cfg: GatewayConfig,
  account: StalkerAccount,
  base: string,
  type: string,
  action: string,
  params: Record<string, string>,
  auth: boolean,
): Promise<string> {
  // 8 candidates x 20s was up to ~160s of blank page when the host is a black hole.
  // A reachable portal answers a handshake well inside this.
  const target = buildStalkerUrl(base, type, action, params);
  try {
    const body = await fetchTextTimeout(target, stalkerHeaders(cfg, account, auth), 7000);
    recordPortal({ at: Date.now(), action, address: portalAddress(base), status: 200, said: portalSaid(body) });
    return body;
  } catch (e) {
    // A refusal is the useful fact; record it before it becomes an exception.
    const status = e instanceof StalkerError && e.status !== null ? e.status : 0;
    recordPortal({
      at: Date.now(),
      action,
      address: portalAddress(base),
      status,
      said: e instanceof Error ? e.message.slice(0, 200) : String(e).slice(0, 200),
    });
    throw e;
  }
}

/** The portal path only: no query, no credentials. */
function portalAddress(base: string): string {
  try {
    const u = new URL(base);
    return `${u.protocol}//${u.host}${u.pathname}`;
  } catch {
    return base.split("?")[0]!;
  }
}

/**
 * Asks every candidate address for a handshake once and keeps the one that answers with a token.
 * A portal that refuses in its own words (expired MAC, too many connections) is reported in those
 * words instead of as a list of HTTP codes.
 */
async function stalkerHandshake(cfg: GatewayConfig): Promise<string> {
  const account = stalkerAccount(cfg);
  if (stalkerSession.endpoint) {
    const body = await stalkerRaw(cfg, account, stalkerSession.endpoint, "stb", "handshake", { token: "", prehash: "0" }, false);
    const issued = stalkerToken(body);
    if (!issued) throw new StalkerError(null, stalkerError(body) ?? "the portal issued no token");
    stalkerSession.token = issued;
    stalkerSession.profiled = false;
    return issued;
  }

  if (Date.now() < sourceBackoff.maxtvUntil) {
    // Recently proven dead: fail fast with the reason we already know.
    throw new StalkerError(null, sourceBackoff.maxtvReason ?? "no portal at this address");
  }

  const failures: string[] = [];
  let spoken: string | null = null;
  let lastErr: unknown = null;

  for (const identity of stalkerAccountCandidates(cfg)) {
    for (const candidate of stalkerCandidates(identity.portal)) {
      try {
        const body = await stalkerRaw(cfg, identity, candidate, "stb", "handshake", { token: "", prehash: "0" }, false);
        const issued = stalkerToken(body);
        if (!issued) {
          const detail = stalkerError(body);
          if (detail && !spoken) spoken = detail;
          failures.push(`${candidate} no token`);
          continue;
        }
        stalkerSession.endpoint = candidate;
        stalkerSession.account = identity;
        stalkerSession.token = issued;
        stalkerSession.profiled = false;
        sourceBackoff.maxtvUntil = 0;
        sourceBackoff.maxtvReason = null;
        return issued;
      } catch (e) {
        lastErr = e;
        if (e instanceof StalkerError) {
          if (e.status === null && e.detail && !spoken) spoken = e.detail;
          failures.push(`${candidate} ${e.status !== null ? `HTTP ${e.status}` : "no token"}`);
        } else {
          failures.push(`${candidate} ${e instanceof Error ? e.message : String(e)}`);
        }
      }
    }
  }

  stalkerSession.endpoint = null;
  stalkerSession.token = null;
  const tried = failures.slice(0, 4).join(", ") + (failures.length > 4 ? " …" : "");
  const detail = spoken ?? (failures.length ? `no portal at this address (tried ${tried})` : "no portal at this address");
  sourceBackoff.maxtvUntil = Date.now() + SOURCE_BACKOFF_MS;
  sourceBackoff.maxtvReason = detail;
  if (lastErr instanceof StalkerError) throw new StalkerError(lastErr.status, lastErr.detail || detail);
  throw new StalkerError(null, detail);
}

async function stalkerRequest(
  cfg: GatewayConfig,
  type: string,
  action: string,
  params: Record<string, string>,
  retried = false,
): Promise<string> {
  const account = stalkerAccount(cfg);
  if (!stalkerSession.token || !stalkerSession.endpoint) await stalkerHandshake(cfg);

  if (!stalkerSession.profiled && stalkerSession.endpoint) {
    stalkerSession.profiled = true;
    // Some portals answer get_profile only after a second step they do not advertise; the token is
    // what the catalogue calls need, so a refusal here is not fatal.
    try {
      await stalkerRaw(
        cfg,
        account,
        stalkerSession.endpoint,
        "stb",
        "get_profile",
        {
          hd: "1",
          auth_second_step: "1",
          prehash: "0",
          num_banks: "2",
          stb_type: "MAG254",
          sn: account.serial,
          device_id: account.deviceId,
          device_id2: account.deviceId,
          signature: "",
          client_type: "STB",
          image_version: "218",
          video_out: "hdmi",
          hw_version: "1.7-BD-00",
          api_signature: "262",
        },
        true,
      );
    } catch {
      // non-fatal on portals that do not enforce get_profile
    }
  }

  if (!stalkerSession.endpoint) throw new StalkerError(null, "no portal address was reached");
  const body = await stalkerRaw(cfg, account, stalkerSession.endpoint, type, action, params, true);

  // An expired token is normal on a portal that sat idle; handshake once and repeat the call.
  if (!retried && isStalkerExpired(body)) {
    stalkerSession.token = null;
    stalkerSession.endpoint = null;
    await stalkerHandshake(cfg);
    return stalkerRequest(cfg, type, action, params, true);
  }
  return body;
}

/**
 * The paginated catalogue, for panels that refuse the bulk `get_all_channels`.
 *
 * Twelve pages of fourteen, which is what the app asks for. A short page ends the walk: the
 * portal is telling us it has nothing more, and guessing a page count would mean asking a
 * portal for pages it does not have.
 */
async function stalkerPagedChannels(cfg: GatewayConfig): Promise<StalkerChannel[]> {
  const out: StalkerChannel[] = [];
  const seen = new Set<string>();
  for (let page = 1; page <= 12; page++) {
    const body = await stalkerRequest(cfg, "itv", "get_ordered_list", {
      genre: "*",
      force_ch_link_check: "0",
      fav: "0",
      sortby: "number",
      hd: "0",
      p: String(page),
    });
    const batch = stalkerChannels(body);
    for (const c of batch) {
      if (seen.has(c.id)) continue;
      seen.add(c.id);
      out.push(c);
    }
    if (batch.length < 14) break;
  }
  return out;
}

/**
 * The whole maxTV catalogue, in the order the portal lists it.
 *
 * Stalker panels frequently issue a handshake token to *any* MAC format and only enforce
 * subscription binding on `get_all_channels` / `get_ordered_list`. If the first identity
 * handshakes cleanly but returns zero channels or a refusal, try the alternate identity too.
 */
async function fetchMaxtvCatalogueForIdentity(
  cfg: GatewayConfig,
  identity?: StalkerAccount,
): Promise<{ channels: LiveChannelItem[]; endpoint: string | null }> {
  if (identity && (!stalkerSession.account || !stalkerSameAccount(stalkerSession.account, identity))) {
    stalkerSession.endpoint = null;
    stalkerSession.token = null;
    stalkerSession.profiled = false;
    stalkerSession.account = identity;
    const singleCfg: GatewayConfig = {
      ...cfg,
      maxtvPortal: identity.portal,
      maxtvMac: identity.mac,
      maxtvPortalExplicit: true,
      maxtvMacExplicit: true,
    };
    return fetchMaxtvCatalogueOnce(singleCfg);
  }
  return fetchMaxtvCatalogueOnce(cfg);
}

async function fetchMaxtvCatalogueOnce(cfg: GatewayConfig): Promise<{ channels: LiveChannelItem[]; endpoint: string | null }> {
  const channelsBody = await stalkerRequest(cfg, "itv", "get_all_channels", {});
  const account = stalkerAccount(cfg);
  let listed = stalkerChannels(channelsBody);

  if (listed.length === 0) {
    const spoken = stalkerError(channelsBody);
    const paged = await stalkerPagedChannels(cfg).catch(() => []);
    if (paged.length > 0) listed = paged;
    else if (spoken) throw new StalkerError(null, spoken);
  }
  if (listed.length === 0) {
    throw new StalkerError(null, stalkerError(channelsBody) ?? "The portal lists no channels");
  }

  // Genres are best effort: a portal may refuse them without refusing the channels.
  let genres = new Map<string, string>();
  try {
    genres = stalkerGenres(await stalkerRequest(cfg, "itv", "get_genres", {}));
  } catch {
    genres = new Map();
  }

  const origin = stalkerOrigin(account.portal);
  const channels: LiveChannelItem[] = listed.map((c) => {
    const genre = genres.get(c.genre);
    return {
      id: `st:${c.id}`,
      rawId: c.id,
      source: "maxtv" as const,
      name: c.name,
      category: genre && genre.trim() && genre !== "*" && genre.toLowerCase() !== "all" ? genre : "maxTV",
      logo: c.logo.startsWith("/") ? `${origin}${c.logo}` : c.logo,
      cmd: c.cmd,
    };
  });

  return { channels, endpoint: stalkerSession.endpoint };
}

async function fetchMaxtvCatalogue(cfg: GatewayConfig): Promise<{ channels: LiveChannelItem[]; endpoint: string | null }> {
  const candidates = stalkerAccountCandidates(cfg);
  let lastErr: unknown = null;
  for (let i = 0; i < candidates.length; i++) {
    try {
      return await fetchMaxtvCatalogueForIdentity(cfg, i === 0 ? undefined : candidates[i]);
    } catch (e) {
      lastErr = e;
      stalkerSession.endpoint = null;
      stalkerSession.token = null;
      stalkerSession.profiled = false;
      stalkerSession.account = null;
      // If the host itself was completely unreachable across all URLs on pass 1, don't repeat network timeouts unless identity differed.
      if (Date.now() < sourceBackoff.maxtvUntil && e instanceof StalkerError && e.detail.startsWith("no portal at this address")) {
        break;
      }
    }
  }
  throw lastErr instanceof Error ? lastErr : new StalkerError(null, "The portal lists no channels");
}

/** The playable URL for one channel handle. */
async function resolveMaxtvLink(cfg: GatewayConfig, cmd: string): Promise<string> {
  const body = await stalkerRequest(cfg, "itv", "create_link", {
    cmd,
    series: "",
    forced_storage: "",
    disable_ad: "0",
    download: "0",
    force_ch_link_check: "0",
  });
  // A portal may hand back the channel's own address unchanged; that is a valid answer too.
  const url = extractStalkerStreamUrl(stalkerLink(body) ?? "") || extractStalkerStreamUrl(cmd);
  if (!url) throw new StalkerError(null, stalkerError(body) ?? "The portal returned no playable link for this channel");
  return url;
}

// ---------- JioTV M3U backup source ----------
//
// A plain HLS playlist used as the fallback when the maxTV portal is unreachable.
// Format is standard extended M3U:
//   #EXTINF:-1 tvg-id="144" tvg-name="Colors HD" tvg-logo="…" group-title="Entertainment", Colors HD
//   https://host/path/144/master.m3u8
// Every stream is HLS, so it is proxied through the same manifest rewriter that
// mpegts/HLS playback already uses.

interface ParsedM3uEntry {
  name: string;
  url: string;
  tvgId: string;
  logo: string;
  group: string;
  /** `#KODIPROP:...license_type=clearkey` — these channels are DRM protected. */
  licenseType: string;
  /** `#KODIPROP:...license_key=…`; either a key-document URL or an inline `kid:key`. */
  licenseKey: string;
  /** `#EXTVLCOPT:http-user-agent=…` — the playlist's own UA, required by the media host. */
  userAgent: string;
  /** `#EXTHTTP:{"cookie":"…"}` — a CDN token, only for the playlist host. */
  cookie: string;
}

const JIO_ATTR_RE = /([A-Za-z0-9_-]+)="([^"]*)"/g;

function parseM3u(text: string, baseUrl: string): ParsedM3uEntry[] {
  const lines = String(text || "")
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);

  const out: ParsedM3uEntry[] = [];
  let pending: Omit<ParsedM3uEntry, "url"> | null = null;
  // Directives may appear before the #EXTINF or between it and the media URL.
  let preLicenseType = "";
  let preLicenseKey = "";
  let preUserAgent = "";
  let preCookie = "";

  const attrValue = (line: string, key: string): string => {
    const m = line.match(new RegExp(`${key}="([^"]*)"`, "i"));
    return m?.[1]?.trim() ?? "";
  };

  const resetPre = () => {
    preLicenseType = "";
    preLicenseKey = "";
    preUserAgent = "";
    preCookie = "";
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;

    if (line.startsWith("#EXTINF")) {
      const comma = line.lastIndexOf(",");
      const name = comma >= 0 ? line.slice(comma + 1).trim() : "";
      if (!name) continue;
      const head = line.slice(0, comma);
      const attrs: Record<string, string> = {};
      for (const m of head.matchAll(JIO_ATTR_RE)) attrs[m[1]!.toLowerCase()] = m[2]!.trim();
      pending = {
        ...{
          name,
          tvgId: attrs["tvg-id"] || attrs["tvg-chno"] || attrs["channel-id"] || "",
          logo: attrs["tvg-logo"] || "",
          group: attrs["group-title"] || "",
        },
        ...{ licenseType: preLicenseType, licenseKey: preLicenseKey, userAgent: preUserAgent, cookie: preCookie },
      };
      resetPre();

      // Directives between the #EXTINF and the media URL override the ones above it.
      let j = i + 1;
      for (; j < lines.length; j++) {
        const l = lines[j]!;
        if (l.startsWith("#EXTINF")) break;
        if (!l.startsWith("#")) break;
        const type = l.match(/^#KODIPROP:inputstream\.adaptive\.license_type=(\S+)/i);
        const key = l.match(/^#KODIPROP:inputstream\.adaptive\.license_key=(\S+)/i);
        const ua = l.match(/^#EXTVLCOPT:http-user-agent=(\S+)/i);
        const cookie = l.match(/^#EXTHTTP:\{"cookie":"([^"]*)"\}/i);
        if (type) pending!.licenseType = type[1]!;
        if (key) pending!.licenseKey = key[1]!;
        if (ua) pending!.userAgent = ua[1]!;
        if (cookie) pending!.cookie = cookie[1]!;
      }
      i = j - 1;
      continue;
    }

    if (line.startsWith("#")) {
      const type = line.match(/^#KODIPROP:inputstream\.adaptive\.license_type=(\S+)/i);
      const key = line.match(/^#KODIPROP:inputstream\.adaptive\.license_key=(\S+)/i);
      const ua = line.match(/^#EXTVLCOPT:http-user-agent=(\S+)/i);
      const cookie = line.match(/^#EXTHTTP:\{"cookie":"([^"]*)"\}/i);
      if (type) preLicenseType = type[1]!;
      if (key) preLicenseKey = key[1]!;
      if (ua) preUserAgent = ua[1]!;
      if (cookie) preCookie = cookie[1]!;
      continue;
    }

    // A media URL. It needs a pending entry; some lists carry the id only in `?id=`.
    let abs = "";
    try {
      abs = new URL(line, baseUrl).toString();
    } catch {
      abs = line;
    }
    if (pending && /^https?:\/\//i.test(abs)) {
      const idFromUrl = abs.match(/[?&]id=(\d{1,10})/)?.[1] ?? "";
      const tvgId = pending.tvgId || idFromUrl;
      if (tvgId || pending.name) {
        out.push({ ...pending, tvgId, url: abs });
      }
    }
    pending = null;
    resetPre();
  }
  return out;
}

/** "dash", "hls" or "mp4" from the media URL, never from the page. */
function jioMediaType(url: string): "hls" | "mp4" | "dash" {
  const ext = url.split("?")[0]!.split(".").pop()?.toLowerCase() ?? "";
  if (ext === "m3u8") return "hls";
  if (ext === "mp4") return "mp4";
  return "dash";
}

/** The headers an IPTV player sends: the playlist's own UA, no Origin/Referer (the CDN 451s on those). */
function jioMediaHeaders(entry: ParsedM3uEntry, includeCookie: boolean): Record<string, string> {
  const h: Record<string, string> = { "User-Agent": entry.userAgent || JIO_UA_OVERRIDE };
  if (includeCookie && entry.cookie) h["Cookie"] = entry.cookie;
  return h;
}

/** A real manifest, not an HTML error page or a login wall that happens to return 200. */
function isManifest(type: "hls" | "mp4" | "dash", body: string): boolean {
  const head = body.slice(0, 2048);
  if (type === "hls") return head.trimStart().startsWith("#EXTM3U");
  if (type === "mp4") return false;
  return /<MPD/i.test(head) || /urn:mpeg:dash/i.test(head);
}

interface ClearKeyPair {
  kid: string;
  k: string;
}

/**
 * The provider's ClearKey document: `{"keys":[{"kty":"oct","k":"…","kid":"…"}]}`.
 * Plain `kid:key` pairs and `{"keys":{"kid":"key"}}` are accepted too.
 */
function parseClearKeys(body: string): ClearKeyPair[] {
  if (!body.trim() || body.length > 64 * 1024) return [];
  const out: ClearKeyPair[] = [];
  const normalised = (v: string | undefined | null): string | null => {
    const t = (v ?? "").trim();
    if (/^[0-9a-fA-F]{32}$/.test(t)) return t;
    if (/^[A-Za-z0-9_-]{16,88}$/.test(t)) return t;
    return null;
  };
  const push = (kid: unknown, key: unknown) => {
    const id = normalised(typeof kid === "string" ? kid : "");
    const k = normalised(typeof key === "string" ? key : "");
    if (id && k) out.push({ kid: id, k });
  };

  try {
    const parsed = JSON.parse(body) as Record<string, unknown>;
    const keys = parsed["keys"];
    if (Array.isArray(keys)) {
      for (const item of keys.slice(0, 8)) {
        if (!item || typeof item !== "object") continue;
        const rec = item as Record<string, unknown>;
        push(rec["kid"] ?? rec["keyId"], rec["k"] ?? rec["key"]);
      }
    } else if (keys && typeof keys === "object") {
      for (const [kid, k] of Object.entries(keys as Record<string, unknown>)) push(kid, k);
    }
    if (out.length === 0) push(parsed["kid"], parsed["k"]);
    return out;
  } catch {
    // Not JSON: fall back to plain kid:key tokens.
  }
  for (const token of body.trim().split(/[,;\s]+/)) {
    if (!token.includes(":")) continue;
    push(token.slice(0, token.indexOf(":")), token.slice(token.indexOf(":") + 1));
  }
  return out;
}

/** `cenc:default_KID` values from an MPD, normalised to base64url (the form ClearKey uses). */
/**
 * Reconcile a key document with the manifest's declared key ids.
 *
 * If none of the advertised ids appear in the manifest and there is exactly one key, the
 * key is relabelled onto each id the manifest declares. Fewer than one, or several
 * disagreeing keys, are left alone: guessing there would decrypt the wrong track.
 */
function matchClearKeys(keys: ClearKeyPair[], kids: string[]): ClearKeyPair[] {
  if (keys.length === 0 || kids.length === 0) return keys;
  if (keys.some((k) => kids.includes(k.kid))) return keys;
  if (keys.length !== 1) return keys;
  const only = keys[0]!.k;
  return kids.map((kid) => ({ kid, k: only }));
}

function defaultKids(mpd: string): string[] {
  const out = new Set<string>();
  const re = /cenc:default_KID\s*=\s*["']([0-9a-fA-F-]{32,36})["']/g;
  for (const m of mpd.matchAll(re)) {
    const hex = m[1]!.replace(/-/g, "");
    if (hex.length !== 32) continue;
    const bytes = new Uint8Array(16);
    for (let i = 0; i < 16; i++) bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    out.add(toBase64Url(bytes));
  }
  return [...out];
}

/** The key endpoint the playlist advertises, when it is HTTPS on the playlist's own host. */
function jioLicenseUrl(entry: ParsedM3uEntry, siteHost: string): string | null {
  const raw = (entry.licenseKey || "").trim();
  if (!/^https:\/\//i.test(raw)) return null;
  try {
    const u = new URL(raw);
    if (u.protocol !== "https:" || u.username || !u.hostname) return null;
    if (u.hostname.toLowerCase() !== siteHost.toLowerCase()) return null;
    return raw;
  } catch {
    return null;
  }
}

const JIO_UA_OVERRIDE = "@allinone_reborn";
const JIO_SITE = "https://allinonereborn2.online";
const jioKeyCache = new Map<string, { keys: ClearKeyPair[]; until: number }>();

/** Reads the provider's key document. Keys stay in memory and are never written to disk. */
async function jioKeysFor(entry: ParsedM3uEntry, siteHost: string): Promise<ClearKeyPair[]> {
  // Inline `kid:key` needs no request.
  const inline: ClearKeyPair[] = [];
  if (entry.licenseKey && !/^https?:\/\//i.test(entry.licenseKey)) {
    for (const pair of entry.licenseKey.split(",")) {
      const i = pair.indexOf(":");
      if (i > 0) inline.push({ kid: pair.slice(0, i).trim(), k: pair.slice(i + 1).trim() });
    }
  }
  const url = jioLicenseUrl(entry, siteHost);
  if (!url) return inline;

  const cached = jioKeyCache.get(url);
  if (cached && cached.until > Date.now()) return [...inline, ...cached.keys];

  try {
    // Bounded: a key document that never answers used to stall the entire playlist
    // build, leaving the guide on "Loading…" with no way out.
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 4000);
    let body = "";
    try {
      const res = await fetch(url, {
        headers: {
          Origin: JIO_SITE,
          Referer: `${JIO_SITE}/`,
          Accept: "application/json",
          "User-Agent": JIO_UA_OVERRIDE,
        },
        signal: ctrl.signal,
      });
      if (!res.ok) return inline;
      body = await res.text();
    } finally {
      clearTimeout(timer);
    }
    const keys = parseClearKeys(body);
    if (keys.length) jioKeyCache.set(url, { keys, until: Date.now() + 10 * 60 * 1000 });
    return [...inline, ...keys];
  } catch {
    return inline;
  }
}

let jioPlaylistShape = 0;

/**
 * The last attempt to read the JioTV playlist: which address and header shape answered, and
 * what every attempt got back when none did.
 *
 * The playlist is a URL on someone else's server and the only way to know why it refused is
 * to record the refusal. Without this, an unreachable playlist and an empty one look the
 * same from the player, and both look like "no channels".
 */
let jioPlaylistReport: {
  at: number;
  ok: boolean;
  attempts: Array<{ url: string; shape: number; status: number; bytes: number; sniff: string }>;
  winner: string | null;
  entries: number;
  error: string | null;
} | null = null;

/** The numbered playlist file that last answered, asked before the shipped one. */
let jioPlaylistWinner: string | null = null;

/**
 * The playlist is a numbered file the site rebuilds from time to time -- the app's note
 * records `jtv33` becoming `jtv39`, with the old file simply ceasing to exist. We shipped
 * one number as a constant, so the day it moved, the whole backup tab became an empty
 * section and every stream fell through to a seed entry that answers 503.
 *
 * Ask the address that last worked, then the shipped one, then its neighbours (forward
 * first, since the file has only ever moved forward).
 */
function jioPlaylistCandidates(cfg: GatewayConfig): string[] {
  const shipped = cfg.jioPlaylistUrl;
  const out: string[] = [];
  if (jioPlaylistWinner) out.push(jioPlaylistWinner);
  out.push(shipped);

  const numbered = /jtv(\d{1,3})\.m3u8$/i.exec(shipped);
  const number = numbered?.[1] ? Number.parseInt(numbered[1], 10) : null;
  if (number !== null && numbered) {
    const base = shipped.slice(0, shipped.length - numbered[0].length);
    for (let step = 1; step <= 8; step++) out.push(`${base}jtv${number + step}.m3u8`);
    for (let step = 1; step <= 3; step++) {
      if (number - step > 0) out.push(`${base}jtv${number - step}.m3u8`);
    }
  }
  // Only this site's own numbered files are ever followed.
  return [...new Set(out.filter((u) => /jtv\d{1,3}\.m3u8$/i.test(u) || u === shipped))];
}

const jioCache: { items: LiveChannelItem[]; fetchedAt: number; error: string | null } = {
  items: [],
  fetchedAt: 0,
  error: null,
};

/**
 * The statuses Jio's CDN uses to say "your token is not valid for this": 450 and 451 are
 * its own codes and appear in the app's notes as exactly that, with 403 for the same
 * meaning. They are not about the channel, so the answer is a fresher token.
 */
function isJioRefusal(status: number): boolean {
  return status === 450 || status === 451 || status === 403 || status === 401;
}

/**
 * Re-read the playlist and return this channel as it is advertised *now*.
 *
 * Rate-limited: one refresh per few seconds, no matter how many players ask at once. A
 * room full of viewers hitting an aged-out token must not turn into a room full of
 * playlist fetches, and the catalogue reload is not cheap.
 */
let jioRefreshAt = 0;

async function refreshJioChannel(
  cfg: GatewayConfig,
  channel: LiveChannelItem,
): Promise<LiveChannelItem | null> {
  if (Date.now() - jioRefreshAt < 5_000) return null;
  jioRefreshAt = Date.now();
  try {
    const fresh = await getChannelsBySource(cfg, "jio", true);
    return fresh.find((c) => c.id === channel.id || c.rawId === channel.rawId) ?? null;
  } catch {
    // A refresh that fails leaves the old entry in place; the caller reports the refusal.
    return null;
  }
}

async function fetchJioCatalogue(cfg: GatewayConfig): Promise<LiveChannelItem[]> {
  // The front door refuses a scriptless client (HTTP 415) when the request looks like a
  // media player. Try the shapes the Android app tries, cheapest first, and keep the one
  // that answers for the rest of this process.
  const shapes: Array<Record<string, string>> = [
    { Accept: "*/*" },
    { Accept: "*/*", "User-Agent": JIO_UA_OVERRIDE },
    { Accept: "application/vnd.apple.mpegurl, application/x-mpegurl" },
    { Origin: JIO_SITE, Referer: `${JIO_SITE}/`, Accept: "*/*" },
    {
      Origin: JIO_SITE,
      Referer: `${JIO_SITE}/`,
      Accept: "application/vnd.apple.mpegurl, application/x-mpegurl",
      "User-Agent": JIO_UA_OVERRIDE,
    },
  ];

  let text = "";
  let status = 0;
  let networkError = "";
  let usedUrl = cfg.jioPlaylistUrl;
  // Kept so the diagnostic can say which address and which header shape answered -- or,
  // when nothing did, exactly what each attempt got back.
  const attempts: Array<{ url: string; shape: number; status: number; bytes: number; sniff: string }> = [];
  const order = [jioPlaylistShape, ...shapes.map((_, i) => i)];
  search: for (const candidate of jioPlaylistCandidates(cfg)) {
    for (const idx of order) {
      try {
        const res = await fetch(candidate, { headers: shapes[idx]! });
        if (!res.ok) {
          status = res.status;
          const peek = await res.text().catch(() => "");
          attempts.push({
            url: candidate,
            shape: idx!,
            status: res.status,
            bytes: peek.length,
            sniff: peek.trim().slice(0, 120).replace(/\s+/g, " "),
          });
          // A 404 is the numbered file having moved on; stop trying shapes for it.
          if (res.status === 404) break;
          continue;
        }
        const body = await res.text();
        if (!body.trimStart().startsWith("#EXTM3U")) {
          attempts.push({
            url: candidate,
            shape: idx!,
            status: res.status,
            bytes: body.length,
            sniff: body.trim().slice(0, 120).replace(/\s+/g, " "),
          });
          continue;
        }
        attempts.push({ url: candidate, shape: idx!, status: res.status, bytes: body.length, sniff: "#EXTM3U" });
        text = body;
        usedUrl = candidate;
        jioPlaylistShape = idx!;
        jioPlaylistWinner = candidate;
        break search;
      } catch (e) {
        networkError = e instanceof Error ? e.message : String(e);
      }
    }
  }

  if (!text) {
    jioPlaylistReport = {
      at: Date.now(),
      ok: false,
      attempts,
      winner: null,
      entries: 0,
      error: status ? `HTTP ${status}` : networkError || "no response",
    };
    if (status) throw new Error(`Backup playlist returned HTTP ${status}`);
    throw new Error(`Backup playlist unreachable (${networkError || "no response"})`);
  }

  const entries = parseM3u(text, usedUrl);
  jioPlaylistReport = { at: Date.now(), ok: true, attempts, winner: usedUrl, entries: entries.length, error: null };
  if (entries.length === 0) throw new Error("Backup playlist listed no channels");

  const siteHost = (() => {
    try {
      return new URL(usedUrl).hostname;
    } catch {
      return "allinonereborn2.online";
    }
  })();

  // Key documents are fetched concurrently. Doing this one channel at a time meant the
  // catalogue took as long as the sum of every key round-trip -- measured at 6s for 60
  // channels, so a real playlist of a few hundred sat on "Loading…" for half a minute.
  const wantsDrm = (e: ParsedM3uEntry) => Boolean(e.licenseType.toLowerCase() === "clearkey" || e.licenseKey);

  const keyStore = new Map<number, ClearKeyPair[]>();
  const drmIndexes = entries.map((e, i) => (wantsDrm(e) ? i : -1)).filter((i) => i >= 0);
  const CONCURRENCY = 8;
  for (let start = 0; start < drmIndexes.length; start += CONCURRENCY) {
    const batch = drmIndexes.slice(start, start + CONCURRENCY);
    const settled = await Promise.all(
      batch.map(async (i) => {
        try {
          return [i, await jioKeysFor(entries[i]!, siteHost)] as const;
        } catch {
          // A single unreachable key document must not take the playlist down with it.
          return [i, [] as ClearKeyPair[]] as const;
        }
      }),
    );
    for (const [i, keys] of settled) keyStore.set(i, keys);
  }

  const items: LiveChannelItem[] = [];
  for (const [i, e] of entries.entries()) {
    const mediaType = jioMediaType(e.url);
    // Only DRM-protected entries need a key round-trip; plain HLS does not.
    let drm: LiveChannelItem["drm"];
    if (wantsDrm(e)) {
      const keys = keyStore.get(i) ?? [];
      const licenseUrl = jioLicenseUrl(e, siteHost) ?? undefined;
      // Kept even with zero keys: the player can pull the MPD's default_KID itself.
      drm = { type: "clearkey", keys, ...(licenseUrl ? { licenseUrl } : {}) };
    }
    items.push({
      // Stable id: the playlist's own channel number, else its position.
      id: `jio:${e.tvgId || i}`,
      rawId: e.tvgId || String(i),
      source: "jio" as const,
      name: e.name,
      category: e.group || "JioTV",
      logo: e.logo,
      cmd: e.url,
      mediaType,
      userAgent: e.userAgent || JIO_UA_OVERRIDE,
      ...(e.cookie ? { cookie: e.cookie } : {}),
      ...(drm ? { drm } : {}),
    });
  }
  return items;
}

async function loadJioCached(cfg: GatewayConfig, force = false): Promise<LiveChannelItem[]> {
  if (!force && jioCache.items.length > 0 && Date.now() - jioCache.fetchedAt < CATALOGUE_TTL_MS) {
    return jioCache.items;
  }
  try {
    const live = await fetchJioCatalogue(cfg);
    jioCache.items = live;
    jioCache.fetchedAt = Date.now();
    jioCache.error = null;
    return live;
  } catch (e) {
    const primaryError = e instanceof Error ? e.message : String(e);
    // On Cloudflare Workers, allinonereborn2.online often blocks datacenter IPs (HTTP 403/415/450),
    // whereas giotv-6op.pages.dev/jstr4web.json serves the 1,176-channel Jio catalogue over Pages CDN.
    const gioFallback = await loadGioTvRawCached(force).catch(() => []);
    if (gioFallback.length > 0) {
      const mapped: LiveChannelItem[] = gioFallback.map((g) => ({
        id: `jio:${g.id}`,
        rawId: g.id,
        source: "jio" as const,
        name: g.name,
        category: g.category || "JioTV",
        logo: g.logo,
        cmd: g.urls[0]!,
        mediaType: jioMediaType(g.urls[0]!),
        ...(g.keys.length ? { drm: { type: "clearkey" as const, keys: g.keys } } : {}),
      }));
      jioCache.items = mapped;
      jioCache.fetchedAt = Date.now();
      jioCache.error = null;
      return mapped;
    }
    jioCache.error = primaryError;
    return jioCache.items;
  }
}

const xtreamState: { workingOrigin: string; canonUser: string; canonPass: string } = {
  workingOrigin: "",
  canonUser: "",
  canonPass: "",
};

async function fetchStarshareCatalogue(cfg: GatewayConfig): Promise<LiveChannelItem[]> {
  if (!cfg.starshareUser || !cfg.starsharePass) return [];
  const origins = [cfg.starshareServer, cfg.starshareFallback].filter(Boolean).map((u) => u.replace(/\/+$/, ""));
  let lastErr: unknown = null;
  for (const origin of origins) {
    try {
      const u = encodeURIComponent(cfg.starshareUser);
      const p = encodeURIComponent(cfg.starsharePass);
      const authText = await fetchTextTimeout(
        `${origin}/player_api.php?username=${u}&password=${p}`,
        { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
        12000,
      );
      const auth = JSON.parse(authText) as { user_info?: Record<string, unknown> };
      const ui = auth?.user_info;
      if (!ui || !(ui["auth"] === 1 || ui["auth"] === "1" || ui["status"] === "Active")) {
        throw new Error("StarShare auth rejected");
      }
      xtreamState.workingOrigin = origin;
      xtreamState.canonUser = String(ui["username"] || cfg.starshareUser).trim();
      xtreamState.canonPass = String(ui["password"] || cfg.starsharePass).trim();

      const cu = encodeURIComponent(xtreamState.canonUser);
      const cp = encodeURIComponent(xtreamState.canonPass);
      const catsRaw = await fetchTextTimeout(
        `${origin}/player_api.php?username=${cu}&password=${cp}&action=get_live_categories`,
        { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
        18000,
      ).catch(() => "[]");
      const catsMap = new Map<string, string>();
      for (const c of JSON.parse(catsRaw) as Array<Record<string, unknown>>) {
        if (c?.["category_id"] && c?.["category_name"]) catsMap.set(String(c["category_id"]), String(c["category_name"]).trim());
      }

      const streamsRaw = await fetchTextTimeout(
        `${origin}/player_api.php?username=${cu}&password=${cp}&action=get_live_streams`,
        { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
        30000,
      );
      const arr = JSON.parse(streamsRaw) as Array<Record<string, unknown>>;
      return arr
        .filter((it) => it?.["stream_id"] && it?.["name"])
        .map((it) => ({
          id: `xt:${String(it["stream_id"])}`,
          rawId: String(it["stream_id"]),
          source: "starshare" as const,
          name: String(it["name"]).trim(),
          category: catsMap.get(String(it["category_id"])) || "StarShare Live",
          logo: String(it["stream_icon"] || "").trim(),
        }));
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr instanceof Error ? lastErr : new Error("StarShare unreachable");
}

// ---------- In-Memory Catalogue Cache ----------

const memCache: {
  maxtv: { items: LiveChannelItem[]; fetchedAt: number; usingSeed: boolean; error: string | null; endpoint: string | null };
  starshare: { items: LiveChannelItem[]; fetchedAt: number; error: string | null };
} = {
  maxtv: { items: [], fetchedAt: 0, usingSeed: false, error: null, endpoint: null },
  starshare: { items: [], fetchedAt: 0, error: null },
};

async function loadMaxtvCached(cfg: GatewayConfig, force = false): Promise<LiveChannelItem[]> {
  if (!force && memCache.maxtv.items.length > 0 && !memCache.maxtv.usingSeed && Date.now() - memCache.maxtv.fetchedAt < CATALOGUE_TTL_MS) {
    return memCache.maxtv.items;
  }
  try {
    const { channels: live, endpoint } = await fetchMaxtvCatalogue(cfg);
    // Remember the address that answered so later calls skip discovery.
    if (endpoint) memCache.maxtv.endpoint = endpoint;
    memCache.maxtv = { items: live, fetchedAt: Date.now(), usingSeed: false, error: null, endpoint: endpoint ?? memCache.maxtv.endpoint };
    return live;
  } catch (e) {
    const fallback = memCache.maxtv.items.length > 0 ? memCache.maxtv.items : PREVIEW_SEED_CHANNELS;
    memCache.maxtv = {
      items: fallback,
      fetchedAt: Date.now(),
      usingSeed: fallback === PREVIEW_SEED_CHANNELS,
      error: e instanceof Error ? e.message : String(e),
      endpoint: memCache.maxtv.endpoint,
    };
    return fallback;
  }
}

async function loadStarshareCached(cfg: GatewayConfig, force = false): Promise<LiveChannelItem[]> {
  if (!force && memCache.starshare.items.length > 0 && Date.now() - memCache.starshare.fetchedAt < CATALOGUE_TTL_MS) {
    return memCache.starshare.items;
  }
  try {
    const live = await fetchStarshareCatalogue(cfg);
    memCache.starshare = { items: live, fetchedAt: Date.now(), error: null };
    return live;
  } catch (e) {
    memCache.starshare = {
      items: memCache.starshare.items,
      fetchedAt: Date.now(),
      error: e instanceof Error ? e.message : String(e),
    };
    return memCache.starshare.items;
  }
}

// ---------- Browser-Direct GioTV / Jstr4Web + TG TV Live RPC Resolvers ----------
//
// Why this exists:
// Lovable deploys `screen-safari-studio` onto Cloudflare Workers (`AS13335`). Upstream media CDNs
// (`new.maxtv.be` media edges -> HTTP 403, `jiotvmblive.cdn.jio.com` / `jiotvpllive.cdn.jio.com` /
// `allinonereborn2.online` -> HTTP 450/451) block Cloudflare Worker datacenter egress IPs whenever
// video manifests or segments are proxied server-side.
//
// Meanwhile, `jiotvmblive.cdn.jio.com` and `jiotvpllive.cdn.jio.com` send `Access-Control-Allow-Origin: *`
// and accept direct HTTPS requests from the viewer's own Indian residential browser IP when:
//   1. `Referer` is suppressed (`no-referrer`),
//   2. `__hdnea__=...` is appended to MANIFEST and SEGMENT URLs via Shaka's `registerRequestFilter`, and
//   3. ClearKey hex pairs are configured on `shaka.Player`.
//
// The metadata endpoints below (`giotv-6op.pages.dev` and `livetgtv.lovable.app/_serverFn/...`) answer
// `200 OK` even from Cloudflare Worker datacenter IPs, supplying fresh tokens + ClearKeys in ~150ms.

const BROWSER_METADATA_UA =
  "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
const GIOTV_SITE = "https://giotv-6op.pages.dev";
const GIOTV_CATALOGUE_URLS = [
  "https://giotv-6op.pages.dev/jstr4web.json",
  "https://jjtvxweb.pages.dev/jstr4web.json",
];
const GIOTV_REGULAR_COOKIES_URL = "https://allinonereborn2.online/jstrweb2/cookies.json";
const GIOTV_STAR_COOKIES_URL = "https://giotv-6op.pages.dev/cookie.json";
const TG_LIVE_SITE = "https://livetgtv.lovable.app";
const TG_INITIAL_SERVER_FN = "211ba18c9fcbaf4e68e5bb262905070b60b7ed4ffe802d16fbbed00150f1c024";

interface GioTvChannelRecord {
  id: string;
  name: string;
  category: string;
  logo: string;
  urls: string[];
  keys: ClearKeyPair[];
}

const gioTvRawCache: { items: GioTvChannelRecord[]; fetchedAt: number } = {
  items: [],
  fetchedAt: 0,
};

const gioCookieCache: {
  regular: { value: string; until: number } | null;
  starBody: { raw: string; until: number } | null;
} = {
  regular: null,
  starBody: null,
};

let tgVerifiedFnId: string = TG_INITIAL_SERVER_FN;
let tgDiscoveredAt = 0;
const tgChannelCache = new Map<string, { until: number; candidates: DirectPlayCandidate[] }>();

function extractHdneaToken(raw: string | undefined | null): string {
  if (!raw) return "";
  const m = /(?:^|[?;&\s])(__hdnea__=[^;\s&"'<>]+)/.exec(String(raw).trim());
  return m?.[1] ?? "";
}

function hdneaExpiryMs(tokenOrCookie: string): number | null {
  const tok = extractHdneaToken(tokenOrCookie) || tokenOrCookie;
  if (!tok) return null;
  try {
    const decoded = decodeURIComponent(tok);
    const m = /(?:^|~)exp=(\d+)(?:~|$)/.exec(decoded);
    if (!m?.[1]) return null;
    const sec = Number.parseInt(m[1], 10);
    return Number.isFinite(sec) && sec > 0 ? sec * 1000 : null;
  } catch {
    return null;
  }
}

function pickFreshestHdnea(values: string[]): string {
  const now = Date.now();
  const candidates = values.map((v) => extractHdneaToken(v)).filter(Boolean);
  if (candidates.length === 0) return "";
  candidates.sort((a, b) => {
    const expA = hdneaExpiryMs(a);
    const expB = hdneaExpiryMs(b);
    const rank = (exp: number | null) => (exp === null ? 1 : exp > now + 10_000 ? 2 : 0);
    const rDiff = rank(expB) - rank(expA);
    if (rDiff !== 0) return rDiff;
    return (expB ?? Number.MIN_SAFE_INTEGER) - (expA ?? Number.MIN_SAFE_INTEGER);
  });
  return candidates[0]!;
}

function toHex16(raw: string | undefined | null): string | null {
  const v = String(raw ?? "").trim();
  if (!v) return null;
  const cleanUuid = v.replace(/-/g, "");
  if (/^[0-9a-fA-F]{32}$/.test(cleanUuid)) return cleanUuid.toLowerCase();
  const norm = v.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
  if (!/^[A-Za-z0-9+/]+$/.test(norm)) return null;
  const padded = norm + "===".slice((norm.length + 3) % 4);
  try {
    const bin = atob(padded);
    if (bin.length !== 16) return null;
    let hex = "";
    for (let i = 0; i < 16; i++) hex += bin.charCodeAt(i).toString(16).padStart(2, "0");
    return hex;
  } catch {
    return null;
  }
}

function pairsToHexRecord(pairs: ClearKeyPair[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const p of pairs) {
    const kid = toHex16(p.kid);
    const k = toHex16(p.k);
    if (kid && k) out[kid] = k;
  }
  return out;
}

function parseGioTvCatalogue(body: string): GioTvChannelRecord[] {
  const trimmed = body.replace(/^\uFEFF/, "").trim();
  if (!trimmed || trimmed.startsWith("<")) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const out: GioTvChannelRecord[] = [];
  const seen = new Set<string>();
  for (const raw of parsed) {
    if (!raw || typeof raw !== "object") continue;
    const item = raw as Record<string, unknown>;
    const id = String(item["id"] ?? item["channel_id"] ?? "").trim();
    const name = String(item["name"] ?? item["channel_name"] ?? "").trim();
    if (!id || !/^\d{1,10}$/.test(id) || !name || seen.has(id)) continue;

    const urls: string[] = [];
    for (const field of ["url", "url1", "url2", "url3"]) {
      const val = item[field];
      if (typeof val === "string" && /^https?:\/\//i.test(val.trim())) {
        const normalized = val.trim().replace("bpk-tv//", "bpk-tv/");
        if (!urls.includes(normalized)) urls.push(normalized);
      }
    }
    if (urls.length === 0) continue;

    const keys: ClearKeyPair[] = [];
    if (Array.isArray(item["keys"])) {
      for (const kp of item["keys"] as unknown[]) {
        if (!kp || typeof kp !== "object") continue;
        const rec = kp as Record<string, unknown>;
        const kid = toHex16(String(rec["keyId"] ?? rec["kid"] ?? ""));
        const k = toHex16(String(rec["key"] ?? rec["k"] ?? ""));
        if (kid && k) keys.push({ kid, k });
      }
    }
    if (keys.length === 0) {
      const kid = toHex16(String(item["keyId"] ?? item["kid"] ?? ""));
      const k = toHex16(String(item["key"] ?? item["k"] ?? ""));
      if (kid && k) keys.push({ kid, k });
    }

    seen.add(id);
    out.push({
      id,
      name,
      category: String(item["category"] ?? "Live TV").trim() || "Live TV",
      logo: String(item["logo"] ?? "").trim(),
      urls,
      keys,
    });
  }
  return out;
}

async function loadGioTvRawCached(force = false): Promise<GioTvChannelRecord[]> {
  if (!force && gioTvRawCache.items.length > 0 && Date.now() - gioTvRawCache.fetchedAt < CATALOGUE_TTL_MS) {
    return gioTvRawCache.items;
  }
  for (const candidateUrl of GIOTV_CATALOGUE_URLS) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 9000);
    try {
      const res = await fetch(candidateUrl, {
        headers: {
          "User-Agent": BROWSER_METADATA_UA,
          Accept: "application/json, */*",
          "Cache-Control": "no-cache",
        },
        signal: ctrl.signal,
      });
      if (!res.ok) continue;
      const text = await res.text();
      const parsed = parseGioTvCatalogue(text);
      if (parsed.length > 0) {
        gioTvRawCache.items = parsed;
        gioTvRawCache.fetchedAt = Date.now();
        return parsed;
      }
    } catch {
      // try next mirror
    } finally {
      clearTimeout(timer);
    }
  }
  return gioTvRawCache.items;
}

function isJioStarPlUrl(url: string): boolean {
  try {
    return new URL(url).hostname.toLowerCase() === "jiotvpllive.cdn.jio.com";
  } catch {
    return false;
  }
}

async function fetchGioRegularCookie(force = false): Promise<string> {
  if (!force && gioCookieCache.regular && gioCookieCache.regular.until > Date.now()) {
    return gioCookieCache.regular.value;
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 6000);
  try {
    const res = await fetch(GIOTV_REGULAR_COOKIES_URL, {
      headers: { "User-Agent": BROWSER_METADATA_UA, Accept: "application/json, */*", "Cache-Control": "no-cache" },
      signal: ctrl.signal,
    });
    if (!res.ok) return gioCookieCache.regular?.value ?? "";
    const data = (await res.json()) as unknown;
    const rawCookies: string[] = [];
    if (Array.isArray(data)) {
      for (const row of data) {
        if (row && typeof row === "object" && typeof (row as Record<string, unknown>)["cookie"] === "string") {
          rawCookies.push((row as Record<string, string>)["cookie"]!);
        }
      }
    } else if (data && typeof data === "object" && typeof (data as Record<string, unknown>)["cookie"] === "string") {
      rawCookies.push((data as Record<string, string>)["cookie"]!);
    }
    const chosen = pickFreshestHdnea(rawCookies);
    if (chosen) {
      const exp = hdneaExpiryMs(chosen);
      const until = Math.min(Date.now() + 60_000, exp ? exp - 10_000 : Date.now() + 60_000);
      if (until > Date.now()) gioCookieCache.regular = { value: chosen, until };
    }
    return chosen;
  } catch {
    return gioCookieCache.regular?.value ?? "";
  } finally {
    clearTimeout(timer);
  }
}

async function fetchGioStarCookieForChannel(channelId: string, force = false): Promise<string> {
  let raw = !force && gioCookieCache.starBody && gioCookieCache.starBody.until > Date.now()
    ? gioCookieCache.starBody.raw
    : "";
  if (!raw) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 7000);
    try {
      const res = await fetch(GIOTV_STAR_COOKIES_URL, {
        headers: {
          "User-Agent": BROWSER_METADATA_UA,
          Origin: GIOTV_SITE,
          Accept: "application/json, */*",
          "Cache-Control": "no-cache",
        },
        signal: ctrl.signal,
      });
      if (res.ok) {
        raw = await res.text();
        gioCookieCache.starBody = { raw, until: Date.now() + 45_000 };
      }
    } catch {
      raw = gioCookieCache.starBody?.raw ?? "";
    } finally {
      clearTimeout(timer);
    }
  }
  if (!raw) return "";
  try {
    const root = JSON.parse(raw) as Record<string, unknown>;
    const found: string[] = [];
    for (const bucket of ["successful_results", "failed_results"]) {
      const arr = root[bucket];
      if (!Array.isArray(arr)) continue;
      for (const entry of arr) {
        if (!entry || typeof entry !== "object") continue;
        const item = entry as Record<string, unknown>;
        if (String(item["channel_id"] ?? "") !== String(channelId)) continue;
        const errDetails = item["error_details"] as Record<string, unknown> | undefined;
        const finalUrl = String(errDetails?.["final_url"] ?? item["final_url"] ?? "");
        const tok = extractHdneaToken(finalUrl);
        if (tok) found.push(tok);
      }
    }
    return pickFreshestHdnea(found);
  } catch {
    return "";
  }
}

/** Decode TanStack Start Seroval / `application/x-tss-framed` RPC payloads (mirrors `TgProtocol.kt`). */
function decodeTgSerovalNode(node: unknown, refs: Map<number, unknown>, depth = 0): unknown {
  if (depth > 48 || node === null || node === undefined) return node;
  if (Array.isArray(node)) return node.map((x) => decodeTgSerovalNode(x, refs, depth + 1));
  if (typeof node !== "object") return node;
  const obj = node as Record<string, unknown>;
  const tag = obj["t"];
  if (tag && typeof tag === "object" && "f" in obj) return decodeTgSerovalNode(tag, refs, depth + 1);
  if (typeof tag !== "number") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(obj)) out[k] = decodeTgSerovalNode(v, refs, depth + 1);
    return out;
  }
  const id = typeof obj["i"] === "number" ? (obj["i"] as number) : undefined;
  switch (tag) {
    case 0:
    case 1:
    case 3:
      return obj["s"];
    case 2:
      return obj["s"] === 2 ? true : obj["s"] === 3 ? false : null;
    case 4:
      return id !== undefined ? (refs.get(id) ?? null) : null;
    case 9: {
      const arr: unknown[] = [];
      if (id !== undefined) refs.set(id, arr);
      const items = Array.isArray(obj["a"]) ? (obj["a"] as unknown[]) : [];
      for (const it of items) arr.push(decodeTgSerovalNode(it, refs, depth + 1));
      return arr;
    }
    case 10:
    case 11: {
      const mapObj: Record<string, unknown> = {};
      if (id !== undefined) refs.set(id, mapObj);
      const props = (obj["p"] && typeof obj["p"] === "object" ? obj["p"] : {}) as Record<string, unknown>;
      const keys = Array.isArray(props["k"]) ? (props["k"] as unknown[]) : [];
      const vals = Array.isArray(props["v"]) ? (props["v"] as unknown[]) : [];
      const len = Math.min(keys.length, vals.length);
      for (let i = 0; i < len; i++) {
        const k = typeof keys[i] === "object" ? String(decodeTgSerovalNode(keys[i], refs, depth + 1)) : String(keys[i]);
        mapObj[k] = decodeTgSerovalNode(vals[i], refs, depth + 1);
      }
      return mapObj;
    }
    case 12:
      return obj["s"] === true || obj["s"] === 1 ? decodeTgSerovalNode(obj["f"], refs, depth + 1) : null;
    case 21:
      return decodeTgSerovalNode(obj["f"], refs, depth + 1);
    case 23: {
      const a = Array.isArray(obj["a"]) ? (obj["a"] as unknown[]) : [];
      return decodeTgSerovalNode(a[1], refs, depth + 1);
    }
    default:
      return null;
  }
}

function parseTgFramedOrJsonRecords(bytes: Uint8Array, contentType: string): unknown[] {
  const ct = contentType.split(";")[0]!.trim().toLowerCase();
  if (ct === "application/x-tss-framed") {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    const dec = new TextDecoder();
    const out: unknown[] = [];
    let pos = 0;
    while (pos + 9 <= bytes.byteLength) {
      const kind = view.getUint8(pos);
      const stream = view.getInt32(pos + 1, false);
      const length = view.getInt32(pos + 5, false);
      pos += 9;
      if (length < 0 || pos + length > bytes.byteLength) break;
      if (kind === 0 && stream === 0) {
        try {
          out.push(JSON.parse(dec.decode(bytes.subarray(pos, pos + length))));
        } catch {
          // ignore malformed frame
        }
      }
      pos += length;
    }
    return out;
  }
  const text = new TextDecoder().decode(bytes).replace(/^\uFEFF/, "").trim();
  if (!text) return [];
  try {
    return [JSON.parse(text)];
  } catch {
    const out: unknown[] = [];
    for (const line of text.split(/\r?\n/)) {
      const t = line.trim();
      if (t.startsWith("{") || t.startsWith("[")) {
        try {
          out.push(JSON.parse(t));
        } catch {
          // ignore line
        }
      }
    }
    return out;
  }
}

async function discoverTgServerFn(force = false): Promise<string[]> {
  if (!force && tgVerifiedFnId && Date.now() - tgDiscoveredAt < 10 * 60_000) {
    return [tgVerifiedFnId];
  }
  const found = new Set<string>();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 7000);
  try {
    const pageUrl = `${TG_LIVE_SITE}/watch/892`;
    const pageRes = await fetch(pageUrl, {
      headers: { "User-Agent": BROWSER_METADATA_UA, Accept: "text/html,*/*" },
      signal: ctrl.signal,
    });
    if (pageRes.ok) {
      const html = await pageRes.text();
      const scriptMatches = [...html.matchAll(/["'`]((?:\/)?assets\/[^"'`<>\s]+\.m?js)["'`]/g)].map((m) =>
        new URL(m[1]!, `${TG_LIVE_SITE}/`).toString(),
      );
      scriptMatches.sort((a, b) => (a.includes("servers") ? -1 : 1) - (b.includes("servers") ? -1 : 1));
      for (const scriptUrl of scriptMatches.slice(0, 5)) {
        const sRes = await fetch(scriptUrl, {
          headers: { "User-Agent": BROWSER_METADATA_UA, Referer: pageUrl },
          signal: ctrl.signal,
        }).catch(() => null);
        if (!sRes || !sRes.ok) continue;
        const code = await sRes.text();
        for (const m of code.matchAll(/_serverFn\/([A-Za-z0-9_-]{16,128})/g)) found.add(m[1]!);
        for (const m of code.matchAll(/\(\s*["'`]([a-f0-9]{64})["'`]\s*\)/gi)) found.add(m[1]!);
        if (found.size > 0 && scriptUrl.includes("servers")) break;
      }
    }
  } catch {
    // keep fallback id
  } finally {
    clearTimeout(timer);
  }
  if (tgVerifiedFnId) found.add(tgVerifiedFnId);
  found.add(TG_INITIAL_SERVER_FN);
  tgDiscoveredAt = Date.now();
  return [...found];
}

function extractTgCandidatesFromConfig(decoded: unknown): DirectPlayCandidate[] {
  if (!decoded || typeof decoded !== "object") return [];
  const root = decoded as Record<string, unknown>;
  const cfg = (root["result"] ?? root["data"] ?? root) as Record<string, unknown>;
  if (!cfg || typeof cfg !== "object") return [];

  const extractClearKeys = (srv: Record<string, unknown>, fallbackKeys: Record<string, string>): Record<string, string> => {
    const out: Record<string, string> = { ...fallbackKeys };
    const ck = srv["clearKeys"];
    if (ck && typeof ck === "object" && !Array.isArray(ck)) {
      for (const [rawKid, rawK] of Object.entries(ck as Record<string, unknown>)) {
        const kid = toHex16(rawKid);
        const k = toHex16(String(rawK ?? ""));
        if (kid && k) out[kid] = k;
      }
    }
    const kidSingle = toHex16(String(srv["keyId"] ?? srv["kid"] ?? ""));
    const kSingle = toHex16(String(srv["key"] ?? srv["k"] ?? ""));
    if (kidSingle && kSingle) out[kidSingle] = kSingle;
    return out;
  };

  const baseKeys = extractClearKeys(cfg, {});
  const servers: Array<{ label: string; node: Record<string, unknown> }> = [];
  if (Array.isArray(cfg["urls"]) && cfg["urls"].length > 0) {
    servers.push({ label: "TG Live · Server 1", node: cfg });
  }
  if (Array.isArray(cfg["backups"])) {
    (cfg["backups"] as unknown[]).forEach((b, idx) => {
      if (b && typeof b === "object") {
        servers.push({ label: `TG Live · Server ${servers.length + 1 || idx + 2}`, node: b as Record<string, unknown> });
      }
    });
  }

  const out: DirectPlayCandidate[] = [];
  for (const srv of servers) {
    const urls = Array.isArray(srv.node["urls"]) ? (srv.node["urls"] as unknown[]) : [];
    const cookie = extractHdneaToken(String(srv.node["cookie"] ?? cfg["cookie"] ?? ""));
    const clearKeys = extractClearKeys(srv.node, baseKeys);
    for (const u of urls) {
      if (typeof u !== "string" || !/^https?:\/\//i.test(u.trim())) continue;
      const cleanUrl = u.trim().replace("bpk-tv//", "bpk-tv/");
      const container: "dash" | "hls" = /\.m3u8(?:$|\?)/i.test(cleanUrl) ? "hls" : "dash";
      out.push({ label: srv.label, url: cleanUrl, cookie, clearKeys, container });
    }
  }
  return out;
}

async function resolveTgRpcCandidates(numericId: string, force = false): Promise<DirectPlayCandidate[]> {
  if (!/^\d{1,10}$/.test(numericId)) return [];
  if (!force) {
    const cached = tgChannelCache.get(numericId);
    if (cached && cached.until > Date.now()) return cached.candidates;
  }

  const callFn = async (fnId: string): Promise<DirectPlayCandidate[]> => {
    const inner = {
      t: 10,
      i: 0,
      p: { k: ["data"], v: [{ t: 10, i: 1, p: { k: ["id"], v: [{ t: 1, s: numericId }] }, o: 0 }] },
      o: 0,
    };
    const payload = encodeURIComponent(JSON.stringify({ t: inner, f: 127, m: [] }));
    const rpcUrl = `${TG_LIVE_SITE}/_serverFn/${fnId}?payload=${payload}${force ? `&_aktv=${Date.now()}` : ""}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 6500);
    try {
      const res = await fetch(rpcUrl, {
        headers: {
          "User-Agent": BROWSER_METADATA_UA,
          Origin: TG_LIVE_SITE,
          Referer: `${TG_LIVE_SITE}/watch/${numericId}`,
          Accept: "application/x-tss-framed, application/x-ndjson, application/json",
          "x-tsr-serverfn": "true",
          "Cache-Control": "no-cache",
        },
        signal: ctrl.signal,
      });
      if (!res.ok) return [];
      const buf = new Uint8Array(await res.arrayBuffer());
      const records = parseTgFramedOrJsonRecords(buf, res.headers.get("content-type") || "");
      const refs = new Map<number, unknown>();
      const out: DirectPlayCandidate[] = [];
      for (const rec of records) {
        const decoded = decodeTgSerovalNode(rec, refs);
        out.push(...extractTgCandidatesFromConfig(decoded));
      }
      return out;
    } catch {
      return [];
    } finally {
      clearTimeout(timer);
    }
  };

  let result = await callFn(tgVerifiedFnId);
  if (result.length === 0) {
    // Reuse the 10-minute discovery cache on normal plays; only a forced refresh pays for
    // full `_serverFn` re-discovery. Either way the caller races this whole resolver so a
    // hanging TG RPC can never delay the GioTV candidates past the direct-config budget.
    const discovered = await discoverTgServerFn(force);
    for (const fnId of discovered) {
      if (fnId === tgVerifiedFnId && !force) continue;
      result = await callFn(fnId);
      if (result.length > 0) {
        tgVerifiedFnId = fnId;
        break;
      }
    }
  }

  if (result.length > 0) {
    const expiries = result.map((c) => hdneaExpiryMs(c.cookie || c.url)).filter((x): x is number => x !== null);
    const minExp = expiries.length > 0 ? Math.min(...expiries) : Date.now() + 30_000;
    const until = Math.min(Date.now() + 30_000, minExp - 10_000);
    if (until > Date.now()) tgChannelCache.set(numericId, { until, candidates: result });
  }
  return result;
}

function normalizeChannelMatchTokens(rawName: string): { base: string; noQuality: string; numbers: string[]; tokens: string[] } {
  let s = rawName
    .toLowerCase()
    .replace(/^[|\[(\{\s]*(?:in|ind|india|indian|vip|hindi|uk|us)[|\])\}\s:\-–—]+/gi, "")
    .replace(/\b(?:sony\s+sports\s+ten)\b/g, "sony ten")
    .replace(/\b(?:sony\s+set)\b/g, "set")
    .replace(/\b(?:star\s+sports\s+hd\s*(\d+))\b/g, "star sports $1 hd")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .trim();
  s = s.replace(/\s+/g, " ");
  const noQuality = s.replace(/\b(?:hd|sd|fhd|uhd|4k|live|tv|india)\b/g, " ").replace(/\s+/g, " ").trim();
  const numbers = [...s.matchAll(/\b\d+\b/g)].map((m) => m[0]!);
  const tokens = noQuality.split(" ").filter((t) => t.length > 0);
  return { base: s, noQuality, numbers, tokens };
}

function findBestGioTvMatch(records: GioTvChannelRecord[], rawId: string, channelName: string): GioTvChannelRecord | null {
  const numericId = rawId.replace(/^(?:jio|tg|st|xt):/i, "").trim();
  // Direct numeric V1/Jio ID match ONLY when the channel actually came from jio/tgtv (not st:1001 maxTV IDs!)
  if (/^(?:jio|tg):/i.test(rawId) || (!rawId.includes(":") && /^\d+$/.test(rawId))) {
    const byId = records.find((r) => r.id === numericId);
    if (byId) return byId;
  }
  if (!channelName.trim()) return null;

  const target = normalizeChannelMatchTokens(channelName);
  const wantsHd = /\bhd\b/i.test(channelName);
  let best: { rec: GioTvChannelRecord; score: number } | null = null;

  for (const rec of records) {
    const cand = normalizeChannelMatchTokens(rec.name);
    // Channel numbers (1, 2, 3, 18, 24, etc.) must agree so Star Sports 1 never matches Star Sports 2
    if (target.numbers.join(",") !== cand.numbers.join(",")) continue;
    let score = 0;
    if (cand.base === target.base) score = 100;
    else if (cand.noQuality && cand.noQuality === target.noQuality) {
      const candHd = /\bhd\b/i.test(rec.name);
      score = candHd === wantsHd ? 95 : 88;
    } else if (target.tokens.length > 0 && cand.tokens.length > 0) {
      const tSet = new Set(target.tokens);
      const cSet = new Set(cand.tokens);
      let overlap = 0;
      for (const tok of tSet) if (cSet.has(tok)) overlap += 1;
      const union = new Set([...tSet, ...cSet]).size;
      const jaccard = union > 0 ? overlap / union : 0;
      if (jaccard >= 0.75) {
        score = Math.round(jaccard * 85) + (/\bhd\b/i.test(rec.name) === wantsHd ? 3 : 0);
      }
    }
    if (score > 0 && (!best || score > best.score)) {
      best = { rec, score };
    }
  }
  return best ? best.rec : null;
}

interface PlaylistMint {
  /** Final URL reached by following the playlist redirector (signed CDN address, or ""). */
  finalUrl: string;
  redirectHops: number;
  lastStatus: number;
  /** True when the walk reached a `*.cdn.jio.com` address for the browser to play. */
  ok: boolean;
}

/**
 * The Android app's channel-open probe (`JioM3uResolver.open`), headers-only half, for the worker.
 *
 * The app plays a JioTV channel by opening its playlist address (`jtv2.mpd?id=N`) with the
 * playlist's own headers and following the 302 to the signed CDN address — that redirect
 * mints a fresh per-request token. The browser cannot do this itself (its automatic `Origin`
 * header gets HTTP 451 from the redirector, and the worker cannot proxy media), so the worker
 * walks the redirect chain with `redirect: "manual"` and hands the browser the minted URL as
 * JSON metadata. No media body is ever downloaded here: the walk stops at the first non-3xx
 * response and captures its URL — even a worker-side HTTP 450 — because the viewer's own
 * residential IP opens that same URL with HTTP 200.
 */
async function walkPlaylistRedirector(
  entryUrl: string,
  entryCookie: string,
  entryUserAgent: string,
  budgetMs: number,
): Promise<PlaylistMint> {
  const deadline = Date.now() + budgetMs;
  const ua = entryUserAgent.trim() || JIO_UA_OVERRIDE;
  // The app's shape order: the bare IPTV-player request first (the one that answers), the
  // Origin/Referer shapes last (usually HTTP 451 before ever redirecting).
  const shapes: Array<Record<string, string>> = [
    { "User-Agent": ua, ...(entryCookie ? { Cookie: entryCookie } : {}) },
    { "User-Agent": ua },
    { "User-Agent": ua, ...(entryCookie ? { Cookie: entryCookie } : {}), Origin: JIO_SITE, Referer: `${JIO_SITE}/` },
    { "User-Agent": BROWSER_METADATA_UA },
  ];
  let hops = 0;
  let lastStatus = 0;
  for (const headers of shapes) {
    let current = entryUrl;
    for (let hop = 0; hop < 5; hop++) {
      const remaining = deadline - Date.now();
      if (remaining <= 300) break;
      // A URL that already carries its own minted token is opened bare: sending the coarse
      // playlist cookie alongside it asks the CDN to honour two credentials and it answers
      // 450 rather than choosing (the same rule `proxyMediaStream` applies per hop).
      const hopHeaders: Record<string, string> = { Accept: "*/*", ...headers };
      if (carriesOwnToken(current)) delete hopHeaders["Cookie"];
      let res: Response | null = null;
      try {
        const ctrl = new AbortController();
        const timer = setTimeout(() => ctrl.abort(), Math.min(2500, remaining));
        try {
          res = await fetch(current, {
            headers: hopHeaders,
            redirect: "manual",
            signal: ctrl.signal,
          });
        } finally {
          clearTimeout(timer);
        }
      } catch {
        break;
      }
      if (!res) break;
      lastStatus = res.status;
      hops += 1;
      if (res.status >= 300 && res.status < 400) {
        try {
          await res.arrayBuffer();
        } catch {
          // ignore drain errors
        }
        const loc = res.headers.get("location");
        if (!loc) break;
        try {
          current = new URL(loc, current).toString();
        } catch {
          break;
        }
        continue;
      }
      // First non-redirect response: capture its URL. Media bodies are never downloaded — a
      // 200 here would be a manifest (cancelled, not read), a 450 the known worker ASN block;
      // either way the browser opens the captured URL itself on its own residential IP.
      if (res.status !== 200) {
        try {
          await res.arrayBuffer();
        } catch {
          // ignore drain errors
        }
      } else {
        try {
          await res.body?.cancel();
        } catch {
          // ignore cancel errors
        }
      }
      let host = "";
      try {
        host = new URL(current).hostname.toLowerCase();
      } catch {
        break;
      }
      if (host.endsWith(".cdn.jio.com") || host === "cdn.jio.com") {
        return { finalUrl: current, redirectHops: hops, lastStatus, ok: true };
      }
      break;
    }
  }
  return { finalUrl: "", redirectHops: hops, lastStatus, ok: false };
}

async function resolveDirectPlayCandidates(
  cfg: GatewayConfig,
  rawId: string,
  channelName: string,
  force = false,
): Promise<{
  matchedId: string | null;
  matchedName: string | null;
  candidates: DirectPlayCandidate[];
  playlistMint: { tried: boolean; ok: boolean; hops: number; lastStatus: number; tokenSecondsLeft: number | null };
}> {
  const gioRecords = await loadGioTvRawCached(force);
  const matchedGio = findBestGioTvMatch(gioRecords, rawId, channelName);
  const cleanPrefixId = rawId.replace(/^(?:jio|tg):/i, "").trim();
  const v1NumericId =
    matchedGio?.id ?? (/^(?:jio|tg):/i.test(rawId) && /^\d+$/.test(cleanPrefixId) ? cleanPrefixId : null);

  const candidates: DirectPlayCandidate[] = [];
  const seenKey = new Set<string>();
  const pushCandidate = (c: DirectPlayCandidate) => {
    const k = `${c.url}|${c.cookie}`;
    if (seenKey.has(k)) return;
    seenKey.add(k);
    candidates.push(c);
  };

  // 1. Live TG RPC (`Server 1` + `Server 2`) and GioTV cookies fetched in parallel for minimum latency.
  // The TG leg is budget-raced: when `_serverFn` hangs or rotates its hash, GioTV candidates
  // still reach the browser within the 7s `/api/live/direct-config` client budget instead of
  // stalling behind discovery (the background TG call warms `tgVerifiedFnId` for the retry).
  const tgBudgeted = (async (): Promise<DirectPlayCandidate[]> => {
    if (!v1NumericId) return [];
    const budget = new Promise<DirectPlayCandidate[]>((resolve) => setTimeout(() => resolve([]), 5000));
    return Promise.race([resolveTgRpcCandidates(v1NumericId, force), budget]);
  })();
  // The playlist's own entry (`jtv39.m3u8`): its per-channel `#EXTHTTP` cookie (BTS entries
  // carry a token minted for exactly their channel path) upgrades the Gio token choice, and its
  // redirector address (`jtv2.mpd?id=N`) is walked headers-only to mint the fresh signed CDN URL
  // the Android app plays. Budget-raced so a blocked playlist fetch can never delay
  // direct-config; the background load warms `jioCache` for the next play.
  const playlistBudgeted = (async (): Promise<{ cookie: string; entry: LiveChannelItem | null; mint: PlaylistMint | null }> => {
    const empty = { cookie: "", entry: null, mint: null };
    if (!v1NumericId || !matchedGio) return empty;
    const budget = new Promise<typeof empty>((resolve) => setTimeout(() => resolve(empty), 5000));
    const work = (async () => {
      let hit: LiveChannelItem | null = null;
      try {
        const pool = await loadJioCached(cfg, false);
        // GioTV-fallback items share the ids but are not playlist entries; only a real entry
        // whose stream points at the playlist host counts.
        hit = pool.find((c) => c.rawId === v1NumericId && c.cmd && /allinonereborn/i.test(c.cmd)) ?? null;
      } catch {
        hit = null;
      }
      const cookie = extractHdneaToken(hit?.cookie) || "";
      let mint: PlaylistMint | null = null;
      if (hit?.cmd) {
        try {
          const walked = await walkPlaylistRedirector(hit.cmd, cookie, hit.userAgent || "", 4000);
          mint = walked.ok ? walked : { ...walked, finalUrl: "" };
        } catch {
          mint = null;
        }
      }
      return { cookie, entry: hit, mint };
    })();
    return Promise.race([work, budget]);
  })();
  const [tgCandidates, regularCookie, starCookie, playlist] = await Promise.all([
    tgBudgeted,
    matchedGio && matchedGio.urls.some((u) => !isJioStarPlUrl(u)) ? fetchGioRegularCookie(force) : Promise.resolve(""),
    matchedGio && matchedGio.urls.some((u) => isJioStarPlUrl(u))
      ? fetchGioStarCookieForChannel(matchedGio.id, force)
      : Promise.resolve(""),
    playlistBudgeted,
  ]);
  const playlistCookie = playlist.cookie;

  const gioKeys = matchedGio ? pairsToHexRecord(matchedGio.keys) : {};

  // TG RPC servers (`Server 1` + `Server 2`), Gio keys backfilling bare backups.
  const tgList: DirectPlayCandidate[] = tgCandidates.map((tc) => ({
    ...tc,
    clearKeys: Object.keys(tc.clearKeys).length > 0 ? tc.clearKeys : gioKeys,
  }));

  // 2. Jio CDN URLs (`jstr4web.json` catalogue) + fresh Star/Regular/playlist `__hdnea__` tokens.
  // URLs stay verbatim (query intact) exactly like the app's `JjtvProtocol.streams`: the browser
  // applies the cookie with replace semantics and keeps every sibling parameter.
  // Labelled JioTV, not GioTV: the catalogue JSON is the only GioTV part — the bytes the
  // browser plays come from Jio's own `*.cdn.jio.com` edges.
  const jioList: DirectPlayCandidate[] = [];
  if (matchedGio) {
    for (const rawUrl of matchedGio.urls) {
      const container: "dash" | "hls" = /\.m3u8(?:$|\?)/i.test(rawUrl) ? "hls" : "dash";
      const embeddedToken = extractHdneaToken(rawUrl);
      const coarseCookie = isJioStarPlUrl(rawUrl) ? starCookie : regularCookie;
      // Channel-minted playlist cookie wins over the coarse site cookie when it is fresher;
      // an expired playlist cookie loses automatically, so no extra candidate is needed.
      const freshCookie = playlistCookie ? pickFreshestHdnea([playlistCookie, coarseCookie]) : coarseCookie;
      if (freshCookie) {
        jioList.push({
          label: "JioTV Direct · Fresh Token",
          url: rawUrl,
          cookie: freshCookie,
          clearKeys: gioKeys,
          container,
        });
      }
      if (embeddedToken) {
        jioList.push({
          label: "JioTV Direct · Embedded Token",
          url: rawUrl,
          cookie: embeddedToken,
          clearKeys: gioKeys,
          container,
        });
      }
      if (!freshCookie && !embeddedToken) {
        jioList.push({
          label: "JioTV Direct",
          url: rawUrl,
          cookie: "",
          clearKeys: gioKeys,
          container,
        });
      }
    }
  }

  // The playlist redirector's freshly-minted signed URL: exactly what the Android app plays
  // after its channel-open probe. The cookie is the token the redirect minted onto that URL
  // (falling back to the playlist `#EXTHTTP` cookie when the URL carries none), so the browser
  // signs segments with the same per-request credential and the root request keeps its sibling
  // params. Keys prefer the playlist's own key document, GioTV keys backfilling when the key
  // document was unreachable.
  const redirectList: DirectPlayCandidate[] = [];
  if (playlist.mint?.ok && playlist.mint.finalUrl && playlist.entry) {
    const entryKeys = playlist.entry.drm?.keys?.length
      ? pairsToHexRecord(playlist.entry.drm.keys)
      : gioKeys;
    redirectList.push({
      label: "JioTV Playlist · Redirector",
      url: playlist.mint.finalUrl,
      cookie: extractHdneaToken(playlist.mint.finalUrl) || playlistCookie,
      clearKeys: entryKeys,
      container: jioMediaType(playlist.mint.finalUrl) === "hls" ? "hls" : "dash",
    });
  }

  // The app's JioTV tab connects to JioTV, not TG: a `jio:` channel tries the playlist-minted
  // URL first, then its Jio CDN candidates, and only falls back to TG's servers when Jio fails.
  // TG TV tab (`tg:`) and maxTV rescue (`st:`) keep TG first with the minted URL next. The
  // freshest-first sort below is stable, so this preference holds among equally-fresh tokens
  // while a working token always beats an expired one.
  const isJio = rawId.toLowerCase().startsWith("jio:");
  const ordered = isJio
    ? [...redirectList, ...jioList, ...tgList]
    : [...tgList, ...redirectList, ...jioList];
  for (const c of ordered) pushCandidate(c);

  // 3. Also include direct HTTPS M3U entry if `tg:` or `jio:` came from a custom M3U playlist.
  if (candidates.length === 0) {
    const pool = rawId.startsWith("tg:")
      ? await loadTgTvCached(cfg, false)
      : rawId.startsWith("jio:")
        ? await loadJioCached(cfg, false)
        : [];
    const ch = pool.find((c) => c.id === rawId || c.rawId === rawId);
    if (ch?.cmd && /^https:\/\//i.test(ch.cmd) && !ch.cmd.includes("allinonereborn")) {
      pushCandidate({
        label: "Playlist Direct",
        url: ch.cmd,
        cookie: extractHdneaToken(ch.cookie),
        clearKeys: ch.drm?.keys ? pairsToHexRecord(ch.drm.keys) : {},
        container: ch.mediaType === "hls" ? "hls" : "dash",
      });
    }
  }

  // 4. Never waste player time on a provably-expired token when the same URL also has a
  // fresh one, and order freshest-first (stable, so the source-aware preference above survives
  // among equally-fresh tokens and TG Server 1 still precedes Server 2). Pruning filters in
  // place so a minted URL interleaved between two catalogue variants keeps its position.
  const now = Date.now();
  const rankCandidate = (c: DirectPlayCandidate): number => {
    const exp = hdneaExpiryMs(c.cookie || c.url);
    if (exp === null) return 1;
    return exp > now + 5_000 ? 2 : 0;
  };
  const usableByUrl = new Map<string, boolean>();
  for (const c of candidates) {
    if (rankCandidate(c) > 0) usableByUrl.set(c.url, true);
  }
  const pruned = candidates.filter((c) => rankCandidate(c) > 0 || !usableByUrl.get(c.url));
  pruned.sort((a, b) => rankCandidate(b) - rankCandidate(a));

  // Redacted mint diagnostics (lifetimes and statuses only, never token values): opening
  // `/api/live/direct-config?id=jio:<n>` in a browser shows whether the worker reached the
  // playlist redirector or fell back to catalogue candidates.
  const mintExp = playlist.mint?.ok && playlist.mint.finalUrl ? hdneaExpiryMs(playlist.mint.finalUrl) : null;
  return {
    matchedId: v1NumericId,
    matchedName: matchedGio?.name ?? (channelName || null),
    candidates: pruned,
    playlistMint: {
      tried: playlist.entry !== null,
      ok: playlist.mint?.ok ?? false,
      hops: playlist.mint?.redirectHops ?? 0,
      lastStatus: playlist.mint?.lastStatus ?? 0,
      tokenSecondsLeft: mintExp === null ? null : Math.round((mintExp - now) / 1000),
    },
  };
}

const tgtvCache: { items: LiveChannelItem[]; fetchedAt: number; error: string | null } = {
  items: [],
  fetchedAt: 0,
  error: null,
};

async function fetchTgTvCatalogue(cfg: GatewayConfig): Promise<LiveChannelItem[]> {
  // Primary: GioTV / TG V1 1,176-channel JSON catalogue (works on Cloudflare Workers with full ClearKeys).
  const gio = await loadGioTvRawCached(false);
  if (gio.length > 0) {
    return gio.map((g) => ({
      id: `tg:${g.id}`,
      rawId: g.id,
      source: "tgtv" as const,
      name: g.name,
      category: g.category || "TG TV",
      logo: g.logo,
      cmd: g.urls[0]!,
      mediaType: jioMediaType(g.urls[0]!),
      ...(g.keys.length ? { drm: { type: "clearkey" as const, keys: g.keys } } : {}),
    }));
  }

  // Fallback: M3U playlist at `cfg.tgtvPlaylistUrl`
  if (!cfg.tgtvPlaylistUrl) return [];
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 10000);
  let body = "";
  try {
    const res = await fetch(cfg.tgtvPlaylistUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        Accept: "*/*",
      },
      signal: ctrl.signal,
    });
    if (!res.ok) throw new Error(`TG TV playlist returned HTTP ${res.status}`);
    body = await res.text();
  } finally {
    clearTimeout(timer);
  }
  if (!body.trimStart().startsWith("#EXTM3U")) {
    throw new Error("TG TV playlist did not return valid M3U");
  }
  const entries = parseM3u(body, cfg.tgtvPlaylistUrl);
  if (entries.length === 0) throw new Error("TG TV playlist listed no channels");
  return entries.map((e, i) => ({
    id: `tg:${e.tvgId || i}`,
    rawId: e.tvgId || String(i),
    source: "tgtv" as const,
    name: e.name,
    category: e.group || "TG TV",
    logo: e.logo,
    cmd: e.url,
    mediaType: jioMediaType(e.url),
    ...(e.userAgent ? { userAgent: e.userAgent } : {}),
    ...(e.cookie ? { cookie: e.cookie } : {}),
  }));
}

async function loadTgTvCached(cfg: GatewayConfig, force = false): Promise<LiveChannelItem[]> {
  if (!force && tgtvCache.items.length > 0 && Date.now() - tgtvCache.fetchedAt < CATALOGUE_TTL_MS) {
    return tgtvCache.items;
  }
  try {
    const live = await fetchTgTvCatalogue(cfg);
    tgtvCache.items = live;
    tgtvCache.fetchedAt = Date.now();
    tgtvCache.error = null;
    return live;
  } catch (e) {
    tgtvCache.error = e instanceof Error ? e.message : String(e);
    return tgtvCache.items;
  }
}

async function getChannelsBySource(cfg: GatewayConfig, source: string, force = false): Promise<LiveChannelItem[]> {
  const s = source.toLowerCase();
  if (s === "starshare") return loadStarshareCached(cfg, force);
  if (s === "jio") return loadJioCached(cfg, force);
  if (s === "tgtv") return loadTgTvCached(cfg, force);
  if (s === "all") {
    const [m, j, t] = await Promise.all([
      loadMaxtvCached(cfg, force),
      loadJioCached(cfg, force),
      loadTgTvCached(cfg, force),
    ]);
    // The seed rows are placeholders that cannot play, so they must never pad out a
    // combined list: "Both" would otherwise show 24 dead entries above the live ones,
    // and the top of the guide would be the part that does not work.
    const live = memCache.maxtv.usingSeed ? [] : m;
    const combined = [...live, ...j, ...t];
    return combined.length > 0 ? combined : m;
  }
  // The default path is "maxTV, with JioTV and TG TV backup playlists behind it".
  // A portal that answered with a real catalogue wins; anything else falls through
  // so a viewer still gets channels that actually play even on Cloudflare Worker IPs.
  const maxtv = await loadMaxtvCached(cfg, force);
  if (!memCache.maxtv.usingSeed && maxtv.length > 0) return maxtv;
  const jio = await loadJioCached(cfg, force);
  if (jio.length > 0) return jio;
  const tgtv = await loadTgTvCached(cfg, force);
  return tgtv.length > 0 ? tgtv : maxtv;
}

// ---------- Web Crypto HMAC Signing for HLS Segments ----------

function toBase64Url(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]!);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(str: string): Uint8Array {
  const padded = str.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((str.length + 3) % 4);
  const bin = atob(padded);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function signSegmentToken(
  url: string,
  mode: "mag" | "xtream",
  secret: string,
  headers?: { ua?: string; ck?: string },
): Promise<string> {
  const enc = new TextEncoder();
  const signed: Record<string, unknown> = { u: url, m: mode };
  // Child requests (segments, sub-manifests) must keep the playlist's own UA and
  // cookie. Without them the Jio CDN answers 451 to the bare browser request.
  if (headers?.ua) signed["h"] = { ua: headers.ua, ...(headers.ck ? { ck: headers.ck } : {}) };
  const payload = toBase64Url(enc.encode(JSON.stringify(signed)));
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sigBuf = await crypto.subtle.sign("HMAC", key, enc.encode(payload));
  const sig = toBase64Url(new Uint8Array(sigBuf)).slice(0, 22);
  return `${payload}.${sig}`;
}

interface VerifiedSegmentToken {
  u: string;
  m: "mag" | "xtream";
  ua?: string;
  ck?: string;
}

async function verifySegmentToken(token: string, secret: string): Promise<VerifiedSegmentToken | null> {
  const [payload, sig] = String(token || "").split(".");
  if (!payload || !sig) return null;
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sigBuf = await crypto.subtle.sign("HMAC", key, enc.encode(payload));
  const expected = toBase64Url(new Uint8Array(sigBuf)).slice(0, 22);
  if (sig !== expected) return null;
  try {
    const parsed = JSON.parse(new TextDecoder().decode(fromBase64Url(payload))) as {
      u?: string;
      m?: "mag" | "xtream";
      h?: { ua?: string; ck?: string };
    };
    if (!parsed?.u || !/^https?:\/\//i.test(parsed.u)) return null;
    return {
      u: parsed.u,
      m: parsed.m === "xtream" ? "xtream" : "mag",
      ...(parsed.h?.ua ? { ua: parsed.h.ua } : {}),
      ...(parsed.h?.ck ? { ck: parsed.h.ck } : {}),
    };
  } catch {
    return null;
  }
}

async function rewriteHlsManifestWeb(
  manifestText: string,
  baseUrl: string,
  mode: "mag" | "xtream",
  publicOrigin: string,
  secret: string,
  headers?: { ua?: string; ck?: string },
): Promise<string> {
  const lines = manifestText.split(/\r?\n/);
  const out: string[] = [];
  const signRef = async (ref: string): Promise<string> => {
    const trimmed = ref.trim();
    if (!trimmed) return ref;
    let abs: string;
    try {
      abs = new URL(trimmed, baseUrl).toString();
    } catch {
      return ref;
    }
    const tok = await signSegmentToken(abs, mode, secret, headers);
    // Root-relative on purpose. The player fetches this manifest from *this* origin, so a
    // relative reference resolves correctly with no guessing, in every environment and
    // inside an embed. An absolute URL built from the server's idea of its own address is
    // wrong behind a proxy, and a wrong segment origin looks exactly like a dead stream:
    // the manifest loads, then nothing plays.
    return `/api/live/segment?t=${encodeURIComponent(tok)}`;
  };

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      out.push(line);
      continue;
    }
    if (trimmed.startsWith("#")) {
      const m = trimmed.match(/URI="([^"]+)"/);
      if (m?.[1]) {
        const signed = await signRef(m[1]);
        out.push(line.replace(/URI="([^"]+)"/, `URI="${signed}"`));
      } else {
        out.push(line);
      }
      continue;
    }
    out.push(await signRef(trimmed));
  }
  return out.join("\n");
}

function overrideHeadersFromToken(
  v: VerifiedSegmentToken,
  targetUrl?: string,
): Record<string, string> | undefined {
  // The decision belongs here, at the moment we know the exact URL: a segment that carries
  // its own `__hdnea__` token must not also be sent the playlist's coarse cookie, or the CDN
  // is asked to honour two credentials and answers 450.
  const keepCookie = Boolean(v.ck) && !(targetUrl && carriesOwnToken(targetUrl));
  if (!v.ua && !keepCookie) return undefined;
  const out: Record<string, string> = {};
  if (v.ua) out["User-Agent"] = v.ua;
  if (keepCookie && v.ck) out["Cookie"] = v.ck;
  return Object.keys(out).length ? out : undefined;
}

/** The UA/cookie pair to seal into child tokens, derived from the upstream request. */
/**
 * A CDN address that already carries its own `__hdnea__` token must not be handed the
 * playlist's cookie as well.
 *
 * This is the CDN's two credentials disagreeing, and the app names it outright: the
 * playlist's `#EXTHTTP` cookie is a coarse token minted for the whole site, while the URL
 * behind the redirect carries a token minted for one channel and one path. Sending the
 * coarse one alongside a URL that already carries the specific one is asking the CDN to
 * choose between two answers, and it declines with 450 -- which is what "the JioTV CDN
 * refused this channel (HTTP 450)" was.
 */
function carriesOwnToken(url: string): boolean {
  return url.includes("__hdnea__");
}

function tokenHeadersFrom(
  overrideHeaders?: Record<string, string>,
  targetUrl?: string,
): { ua?: string; ck?: string } | undefined {
  const ua = overrideHeaders?.["User-Agent"];
  const ck = overrideHeaders?.["Cookie"];
  // Keep the user agent, drop the conflicting credential.
  const keepCookie = Boolean(ck) && !(targetUrl && carriesOwnToken(targetUrl));
  if (!ua && !keepCookie) return undefined;
  const out: { ua?: string; ck?: string } = {};
  if (ua) out.ua = ua;
  if (keepCookie && ck) out.ck = ck;
  return out;
}

/**
 * DASH manifests need the same treatment as HLS playlists: an absolute BaseURL or
 * SegmentTemplate points the browser straight at the CDN, where it arrives with no
 * User-Agent and no CORS grant, so every segment is refused. Rewrite those references
 * onto our signed segment proxy.
 *
 * The signature covers a *directory*, never a file name, so DASH template tokens
 * ($Number$, $RepresentationID$, $Time$, $Bandwidth$) are expanded by the player and
 * survive intact in the path appended after it.
 */
/**
 * Every `<BaseURL>` element in document order, with the directory it actually means.
 *
 * The value of a nested BaseURL is relative to its parent's, so the walk keeps a stack of
 * the base in force at each open element. Self-closing tags pop immediately; anything
 * unclosed is tolerated rather than throwing.
 */
function dashBaseElements(
  doc: string,
  manifestUrl: string,
): Array<{ start: number; end: number; raw: string; resolved: URL | null }> {
  const found: Array<{ start: number; end: number; raw: string; resolved: URL | null }> = [];
  const stack: Array<{ tag: string; base: string }> = [];
  let base = manifestUrl;
  const tagRe = /<(\/?)([A-Za-z][\w:.-]*)([^>]*?)(\/?)>/g;
  const lower = doc.toLowerCase();
  let m: RegExpExecArray | null;

  while ((m = tagRe.exec(doc))) {
    const closing = Boolean(m[1]);
    const name = m[2]!.toLowerCase();
    const selfClosing = Boolean(m[4]);

    if (closing) {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i]!.tag === name) {
          base = stack[i]!.base;
          stack.length = i;
          break;
        }
      }
      continue;
    }

    if (name === "baseurl") {
      const contentStart = tagRe.lastIndex;
      const closeAt = lower.indexOf("</baseurl>", contentStart);
      const contentEnd = closeAt < 0 ? doc.length : closeAt;
      const raw = doc.slice(contentStart, contentEnd).trim();
      let resolved: URL | null = null;
      try {
        // The base in force is the manifest URL at the top of the document, and the
        // enclosing BaseURL inside it.
        resolved = new URL(raw, base);
      } catch {
        resolved = null;
      }
      if (resolved) base = resolved.toString();
      found.push({
        start: m.index,
        end: closeAt < 0 ? doc.length : closeAt + "</baseurl>".length,
        raw,
        resolved,
      });
      tagRe.lastIndex = contentEnd;
      continue;
    }

    if (!selfClosing) stack.push({ tag: name, base });
  }

  return found;
}

/**
 * When ClearKeys are supplied for a DASH manifest that only advertises Widevine/PlayReady
 * `<ContentProtection>`, browsers without Widevine (or mobile/Firefox/Safari EME) reject the
 * manifest with Shaka Error 6001 (`REQUESTED_KEY_SYSTEM_CONFIG_UNAVAILABLE`).
 *
 * Mirror Android's `ClearKeyDashManifestParser`: strip proprietary Widevine/PlayReady blocks
 * and ensure a W3C ClearKey `<ContentProtection>` descriptor sits alongside `mp4protection`.
 */
function normalizeClearKeyMpd(mpdText: string, hasClearKeys: boolean): string {
  if (!hasClearKeys) return mpdText;
  let out = mpdText
    // Self-closing Widevine / PlayReady ContentProtection tags
    .replace(
      /<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*\/>/gi,
      "",
    )
    // Open/close Widevine / PlayReady ContentProtection blocks
    .replace(
      /<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*>[\s\S]*?<\/ContentProtection>/gi,
      "",
    );

  const hasClearKeyDescriptor = /urn:uuid:(?:e2719d58-a985-b3c9-781a-b030af78d30e|1077efec-c0b2-4d02-ace3-3c1e52e2fb4b)/i.test(
    out,
  );
  if (!hasClearKeyDescriptor && /mp4protection/i.test(out)) {
    out = out.replace(
      /(<ContentProtection\b[^>]*mp4protection[^>]*>[\s\S]*?<\/ContentProtection>|<ContentProtection\b[^>]*mp4protection[^>]*\/>)/gi,
      `$1<ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" value="ClearKey1.0"/><ContentProtection schemeIdUri="urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b"/>`,
    );
  }
  return out;
}

async function rewriteDashManifestWeb(
  mpdText: string,
  manifestUrl: string,
  mode: "mag" | "xtream",
  publicOrigin: string,
  secret: string,
  headers?: { ua?: string; ck?: string },
  hasClearKeys = false,
): Promise<string> {
  mpdText = normalizeClearKeyMpd(mpdText, hasClearKeys);
  const resolve = (raw: string): URL | null => {
    try {
      return new URL(raw, manifestUrl);
    } catch {
      return null;
    }
  };

  /** An absolute directory -> a proxied directory whose token carries its UA/cookie. */
  const proxyDir = async (dirUrl: string): Promise<string> => {
    const tok = await signSegmentToken(dirUrl, mode, secret, headers);
    // Root-relative: see the note in rewriteHlsManifestWeb. A manifest that points the
    // player at an origin the browser cannot reach is indistinguishable from a stream
    // with no video in it.
    return `/api/live/dashseg/${tok}/`;
  };

  /** An absolute file reference -> proxied path, keeping template tokens and query. */
  const proxyFile = async (url: URL): Promise<string> => {
    const cut = url.pathname.lastIndexOf("/");
    const dir = `${url.origin}${url.pathname.slice(0, cut + 1)}`;
    const file = url.pathname.slice(cut + 1);
    return `${await proxyDir(dir)}${file}${url.search}`;
  };

  let out = mpdText;

  // Attributes that may hold an absolute media reference. `media` and
  // `initialization` are the SegmentTemplate pair; the rest are SegmentBase/List.
  // Rewrite EVERY occurrence, and build replacements with a function.
  //
  // String.replace with a string pattern replaces only the first match, so when two
  // Representations share one absolute media template -- which is normal in DASH -- every
  // copy after the first kept pointing at the CDN and was fetched bare, where it is
  // refused. A function replacement also stops `$` in a token or file name being read as
  // a `$&` backreference.
  const attrs: Array<{ from: string; to: string }> = [];
  const seen = new Set<string>();
  for (const m of out.matchAll(/\b(media|initialization|sourceURL|index)="(https?:\/\/[^"]*)"/gi)) {
    const resolved = resolve(m[2]!);
    if (!resolved) continue;
    const key = `${m[1]!}=${m[2]!}`;
    if (seen.has(key)) continue;
    seen.add(key);
    attrs.push({ from: m[0]!, to: `${m[1]!}="${await proxyFile(resolved)}"` });
  }
  for (const { from, to } of attrs) {
    out = out.split(from).join(to);
  }

  // Every BaseURL, wherever it sits, must land on our proxy. Nested BaseURLs then
  // concatenate correctly because each one is rewritten to a fully-qualified URL.
  if (/<BaseURL>/i.test(out)) {
    // Resolve every BaseURL against its *parent*, not against the manifest.
    //
    // DASH nests them: an MPD-level `<BaseURL>143/</BaseURL>` and an AdaptationSet-level
    // `<BaseURL>video/</BaseURL>` mean `143/video/`. Resolving each one against the
    // manifest URL instead gives `video/` -- a directory that does not exist. A player
    // given a manifest whose every segment 404s reports it as having no usable streams.
    const elements = dashBaseElements(out, manifestUrl);
    const rewrites: Array<{ start: number; end: number; text: string }> = [];
    for (const el of elements) {
      const resolved = el.resolved;
      if (!resolved) continue;
      // A BaseURL without a trailing slash is a file-name *prefix*, not a directory.
      const isDir = resolved.pathname.endsWith("/");
      const cut = resolved.pathname.lastIndexOf("/");
      // The query is part of the credential, not decoration. Jio signs the directory --
      // `.../143/?__hdnea__=...` -- and dropping it left every segment fetch unsigned.
      const suffix = isDir ? "" : `${resolved.pathname.slice(cut + 1)}${resolved.search}`;
      const dir = `${resolved.origin}${resolved.pathname.slice(0, cut + 1)}${isDir ? resolved.search : ""}`;
      rewrites.push({
        start: el.start,
        end: el.end,
        text: `<BaseURL>${await proxyDir(dir)}${suffix}</BaseURL>`,
      });
    }
    // Apply back to front so earlier offsets stay valid.
    for (const r of rewrites.reverse()) out = out.slice(0, r.start) + r.text + out.slice(r.end);
  } else {
    // With no BaseURL the player resolves against the manifest URL, i.e. our
    // /api/live/stream/... route, where no segments exist. Pin it to the real one.
    const self = resolve(manifestUrl);
    if (self) {
      const cut = self.pathname.lastIndexOf("/");
      const dir = `${self.origin}${self.pathname.slice(0, cut + 1)}`;
      const proxied = await proxyDir(dir);
      out = out.replace(/<MPD\b[^>]*>/i, (tag) => `${tag}<BaseURL>${proxied}</BaseURL>`);
    }
  }

  return out;
}

function corsEmbedHeaders(extra: Record<string, string> = {}): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Allow-Headers": "Range, Content-Type, Accept, Origin",
    "Access-Control-Expose-Headers": "Content-Type, X-Stream-Container, X-Clear-Keys",
    "Content-Security-Policy": "frame-ancestors *;",
    ...extra,
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: corsEmbedHeaders({
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    }),
  });
}

/**
 * Open a JioTV media URL the way the v.4.9 app does, trying each header shape in turn.
 *
 * The playlist's `#EXTHTTP` cookie is a *coarse* token minted for the whole site, while
 * the address behind the redirect carries one minted for that single channel and path.
 * The CDN disagrees about which it wants per channel, and answers 450/451/403 to the
 * wrong one. The app therefore sends both and lets the CDN choose; a single attempt is
 * what made every channel fail here with "Upstream stream returned HTTP 450".
 */
/**
 * Open a JioTV channel the way the app opens it, and hand back the address that answered.
 *
 * The app does this in two stages and the distinction matters:
 *
 *  1. Ask the playlist's own address once. It redirects and mints a token for this channel.
 *  2. Now hold that minted address fixed and vary only the credentials, because the two
 *     credentials it can carry disagree by design. The playlist's `#EXTHTTP` cookie is a
 *     coarse site-wide token; the minted URL carries a token for one channel and path.
 *     A URL that already carries its own token, sent with the coarse cookie as well, is
 *     asked to honour two different credentials -- and the CDN answers 450.
 *
 * Our previous ladder restarted at the *advertised* address for every shape, so it never
 * tried the combination the CDN actually wants: the minted address, bare.
 */
async function fetchJioMedia(
  url: string,
  entry: {
    userAgent?: string | undefined;
    cookie?: string | undefined;
    preferHls?: boolean | undefined;
  },
): Promise<{ res: Response; finalUrl: string } | null> {
  const ua = entry.userAgent || JIO_UA_OVERRIDE;
  const browserUa =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

  // A probe must not become the delay; the app budgets eight seconds for the whole ladder.
  const deadline = Date.now() + 8_000;
  let last: { res: Response; finalUrl: string } | null = null;

  /** Follow the redirect chain by hand so the minted address survives. */
  const walk = async (
    from: string,
    headers: Record<string, string>,
  ): Promise<{ res: Response; finalUrl: string } | null> => {
    let currentUrl = from;
    try {
      for (let hop = 0; hop < 6; hop++) {
        if (Date.now() > deadline) return null;
        const res = await fetch(currentUrl, { headers: { Accept: "*/*", ...headers }, redirect: "manual" });
        if (res.status >= 300 && res.status < 400) {
          const loc = res.headers.get("location");
          if (!loc) return { res, finalUrl: currentUrl };
          currentUrl = new URL(loc, currentUrl).toString();
          continue;
        }
        return { res, finalUrl: currentUrl };
      }
    } catch {
      return null;
    }
    return null;
  };

  const cookie = entry.cookie ? { Cookie: entry.cookie } : {};

  // Stage 1: the playlist address, opened as an IPTV player opens it. Origin and Referer
  // are deliberately absent -- the app records that the redirector refuses a request
  // carrying them before it ever redirects.
  const opened =
    (await walk(url, { "User-Agent": ua, ...cookie })) ?? (await walk(url, { "User-Agent": ua }));
  if (!opened) return null;
  if (opened.res.ok) return opened;
  last = opened;

  // Stage 2: the minted address, credentials varied. Bare first when it carries its own
  // token -- that is the combination the coarse cookie was breaking.
  const signed = carriesOwnToken(opened.finalUrl);
  const shapes: Array<Record<string, string>> = signed
    ? [
        { "User-Agent": ua },
        { "User-Agent": ua, ...cookie },
        { "User-Agent": ua, ...cookie, Origin: JIO_SITE, Referer: `${JIO_SITE}/` },
        { "User-Agent": browserUa },
      ]
    : [
        { "User-Agent": ua, ...cookie },
        { "User-Agent": ua },
        { "User-Agent": ua, ...cookie, Origin: JIO_SITE, Referer: `${JIO_SITE}/` },
        { "User-Agent": browserUa },
      ];

  for (const headers of shapes) {
    if (Date.now() > deadline) break;
    const attempt = await walk(opened.finalUrl, headers);
    if (attempt?.res.ok) return attempt;
    if (attempt) last = attempt;
  }

  // Stage 3: back to the redirector, which re-mints. Last because it costs a redirect.
  if (opened.finalUrl !== url) {
    const remint = await walk(url, { "User-Agent": ua, ...cookie });
    if (remint?.res.ok) return remint;
    if (remint) last = remint;
  }

  return last;
}

/**
 * The last few upstream answers per source, newest first.
 *
 * "The stream started but no video arrived" is what a viewer sees; the thing worth knowing
 * is the status the CDN returned for the segment the player asked for. Without a record of
 * that, every diagnosis is a guess about somebody else's server. Bounded so a busy process
 * cannot grow without limit.
 */
interface SegmentAttempt {
  at: number;
  url: string;
  status: number;
  bytes: number;
  range: string | null;
}

const segmentLog = new Map<string, SegmentAttempt[]>();

/**
 * The last portal exchanges, newest first.
 *
 * "maxTV is failing" has several distinguishable causes -- portal unreachable, identity
 * refused, no token, catalogue empty, create_link refused, media refused -- and the player
 * shows the same blank rectangle for all of them. Each call is recorded with the status and
 * the portal's own words, which is the only way to tell them apart from a browser.
 */
interface PortalAttempt {
  at: number;
  action: string;
  address: string;
  status: number;
  said: string;
}

const portalTrace: PortalAttempt[] = [];
const PORTAL_TRACE_LIMIT = 30;

function recordPortal(attempt: PortalAttempt): void {
  portalTrace.unshift(attempt);
  if (portalTrace.length > PORTAL_TRACE_LIMIT) portalTrace.length = PORTAL_TRACE_LIMIT;
}

/** The portal's own refusal, or a short excerpt of what it sent instead. */
function portalSaid(body: string): string {
  const spoken = stalkerError(body);
  if (spoken) return spoken.slice(0, 200);
  const head = body.trim().replace(/\s+/g, " ").slice(0, 160);
  return head || "(empty body)";
}
const __hdnea_re = /__hdnea__=[^&"'\s]*/g;
const SEGMENT_LOG_LIMIT = 24;

function recordSegment(key: string, attempt: SegmentAttempt): void {
  const list = segmentLog.get(key) ?? [];
  list.unshift(attempt);
  if (list.length > SEGMENT_LOG_LIMIT) list.length = SEGMENT_LOG_LIMIT;
  segmentLog.set(key, list);
  if (segmentLog.size > 200) {
    // Oldest key out; the map only ever holds recently played channels.
    const oldest = segmentLog.keys().next().value;
    if (oldest !== undefined && oldest !== key) segmentLog.delete(oldest);
  }
}

/** Strip the signed query so a token never ends up in a diagnostic, but keep the shape. */
function diagnosticUrl(raw: string): string {
  try {
    const u = new URL(raw);
    return `${u.origin}${u.pathname}${u.search ? "?…" : ""}`;
  } catch {
    return raw.split("?")[0]!;
  }
}

/**
 * Refuse a segment *and* record it.
 *
 * These requests never reach proxyMediaStream, so without this a segment the player did
 * ask for was absent from the log and the diagnostic concluded "the player requested no
 * segments at all" -- the opposite of what happened.
 */
function rejectedSegment(kind: string, why: string, status: number): Response {
  recordSegment(`reject:${kind}`, {
    at: Date.now(),
    url: why,
    status,
    bytes: 0,
    range: null,
  });
  return jsonResponse(status, { ok: false, error: "Invalid segment token" });
}

/**
 * Short-lived single-use handoff cache from `?probe=1` container/key detection to the player's
 * initial manifest request (Shaka `.mpd`, HLS.js `.m3u8`, or `mpegts.js` `.ts`).
 *
 * Without this, probing `/api/live/stream/<id>.ts` and opening `/api/live/stream/<id>.m3u8`
 * 10ms later called Stalker `create_link` twice in milliseconds — burning single-use `play_token`s
 * and tripping Stalker's 1-connection-per-MAC limit.
 *
 * Each entry is consumed once on the very next non-probe request (within 8s), so subsequent live
 * sliding-window manifest refreshes always hit upstream for fresh segments.
 */
interface ProbeHandoffEntry {
  at: number;
  kind: "dash" | "hls" | "mpegts";
  body?: string;
  headers?: Record<string, string>;
  resolvedUrl?: string;
}
const streamHandoffCache = new Map<string, ProbeHandoffEntry>();
const STREAM_HANDOFF_TTL_MS = 8000;

function takeStreamHandoff(channelId: string): ProbeHandoffEntry | null {
  const hit = streamHandoffCache.get(channelId);
  if (!hit) return null;
  streamHandoffCache.delete(channelId);
  if (Date.now() - hit.at > STREAM_HANDOFF_TTL_MS) return null;
  return hit;
}

function saveStreamHandoff(channelId: string | undefined, entry: ProbeHandoffEntry): void {
  if (!channelId) return;
  if (streamHandoffCache.size > 128) {
    const cutoff = Date.now() - STREAM_HANDOFF_TTL_MS;
    for (const [k, v] of streamHandoffCache.entries()) {
      if (v.at < cutoff) streamHandoffCache.delete(k);
    }
  }
  streamHandoffCache.set(channelId, entry);
}

async function proxyMediaStream(
  upstreamUrl: string,
  mode: "mag" | "xtream",
  publicOrigin: string,
  secret: string,
  overrideHeaders?: Record<string, string>,
  clearKeys?: ClearKeyPair[],
  range?: string | null,
  probeOpts?: { isProbe?: boolean; handoffId?: string },
): Promise<Response> {
  const headers: Record<string, string> = overrideHeaders
    ? { Accept: "*/*", ...overrideHeaders }
    : mode === "xtream"
      ? { "User-Agent": XTREAM_USER_AGENT, Accept: "*/*", "Icy-MetaData": "1" }
      : { "User-Agent": MAG_USER_AGENT, Accept: "*/*" };

  // Shaka asks for byte ranges on MP4 segments. Dropping the range silently returned the
  // whole file, which some CDNs answer with 405 because the request is malformed for a
  // ranged resource.
  if (range) headers["Range"] = range;

  let currentUrl = upstreamUrl;
  let upRes: Response | null = null;
  for (let hop = 0; hop < 6; hop++) {
    // Applied per hop, and after any redirect, because the address that carries its own
    // `__hdnea__` is usually the one at the *end* of a redirect chain. Sending the
    // playlist's coarse cookie alongside it asks the CDN to honour two credentials at
    // once, and Jio answers 450 rather than choosing. This belongs here rather than in
    // each caller so no path can forget it.
    const hopHeaders = { ...headers };
    if (carriesOwnToken(currentUrl)) delete hopHeaders["Cookie"];
    const res = await fetch(currentUrl, { headers: hopHeaders, redirect: "manual" });
    if (res.status >= 300 && res.status < 400) {
      const loc = res.headers.get("location");
      if (loc) {
        currentUrl = new URL(loc, currentUrl).toString();
        continue;
      }
    }
    upRes = res;
    break;
  }

  if (!upRes || !upRes.ok || !upRes.body) {
    // The refusal is the useful fact, so keep it where a diagnostic can find it.
    recordSegment(mode, {
      at: Date.now(),
      url: diagnosticUrl(currentUrl),
      status: upRes?.status ?? 0,
      bytes: 0,
      range: range ?? null,
    });
    return jsonResponse(upRes?.status === 404 ? 404 : 502, {
      ok: false,
      error: `Upstream stream returned HTTP ${upRes?.status ?? 502}`,
    });
  }

  const reader = upRes.body.getReader();
  const first = await reader.read();
  if (first.done || !first.value) {
    return jsonResponse(502, { ok: false, error: "Upstream stream closed immediately" });
  }

  const ctype = (upRes.headers.get("content-type") || "").toLowerCase();
  const headText = new TextDecoder().decode(first.value.subarray(0, 64)).trimStart();
  const pathLower = currentUrl.split("?")[0]!.toLowerCase();
  const isHls = ctype.includes("mpegurl") || pathLower.endsWith(".m3u8") || headText.startsWith("#EXTM3U");
  const isDash = ctype.includes("dash+xml") || pathLower.endsWith(".mpd") || /^<\?xml|^<MPD/i.test(headText);

  if (isDash) {
    // An MPD is small; buffer it and hand it back whole so the player can read it.
    const chunks: Uint8Array[] = [first.value];
    let total = first.value.byteLength;
    while (total < 4 * 1024 * 1024) {
      const next = await reader.read();
      if (next.done || !next.value) break;
      chunks.push(next.value);
      total += next.value.byteLength;
    }
    const merged = new Uint8Array(total);
    let offset = 0;
    for (const c of chunks) {
      merged.set(c, offset);
      offset += c.byteLength;
    }
    const mpdText = new TextDecoder().decode(merged);
    // A ClearKey licence must name the key id the manifest actually uses. When the key
    // document and the MPD disagree, the session decrypts nothing and the viewer sees a
    // black screen with no error at all. A single advertised key is therefore relabelled
    // onto the manifest's key id rather than dropped, exactly as the v.4.9 app does.
    const matched = matchClearKeys(clearKeys ?? [], defaultKids(mpdText));
    const keyHeader = matched.length
      ? { "X-Clear-Keys": matched.map((k) => `${k.kid}:${k.k}`).join(",") }
      : {};
    // Hand over a manifest whose every reference points back at us. Untouched, the
    // player would walk straight out to the CDN and lose the UA that keeps it alive.
    const rewritten = await rewriteDashManifestWeb(
      mpdText,
      currentUrl,
      mode,
      publicOrigin,
      secret,
      tokenHeadersFrom(overrideHeaders, currentUrl),
      matched.length > 0,
    );
    const respHeaders = corsEmbedHeaders({
      "Content-Type": "application/dash+xml; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Stream-Container": "dash",
      ...keyHeader,
    });
    if (probeOpts?.isProbe && probeOpts.handoffId) {
      saveStreamHandoff(probeOpts.handoffId, {
        at: Date.now(),
        kind: "dash",
        body: rewritten,
        headers: respHeaders,
      });
    }
    return new Response(rewritten, {
      status: 200,
      headers: respHeaders,
    });
  }

  if (isHls) {
    const chunks: Uint8Array[] = [first.value];
    let total = first.value.byteLength;
    while (total < 2 * 1024 * 1024) {
      const next = await reader.read();
      if (next.done || !next.value) break;
      chunks.push(next.value);
      total += next.value.byteLength;
    }
    const merged = new Uint8Array(total);
    let offset = 0;
    for (const c of chunks) {
      merged.set(c, offset);
      offset += c.byteLength;
    }
    const manifestText = new TextDecoder().decode(merged);
    const rewritten = await rewriteHlsManifestWeb(
      manifestText,
      currentUrl,
      mode,
      publicOrigin,
      secret,
      tokenHeadersFrom(overrideHeaders, currentUrl),
    );
    const respHeaders = corsEmbedHeaders({
      "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Stream-Container": "hls",
    });
    if (probeOpts?.isProbe && probeOpts.handoffId) {
      saveStreamHandoff(probeOpts.handoffId, {
        at: Date.now(),
        kind: "hls",
        body: rewritten,
        headers: respHeaders,
      });
    }
    return new Response(rewritten, {
      status: 200,
      headers: respHeaders,
    });
  }

  // If this was only a container probe (`?probe=1`) and upstream is raw MPEG-TS, close the
  // upstream socket immediately and cache the resolved URL so mpegts.js opens the sole active stream.
  if (probeOpts?.isProbe) {
    await reader.cancel().catch(() => undefined);
    if (probeOpts.handoffId) {
      saveStreamHandoff(probeOpts.handoffId, {
        at: Date.now(),
        kind: "mpegts",
        resolvedUrl: currentUrl,
      });
    }
    return new Response(null, {
      status: 200,
      headers: corsEmbedHeaders({
        "Content-Type": "video/mp2t",
        "Cache-Control": "no-store",
        "X-Stream-Container": "mpegts",
      }),
    });
  }

  recordSegment(mode, {
    at: Date.now(),
    url: diagnosticUrl(currentUrl),
    status: upRes.status,
    bytes: first.value.byteLength,
    range: range ?? null,
  });

  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(first.value!);
    },
    async pull(controller) {
      try {
        const chunk = await reader.read();
        if (chunk.done) controller.close();
        else if (chunk.value) controller.enqueue(chunk.value);
      } catch {
        controller.close();
      }
    },
    cancel() {
      void reader.cancel();
    },
  });

  return new Response(stream, {
    status: 200,
    headers: corsEmbedHeaders({
      "Content-Type": "video/mp2t",
      "Cache-Control": "no-store",
      "X-Stream-Container": "mpegts",
    }),
  });
}

function renderStandaloneEmbedHtml(ch: LiveChannelDto): string {
  const boot = JSON.stringify(ch).replace(/</g, "\\u003c");
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <meta name="referrer" content="no-referrer" />
  <title>${ch.name.replace(/[<>&"]/g, "")} · STREAMR Live</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body { width: 100%; height: 100%; background: #000; color: #f6f7fc; font-family: system-ui, sans-serif; overflow: hidden; }
    .stage { position: relative; width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; background: #05040b; }
    video { width: 100%; height: 100%; object-fit: contain; background: #000; }
    .top { position: absolute; top: 0; inset-inline: 0; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; background: linear-gradient(180deg, rgba(0,0,0,.78), transparent); pointer-events: none; }
    .live { background: #ff2e63; color: #fff; font-size: 10px; font-weight: 800; letter-spacing: .14em; padding: 3px 8px; border-radius: 999px; margin-right: 8px; }
    .bot { position: absolute; bottom: 0; inset-inline: 0; padding: 12px 16px; display: flex; justify-content: space-between; gap: 8px; background: linear-gradient(0deg, rgba(0,0,0,.85), transparent); }
    button, select { background: rgba(16,14,30,.85); color: #fff; border: 1px solid rgba(255,255,255,.18); border-radius: 999px; padding: 6px 12px; font-size: 12px; font-weight: 600; cursor: pointer; }
    button:hover, select:hover { border-color: #00ecf7; }
    .ov { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; background: rgba(5,4,11,.74); text-align: center; padding: 20px; }
    .ov.hide { display: none; }
  </style>
</head>
<body>
  <div class="stage" id="stage">
    <video id="v" playsinline autoplay></video>
    <div class="top">
      <div><span class="live">LIVE</span><strong id="nm"></strong></div>
      <span style="font-size:11px;opacity:.7;letter-spacing:.12em;">STREAMR · maxTV</span>
    </div>
    <div class="ov" id="ov"><div id="st" style="font-size:13px;color:#b4bbd0;">Connecting to live stream…</div><button id="rt" style="display:none;">Reload Stream</button></div>
    <div class="bot">
      <div style="display:flex;gap:8px;flex-wrap:wrap;">
        <button id="pl">Pause</button>
        <button id="mu">Mute</button>
        <button id="rl">Reload</button>
        <select id="au" style="display:none;"></select>
      </div>
      <div><button id="fs">Fullscreen</button></div>
    </div>
  </div>
  <script src="/vendor/mpegts.js"></script>
  <script src="/vendor/hls.min.js"></script>
  <script src="/vendor/shaka-player.compiled.js"></script>
  <script>
    const ch = ${boot};
    const v = document.getElementById("v"), ov = document.getElementById("ov"), st = document.getElementById("st"), rt = document.getElementById("rt"), au = document.getElementById("au"), mu = document.getElementById("mu"), pl = document.getElementById("pl");
    document.getElementById("nm").textContent = ch.name;
    if (new URLSearchParams(location.search).get("muted") === "1") { v.muted = true; mu.textContent = "Unmute"; }
    let mp = null, hp = null, sp = null;
    function clean() {
      if (mp) { try { mp.unload(); mp.detachMediaElement(); mp.destroy(); } catch {} mp = null; }
      if (hp) { try { hp.destroy(); } catch {} hp = null; }
      if (sp) { try { sp.destroy(); } catch {} sp = null; }
      au.style.display = "none"; au.innerHTML = "";
    }
    function sUrl(ext, probe) { return "/api/live/stream/" + encodeURIComponent(ch.id) + ext + "?_t=" + Date.now() + (probe ? "&probe=1" : ""); }
    function hdneaOf(s) {
      var m = /(?:^|[?;&\s])(__hdnea__=[^;\s&"'<>]+)/.exec(String(s == null ? "" : s));
      return m ? m[1] : null;
    }
    function withTok(url, tok, replace) {
      var h = url.indexOf("#"), frag = h >= 0 ? url.slice(h) : "", base = h >= 0 ? url.slice(0, h) : url;
      var q = base.indexOf("?"), params = (q >= 0 ? base.slice(q + 1) : "").split("&").filter(function (p) { return p.length > 0; });
      var has = params.some(function (p) { return p.split("=", 1)[0] === "__hdnea__"; });
      if (!replace && has) return url;
      var kept = params.filter(function (p) { return p.split("=", 1)[0] !== "__hdnea__"; });
      kept.push(tok);
      return (q >= 0 ? base.slice(0, q) : base) + "?" + kept.join("&") + frag;
    }
    function sameHost(a, b) {
      try { return new URL(a).hostname.toLowerCase() === new URL(b).hostname.toLowerCase(); } catch (e) { return false; }
    }
    function startHls(url, tok, host) {
      clean();
      if (window.Hls && Hls.isSupported()) {
        var activeTok = tok || "";
        hp = new Hls({ lowLatencyMode: true, xhrSetup: function (xhr, u) {
          try {
            xhr.addEventListener("load", function () {
              try {
                var fin = xhr.responseURL || "";
                if (fin && /\.m3u8(?:[?#]|$)/i.test(u) && host && sameHost(fin, host)) {
                  var learned = hdneaOf(fin);
                  if (learned) activeTok = learned;
                }
              } catch (e) {}
            });
            if (!activeTok || !host || u.indexOf("allinonereborn") >= 0 || !sameHost(u, host)) return;
            var signed = withTok(u, activeTok, false);
            if (signed !== u) xhr.open("GET", signed, true);
          } catch (e) {}
        } });
        hp.loadSource(url); hp.attachMedia(v);
        hp.on(Hls.Events.MANIFEST_PARSED, () => {
          const tr = hp.audioTracks || [];
          if (tr.length > 1) {
            let hi = -1;
            tr.forEach((t, i) => {
              const o = document.createElement("option");
              o.value = i; o.textContent = "Audio: " + ([t.name, t.lang].filter(Boolean).join(" · ") || ("Track " + (i + 1)));
              if (hi < 0 && /hin|^hi$/i.test((t.name || "") + " " + (t.lang || ""))) hi = i;
              au.appendChild(o);
            });
            if (hi >= 0) hp.audioTrack = hi;
            au.value = hp.audioTrack; au.style.display = "inline-block";
            au.onchange = () => { hp.audioTrack = Number(au.value); };
          }
          v.play().catch(() => { v.muted = true; mu.textContent = "Unmute"; v.play().catch(() => {}); });
        });
      } else { v.src = url; v.play().catch(() => {}); }
    }
    function hexKey(v) {
      v = String(v == null ? "" : v).trim();
      if (!v) return null;
      if (/^[0-9a-fA-F]{32}$/.test(v)) return v.toLowerCase();
      var n = v.replace(/-/g, "+").replace(/_/g, "/").replace(/=+$/, "");
      if (!/^[A-Za-z0-9+/]+$/.test(n)) return null;
      var p = n + "===".slice((n.length + 3) % 4);
      try {
        var b = atob(p);
        if (b.length !== 16) return null;
        var h = "";
        for (var i = 0; i < b.length; i++) h += b.charCodeAt(i).toString(16).padStart(2, "0");
        return h;
      } catch (e) { return null; }
    }
    function startDash(url) {
      clean();
      if (!window.shaka) { st.textContent = "DASH playback engine did not load."; rt.style.display = "inline-block"; ov.classList.remove("hide"); return; }
      shaka.polyfill.installAll();
      if (!shaka.Player.isBrowserSupported()) { st.textContent = "This browser cannot play protected DASH streams."; rt.style.display = "inline-block"; ov.classList.remove("hide"); return; }
      if (shaka.log && shaka.LogLevel) shaka.log.setLevel(shaka.LogLevel.NONE);
      sp = new shaka.Player();
      var ck = {};
      ((ch.drm && ch.drm.keys) || []).forEach(function (pair) {
        var kid = hexKey(pair.kid), k = hexKey(pair.k);
        if (kid && k) ck[kid] = k;
      });
      sp.configure({
        drm: Object.keys(ck).length ? { clearKeys: ck } : {},
        streaming: { lowLatencyMode: true, rebufferingGoal: 2, bufferingGoal: 20, retryParameters: { maxAttempts: 4, baseDelay: 500, backoffFactor: 2, timeout: 20000 } },
        manifest: { retryParameters: { maxAttempts: 3, baseDelay: 500, backoffFactor: 2, timeout: 20000 } }
      });
      sp.addEventListener("error", function () {
        st.textContent = Object.keys(ck).length ? "This DRM channel was refused. Its ClearKey document may have rotated." : "This channel is DRM protected and no key was provided.";
        rt.style.display = "inline-block"; ov.classList.remove("hide");
      });
      Promise.resolve()
        .then(function () { return sp.attach(v); })
        .then(function () { return sp.load(url); })
        .then(function () {
          var langs = sp.getAudioLanguages() || [];
          if (langs.length > 1) {
            Array.prototype.forEach.call(langs, function (name, i) {
              var o = document.createElement("option");
              o.value = name; o.textContent = "Audio: " + name;
              au.appendChild(o);
            });
            var hi = langs.filter(function (l) { return /^hi/i.test(l); })[0];
            if (hi) sp.selectAudioLanguage(hi);
            au.value = sp.getAudioLanguage() || langs[0];
            au.style.display = "inline-block";
            au.onchange = function () { sp.selectAudioLanguage(au.value); };
          }
          v.play().catch(function () { v.muted = true; mu.textContent = "Unmute"; v.play().catch(function () {}); });
        })
        .catch(function () { st.textContent = "DASH playback failed to start."; rt.style.display = "inline-block"; ov.classList.remove("hide"); });
    }
    function startTs(url, fallback) {
      clean();
      if (!window.mpegts || !mpegts.isSupported()) return startHls(sUrl(".m3u8"));
      mp = mpegts.createPlayer({ type: "mpegts", isLive: true, url }, { enableWorker: true, enableStashBuffer: false, liveBufferLatencyChasing: true });
      mp.attachMediaElement(v); mp.load();
      Promise.resolve(mp.play()).catch(() => { v.muted = true; mu.textContent = "Unmute"; v.play().catch(() => {}); });
      mp.on(mpegts.Events.ERROR, () => { if (fallback) startHls(sUrl(".m3u8")); else { st.textContent = "Stream interrupted."; rt.style.display = "inline-block"; ov.classList.remove("hide"); } });
    }
    function waitDirectFrames(ms) {
      return new Promise(function (resolve) {
        const t0 = Date.now();
        (function poll() {
          if ((v.videoWidth > 0 && v.videoHeight > 0) || (!v.paused && v.currentTime > 0.3)) return resolve(true);
          if (Date.now() - t0 > ms) return resolve(false);
          setTimeout(poll, 250);
        })();
      });
    }
    async function tryDirectPlay() {
      try {
        const dc = new AbortController(); const dtm = setTimeout(function () { dc.abort(); }, 7000);
        let cands = [];
        try {
          const dr = await fetch("/api/live/direct-config?id=" + encodeURIComponent(ch.id) + "&name=" + encodeURIComponent(ch.name || ""), { signal: dc.signal });
          if (!dr.ok) return false;
          const dd = await dr.json().catch(() => ({}));
          cands = Array.isArray(dd.candidates) ? dd.candidates : [];
        } catch { return false; } finally { clearTimeout(dtm); }
        if (!cands.length || !window.shaka) return false;
        shaka.polyfill.installAll();
        if (!shaka.Player.isBrowserSupported()) return false;
        if (shaka.log && shaka.LogLevel) shaka.log.setLevel(shaka.LogLevel.NONE);
        const passDeadline = Date.now() + 40000;
        for (let idx = 0; idx < cands.length; idx++) {
          if (Date.now() > passDeadline) return false;
          const cand = cands[idx];
          st.textContent = "Trying " + (cand.label || "direct server") + "…";
          if (cand.container === "hls") {
            startHls(cand.cookie ? withTok(cand.url, cand.cookie, true) : cand.url, cand.cookie, cand.url);
            if (await waitDirectFrames(7000)) return true;
            continue;
          }
          const attempts = cand.cookie ? [cand.cookie, ""] : [""];
          for (let a = 0; a < attempts.length; a++) {
            clean();
            sp = new shaka.Player();
            const activeCookie = attempts[a];
            let activeTok = activeCookie;
            const ck = cand.clearKeys || {};
            sp.configure({
              drm: Object.keys(ck).length ? { clearKeys: ck } : {},
              streaming: { lowLatencyMode: true, rebufferingGoal: 2, bufferingGoal: 20, retryParameters: { maxAttempts: 2, baseDelay: 300, backoffFactor: 2, timeout: 7000 } },
              manifest: { retryParameters: { maxAttempts: 1, baseDelay: 300, backoffFactor: 2, timeout: 7000 } }
            });
            const net = sp.getNetworkingEngine && sp.getNetworkingEngine();
            if (net) {
              net.registerRequestFilter(function (type, req) {
                if ((type !== 0 && type !== 1) || !req.uris || !req.uris[0]) return;
                const u = req.uris[0];
                if (u.includes("allinonereborn") || !sameHost(u, cand.url)) return;
                if (!activeTok) return;
                req.uris[0] = withTok(u, activeTok, false);
              });
            }
            if (net) {
              net.registerResponseFilter(function (type, resp) {
                if (type === 0 && resp.uri) {
                  try {
                    if (!resp.uri.includes("allinonereborn") && sameHost(resp.uri, cand.url)) {
                      const learned = hdneaOf(resp.uri);
                      if (learned) activeTok = learned;
                    }
                  } catch (e) {}
                }
                if (type !== 0 || !resp.data || !Object.keys(ck).length) return;
                try {
                  let xml = new TextDecoder().decode(resp.data);
                  if (!/<MPD[\s>]/i.test(xml)) return;
                  const kids = Array.from(xml.matchAll(/default_KID\s*=\s*"([^"]+)"/gi)).map(function (m) { return m[1].replace(/-/g, "").toLowerCase(); });
                  const ckEntries = Object.entries(ck);
                  if (ckEntries.length === 1 && kids.length > 0 && !ck[kids[0]]) {
                    const extra = Object.assign({}, ck);
                    kids.forEach(function (kHex) { extra[kHex] = ckEntries[0][1]; });
                    sp.configure({ drm: { clearKeys: extra } });
                  }
                  xml = xml.replace(/<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*>[\s\S]*?<\/ContentProtection>/gi, "");
                  xml = xml.replace(/<ContentProtection\b[^>]*schemeIdUri="urn:uuid:(?:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed|9a04f079-9840-4286-ab92-e65be0885f95)"[^>]*\/>/gi, "");
                  resp.data = new TextEncoder().encode(xml).buffer;
                } catch {}
              });
            }
            try {
              await sp.attach(v);
              const rootUrl = activeCookie ? withTok(cand.url, activeCookie, true) : cand.url;
              await Promise.race([sp.load(rootUrl), new Promise(function (_, rej) { setTimeout(function () { rej(new Error("direct-timeout")); }, 8000); })]);
              v.play().catch(function () { v.muted = true; mu.textContent = "Unmute"; v.play().catch(function () {}); });
              // A manifest that loads but never renders must fail over, not trap the embed.
              if (await waitDirectFrames(6000)) return true;
            } catch {}
          }
        }
      } catch {}
      return false;
    }
    async function boot() {
      clean(); ov.classList.remove("hide"); rt.style.display = "none"; st.textContent = "Connecting to " + ch.name + "…";
      armWatchdog();
      if (ch.source === "jio" || ch.source === "tgtv" || /^(?:jio|tg):/i.test(String(ch.id || ""))) {
        if (await tryDirectPlay()) return;
      }
      // Ask for the container the catalogue advertised. Probing ".ts" unconditionally
      // made every DASH channel look like MPEG-TS, so the embed could never play one.
      const wantDash = String(ch.container || "").toLowerCase() === "dash";
      const probeExt = wantDash ? ".mpd" : ".ts";
      const c = new AbortController(); const tm = setTimeout(() => c.abort(), 8000);
      try {
        const r = await fetch(sUrl(probeExt, true), { signal: c.signal }); clearTimeout(tm);
        if (!r.ok) {
          const j = await r.json().catch(() => ({})); c.abort();
          if (await tryDirectPlay()) return;
          if (ch.directUrl) { startHls(ch.directUrl); return; }
          st.textContent = j.error || ("HTTP " + r.status); rt.style.display = "inline-block"; return;
        }
        const kind = (r.headers.get("x-stream-container") || "").toLowerCase(); c.abort();
        if (kind === "dash") startDash(sUrl(".mpd"));
        else if (kind === "hls") startHls(sUrl(".m3u8"));
        else startTs(sUrl(".ts"), true);
      } catch { clearTimeout(tm); if (await tryDirectPlay()) return; if (wantDash) startDash(sUrl(".mpd")); else startTs(sUrl(".ts"), true); }
    }
    function hideIfPlaying() {
      if ((v.videoWidth > 0 && v.videoHeight > 0 && !v.paused) || (!v.paused && v.currentTime > 0.6)) {
        ov.classList.add("hide");
      }
    }
    v.onplaying = () => {
      pl.textContent = "Pause";
      hideIfPlaying();
    };
    v.onloadeddata = hideIfPlaying;
    v.onresize = hideIfPlaying;
    v.ontimeupdate = hideIfPlaying;
    let deadTimer = null;
    function armWatchdog() {
      clearTimeout(deadTimer);
      deadTimer = setTimeout(() => {
        if (v.videoWidth > 0 && v.videoHeight > 0) return;
        st.textContent = "The stream started but no video arrived. Segments were refused or the format is unsupported.";
        rt.style.display = "inline-block"; ov.classList.remove("hide");
      }, 25000);
    }
    v.onpause = () => { pl.textContent = "Play"; };
    pl.onclick = () => v.paused ? v.play() : v.pause();
    mu.onclick = () => { v.muted = !v.muted; mu.textContent = v.muted ? "Unmute" : "Mute"; };
    document.getElementById("rl").onclick = boot; rt.onclick = boot;
    document.getElementById("fs").onclick = () => document.fullscreenElement ? document.exitFullscreen() : document.getElementById("stage").requestFullscreen();
    boot();
  </script>
</body>
</html>`;
}

function renderWidgetSdkJs(): string {
  return `(function(){
  const s = document.currentScript || document.querySelector('script[src*="embed-sdk.js"]');
  if (!s) return;
  const origin = new URL(s.src, location.href).origin;
  const mount = document.querySelector(s.getAttribute("data-target") || "#og-live-tv");
  const source = s.getAttribute("data-source") || "maxtv";
  const filter = s.getAttribute("data-filter") || "hindi";
  if (!mount) return;
  mount.innerHTML = '<div style="background:#080711;border:1px solid rgba(255,255,255,.1);border-radius:16px;overflow:hidden;color:#fff;font-family:system-ui,sans-serif;"><div style="aspect-ratio:16/9;background:#000;"><iframe id="sr-f" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen style="width:100%;height:100%;border:0;"></iframe></div><div style="padding:14px;"><input id="sr-q" type="search" placeholder="Search Live TV…" style="width:100%;margin-bottom:10px;background:#121022;border:1px solid rgba(255,255,255,.15);color:#fff;padding:8px 14px;border-radius:999px;font-size:13px;"/><div id="sr-g" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;max-height:260px;overflow:auto;"></div></div></div>';
  const f = mount.querySelector("#sr-f"), q = mount.querySelector("#sr-q"), g = mount.querySelector("#sr-g");
  let all = [], cur = "";
  function pick(c) { cur = c.id; f.src = origin + "/embed/" + encodeURIComponent(c.id); draw(q.value); }
  function draw(txt) {
    const t = (txt || "").toLowerCase();
    g.innerHTML = "";
    all.filter(c => !t || (c.name + " " + c.category).toLowerCase().includes(t)).slice(0, 120).forEach(c => {
      const b = document.createElement("button");
      b.textContent = c.name;
      b.style.cssText = "text-align:left;padding:8px 10px;border-radius:10px;cursor:pointer;border:1px solid " + (c.id === cur ? "#00ecf7" : "rgba(255,255,255,.1)") + ";background:#121022;color:#fff;font-size:12px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;";
      b.onclick = () => pick(c);
      g.appendChild(b);
    });
  }
  fetch(origin + "/api/live/channels?source=" + encodeURIComponent(source) + "&filter=" + encodeURIComponent(filter))
    .then(r => r.json()).then(d => { all = d.channels || []; if (all[0]) pick(all[0]); });
  q.oninput = () => draw(q.value);
})();`;
}

function toDto(ch: LiveChannelItem, publicOrigin: string): LiveChannelDto {
  const enc = encodeURIComponent(ch.id);
  const embedUrl = `${publicOrigin}/embed/${enc}`;
  const isDirectHttpsHls =
    ch.source === "tgtv" &&
    ch.mediaType === "hls" &&
    typeof ch.cmd === "string" &&
    /^https:\/\//i.test(ch.cmd) &&
    !ch.cookie &&
    !ch.drm;
  return {
    id: ch.id,
    source: ch.source,
    name: ch.name,
    category: displayCategoryName(ch.category),
    rawCategory: ch.category,
    logo: ch.logo,
    embedUrl,
    iframeCode: `<iframe src="${embedUrl}" width="100%" height="100%" frameborder="0" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>`,
    // The extension follows the container so the player probes the right engine.
    streamUrl: `${publicOrigin}/api/live/stream/${enc}.${ch.mediaType === "dash" ? "mpd" : ch.mediaType === "hls" ? "m3u8" : "ts"}`,
    ...(isDirectHttpsHls ? { directUrl: ch.cmd } : {}),
    ...(ch.drm && ch.drm.keys.length ? { drm: { type: "clearkey" as const, keys: ch.drm.keys } } : {}),
    ...(ch.mediaType ? { container: ch.mediaType } : {}),
  };
}

/** A host the browser can never reach, so it must never appear in a URL we hand out. */
function isUnreachableHost(hostname: string): boolean {
  const h = hostname.replace(/^\[|\]$/g, "").toLowerCase();
  return (
    h === "localhost" ||
    h === "0.0.0.0" ||
    h === "127.0.0.1" ||
    h === "::1" ||
    h.endsWith(".internal") ||
    h.endsWith(".local")
  );
}

/**
 * The origin the *browser* used, which is not always the origin this function was called
 * with. A server behind a proxy frequently sees its own internal address -- `localhost`,
 * a container IP, `0.0.0.0` -- while the viewer is on a public domain.
 *
 * This matters because embed URLs, playlist entries and M3U exports are handed to other
 * machines. Getting it wrong produces links to a host only the server can reach.
 */
function publicOriginFor(request: Request, url: URL): string {
  const forwardedProto = request.headers.get("x-forwarded-proto")?.split(",")[0]?.trim();
  const forwardedHost = request.headers.get("x-forwarded-host")?.split(",")[0]?.trim();
  const hostHeader = request.headers.get("host")?.trim();

  const candidate = forwardedHost || hostHeader || url.host;
  const hostname = candidate.replace(/:\d+$/, "");

  // Keep the received origin when the header names a host the browser cannot reach and
  // the received one is reachable.
  const chosen = isUnreachableHost(hostname) && !isUnreachableHost(url.hostname.replace(/:\d+$/, ""))
    ? url.host
    : candidate;

  const proto =
    forwardedProto ||
    (url.protocol === "https:" ? "https" : isUnreachableHost(hostname) ? "http" : "https");

  return `${proto}://${chosen}`;
}

// ---------- Entry Point Called from src/server.ts ----------

export async function maybeHandleLiveRequest(request: Request, env: unknown): Promise<Response | null> {
  const url = new URL(request.url);
  const path = url.pathname;

  const isHandled =
    path.startsWith("/api/live/") ||
    path.startsWith("/embed/") ||
    path === "/embed-sdk.js";

  if (!isHandled) return null;

  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsEmbedHeaders() });
  }

  const cfg = getConfig(env, request);
  const publicOrigin = publicOriginFor(request, url);

  if (path === "/embed-sdk.js") {
    return new Response(renderWidgetSdkJs(), {
      status: 200,
      headers: corsEmbedHeaders({ "Content-Type": "application/javascript; charset=utf-8" }),
    });
  }

  if (path === "/api/live/direct-config") {
    const rawId = (url.searchParams.get("id") || "").trim();
    let channelName = (url.searchParams.get("name") || "").trim();
    const force = url.searchParams.get("refresh") === "1";

    if (!channelName && rawId) {
      const pool = await getChannelsBySource(cfg, "all", false);
      const hit = pool.find((c) => c.id === rawId || c.rawId === rawId);
      if (hit) channelName = hit.name;
    }

    const resolved = await resolveDirectPlayCandidates(cfg, rawId, channelName, force);
    return jsonResponse(200, {
      ok: true,
      channelId: rawId,
      matchedId: resolved.matchedId,
      matchedName: resolved.matchedName,
      candidates: resolved.candidates,
      playlistMint: resolved.playlistMint,
    });
  }

  if (path === "/api/live/channels") {
    const source = url.searchParams.get("source") || "maxtv";
    const filter = url.searchParams.get("filter") || "hindi";
    const q = (url.searchParams.get("q") || "").trim().toLowerCase();
    const category = (url.searchParams.get("category") || "").trim().toLowerCase();
    const force = url.searchParams.get("refresh") === "1";
    const limit = Math.min(Math.max(Number(url.searchParams.get("limit") || 1500), 1), 10000);

    const raw = await getChannelsBySource(cfg, source, force);
    let filtered = raw.filter((c) => keepsByFilter(c.name, c.category, filter));
    if (filtered.length === 0 && filter !== "all") filtered = raw;

    if (category) {
      filtered = filtered.filter(
        (c) => displayCategoryName(c.category).toLowerCase() === category || c.category.toLowerCase() === category,
      );
    }
    if (q) {
      const toks = q.split(/\s+/).filter(Boolean);
      filtered = filtered.filter((c) => {
        const hay = `${c.name} ${c.category}`.toLowerCase();
        return toks.every((t) => hay.includes(t));
      });
    }

    filtered.sort((a, b) => categoryRank(a.category) - categoryRank(b.category) || a.name.localeCompare(b.name));
    const categories = [...new Set(filtered.map((c) => displayCategoryName(c.category)))];
    const channels = filtered.slice(0, limit).map((c) => toDto(c, publicOrigin));

    // Report what is actually being served. A seed row can never masquerade as a working
    // catalogue, and when the backup playlist is carrying the list that is stated plainly.
    const usingSeed = filtered.length > 0 && filtered.every((c) => String(c.cmd || "").startsWith("seed://"));
    const hasJio = filtered.some((c) => c.source === "jio");
    const hasMaxtv = filtered.some((c) => c.source === "maxtv");
    const hasTgTv = filtered.some((c) => c.source === "tgtv");

    const sourceCount = Number(hasMaxtv) + Number(hasJio) + Number(hasTgTv);
    const served: "maxtv" | "jio" | "tgtv" | "starshare" | "mixed" | "seed" =
      source === "starshare"
        ? "starshare"
        : usingSeed
          ? "seed"
          : source === "jio"
            ? "jio"
            : source === "tgtv"
              ? "tgtv"
              : sourceCount > 1
                ? "mixed"
                : hasJio
                  ? "jio"
                  : hasTgTv
                    ? "tgtv"
                    : "maxtv";

    const portalError = usingSeed ? memCache.maxtv.error : null;

    return jsonResponse(200, {
      ok: true,
      source,
      servedBy: served,
      filter,
      totalMatching: filtered.length,
      usingPreviewSeed: usingSeed,
      portalError,
      portalEndpoint: memCache.maxtv.endpoint,
      backupError: jioCache.error,
      categories,
      channels,
    });
  }

  if (path === "/api/live/playlist.m3u") {
    const source = url.searchParams.get("source") || "maxtv";
    const filter = url.searchParams.get("filter") || "hindi";
    const raw = await getChannelsBySource(cfg, source, false);
    let filtered = raw.filter((c) => keepsByFilter(c.name, c.category, filter));
    if (filtered.length === 0 && filter !== "all") filtered = raw;
    filtered.sort((a, b) => categoryRank(a.category) - categoryRank(b.category) || a.name.localeCompare(b.name));

    const lines = ["#EXTM3U"];
    for (const ch of filtered) {
      const safeName = ch.name.replace(/[\r\n]+/g, " ").trim();
      lines.push(
        `#EXTINF:-1 tvg-id="${ch.id}" tvg-logo="${ch.logo.replace(/"/g, "")}" group-title="${displayCategoryName(ch.category).replace(/"/g, "'")}",${safeName}`,
      );
      lines.push(`${publicOrigin}/api/live/stream/${encodeURIComponent(ch.id)}.ts`);
    }
    return new Response(lines.join("\n") + "\n", {
      status: 200,
      headers: corsEmbedHeaders({ "Content-Type": "audio/x-mpegurl; charset=utf-8" }),
    });
  }

  if (path === "/api/live/resolve") {
    const id = url.searchParams.get("id") || "";
    if (!id) return jsonResponse(400, { ok: false, error: "id is required" });
    const pool = await getChannelsBySource(cfg, "all", false);
    const ch = pool.find((c) => c.id === id || c.rawId === id);
    if (!ch) return jsonResponse(404, { ok: false, error: "Channel not found" });

    if (ch.source === "jio") {
      return jsonResponse(200, {
        ok: true,
        channel: ch.name,
        source: "jio",
        container: ch.mediaType ?? "dash",
        upstreamUrl: ch.cmd ?? "",
        clearkeys: ch.drm?.keys ?? [],
        userAgent: ch.userAgent ?? JIO_UA_OVERRIDE,
        hasCookie: Boolean(ch.cookie),
        proxyUrl: `${publicOrigin}/api/live/stream/${encodeURIComponent(ch.id)}.${ch.mediaType === "hls" ? "m3u8" : ch.mediaType === "mp4" ? "ts" : "mpd"}`,
      });
    }

    try {
      const resolved = await resolveMaxtvLink(cfg, ch.cmd || "");
      return jsonResponse(200, {
        ok: true,
        channel: ch.name,
        source: ch.source,
        container: "mpegts",
        upstreamUrl: resolved,
        proxyUrl: `${publicOrigin}/api/live/stream/${encodeURIComponent(ch.id)}.ts`,
      });
    } catch (e) {
      return jsonResponse(502, { ok: false, error: e instanceof Error ? e.message : "Failed to resolve link" });
    }
  }

  if (path === "/api/live/segment") {
    const tok = url.searchParams.get("t") || "";
    const verified = await verifySegmentToken(tok, cfg.proxySecret);
    if (!verified) return rejectedSegment("hls", "invalid token", 403);
    return proxyMediaStream(
      verified.u,
      verified.m,
      publicOrigin,
      cfg.proxySecret,
      overrideHeadersFromToken(verified, verified.u),
      undefined,
      request.headers.get("range"),
    );
  }

  // DASH segment/initialisation fetches. The tokenised part is the directory, so the
  // path after it is whatever SegmentTemplate the manifest used, already expanded by
  // the player ($Number$, $RepresentationID$, $Time$ and nested folders all survive).
  /**
   * Walk a channel's DASH manifest the way the player will, from the first reference
   * onward, and report what each one actually returns. Without this a failure is only
   * ever "segments refused", with no way to see which URL was refused or why.
   */
  /**
   * What the player was actually handed, summarised and quoted.
   *
   * "The player requested no segments at all" is a conclusion, not an observation: the
   * manifest arrived and the player drew nothing from it. The only way to tell an
   * unparseable manifest from one describing segments somewhere unexpected is to look at
   * the document the player got. Signatures are masked; their presence is reported.
   */
  if (path === "/api/live/manifest") {
    const id = url.searchParams.get("id") || "";
    const pool = await getChannelsBySource(cfg, "all", false);
    const ch = pool.find((c) => c.id === id || c.rawId === id);
    if (!ch) {
      // "Channel not found" is the least useful answer possible: it is the same whether the
      // playlist refused us, moved, or simply does not list this id. Say which.
      return jsonResponse(404, {
        ok: false,
        error: "Channel not found in the catalogue",
        channelId: id,
        playlist: jioPlaylistReport,
        hint: jioPlaylistReport
          ? "The playlist report above says which address and header shape answered, or what each attempt got back."
          : "The catalogue has not been read yet in this process. Request /api/live/channels?source=jio first.",
      });
    }

    let body = "";
    let status = 0;
    let finalUrl = "";
    if (ch.source === "jio" && ch.cmd) {
      const opened = await fetchJioMedia(ch.cmd, { userAgent: ch.userAgent, cookie: ch.cookie }).catch(
        () => null,
      );
      if (opened) {
        status = opened.res.status;
        finalUrl = opened.finalUrl;
        body = opened.res.ok ? await opened.res.text().catch(() => "") : "";
      }
    } else if (ch.cmd) {
      try {
        const resolved = await resolveMaxtvLink(cfg, ch.cmd);
        finalUrl = resolved;
        const res = await fetch(resolved, { headers: stalkerHeaders(cfg, stalkerAccount(cfg), false) });
        status = res.status;
        body = res.ok ? await res.text().catch(() => "") : "";
      } catch (e) {
        status = 0;
        body = "";
        finalUrl = e instanceof Error ? e.message : String(e);
      }
    }

    const isDash = /<MPD/i.test(body.slice(0, 4096)) || /\.mpd/i.test(finalUrl);
    const bases = [...body.matchAll(/<BaseURL[^>]*>([\s\S]*?)<\/BaseURL>/gi)].map((m) => m[1]!.trim());
    const summary = {
      bytes: body.length,
      container: isDash ? "dash" : body.trimStart().startsWith("#EXTM3U") ? "hls" : "unknown",
      representations: (body.match(/<Representation\b/gi) ?? []).length,
      adaptationSets: (body.match(/<AdaptationSet\b/gi) ?? []).length,
      segmentTemplate: (body.match(/<SegmentTemplate\b/gi) ?? []).length,
      segmentList: (body.match(/<SegmentList\b/gi) ?? []).length,
      segmentBase: (body.match(/<SegmentBase\b/gi) ?? []).length,
      baseUrls: bases,
      type: /<MPD[^>]*\btype="([^"]+)"/i.exec(body)?.[1] ?? null,
      availabilityStartTime: /\bavailabilityStartTime="([^"]+)"/i.exec(body)?.[1] ?? null,
      minimumUpdatePeriod: /\bminimumUpdatePeriod="([^"]+)"/i.exec(body)?.[1] ?? null,
      contentProtection: (body.match(/<ContentProtection\b/gi) ?? []).length,
      defaultKids: [...body.matchAll(/cenc:default_KID\s*=\s*"([^"]+)"/gi)].map((m) => m[1]!),
    };

    // Signatures are replaced, not printed: this endpoint is reachable by anyone.
    const redacted = body.replace(/__hdnea__=[^&"'\s]*/g, "__hdnea__=<redacted>");
    return jsonResponse(200, {
      ok: true,
      channel: { id: ch.id, name: ch.name, source: ch.source, mediaType: ch.mediaType ?? null },
      upstreamStatus: status,
      resolvedUrl: finalUrl.replace(__hdnea_re, "__hdnea__=<redacted>"),
      summary,
      manifest: redacted.slice(0, 20000),
      note:
        summary.representations === 0
          ? "No Representations: the document is not a DASH manifest the player can use."
          : bases.length === 0 && summary.segmentTemplate === 0 && summary.segmentBase === 0
            ? "Representations exist but nothing says where their segments live."
            : "This is the document shape the player received, before our rewrite. Compare the BaseURLs with what the player requested.",
    });
  }

  if (path === "/api/live/diagnose") {
    const id = url.searchParams.get("id") || "";

    // A maxTV question is about the portal, not about segments. Answer it in terms of the
    // portal chain: which identity we present, which address answered, what the token is,
    // what create_link returned, and whether that link plays.
    if (url.searchParams.get("source") === "maxtv" || id.startsWith("st:")) {
      const candidates = stalkerAccountCandidates(cfg).map((a) => ({
        portal: a.portal,
        mac: a.mac,
        addresses: stalkerCandidates(a.portal),
      }));
      const report: Record<string, unknown> = {
        ok: true,
        kind: "maxtv",
        // What the portal itself said, in order. Five different failures look identical in
        // the player; this is where they differ.
        trace: portalTrace.map((t) => ({
          secondsAgo: Math.round((Date.now() - t.at) / 100) / 10,
          action: t.action,
          address: t.address,
          status: t.status,
          said: t.said,
        })),
        configured: {
          portal: cfg.maxtvPortal,
          mac: cfg.maxtvMac,
          // Whether these came from configuration or from our defaults. It decides whether
          // an identity may be migrated, and it explains a mismatch that otherwise looks
          // like the portal disagreeing with what was set.
          fromEnv: { portal: cfg.maxtvPortalExplicit, mac: cfg.maxtvMacExplicit },
          envVars: ["MAXTV_PORTAL", "MAXTV_MAC", "MAXTV_SERIAL", "MAXTV_DEVICE_ID"],
        },
        identities: candidates,
        migrated:
          candidates[0]!.mac !== cfg.maxtvMac || candidates[0]!.portal !== cfg.maxtvPortal,
      };
      try {
        const token = await stalkerHandshake(cfg);
        report['handshake'] = { ok: true, endpoint: stalkerSession.endpoint, token };
        const account = stalkerAccount(cfg);
        report['identityInUse'] = { portal: account.portal, mac: account.mac };
      } catch (e) {
        report['handshake'] = { ok: false, error: e instanceof Error ? e.message : String(e) };
        report['hint'] =
          "The portal refused us. Compare identityInUse/identities with the app: an expired " +
          "MAC answers in its own words, and the migrated identity is the live one.";
        return jsonResponse(200, report);
      }

      const pool = await getChannelsBySource(cfg, "maxtv", false).catch(() => []);
      report['catalogue'] = {
        channels: pool.length,
        note:
          pool.length === 0
            ? "The handshake succeeded but no channels came back: the bulk call and the paged fallback both returned nothing. The trace shows what each said."
            : "Channels are listed.",
      };
      const ch = pool.find((c) => c.id === id || c.rawId === id);
      if (ch?.cmd && !ch.cmd.startsWith("seed://")) {
        try {
          const resolved = await resolveMaxtvLink(cfg, ch.cmd);
          const probe = await fetch(resolved, {
            headers: stalkerHeaders(cfg, stalkerAccount(cfg), false),
            redirect: "manual",
          }).catch(() => null);
          report['stream'] = { channel: ch.name, url: resolved, status: probe ? probe.status : 0 };
          report['hint'] =
            probe && probe.ok
              ? "The portal and the media both answered."
              : "create_link produced a URL the media host refused; the status above is its answer.";
        } catch (e) {
          report['stream'] = { channel: ch.name, error: e instanceof Error ? e.message : String(e) };
        }
      }
      return jsonResponse(200, report);
    }

    const pool = await getChannelsBySource(cfg, "all", false);
    const ch = pool.find((c) => c.id === id || c.rawId === id);
    if (!ch) {
      return jsonResponse(404, {
        ok: false,
        error: "Channel not found in the catalogue",
        channelId: id,
        playlist: jioPlaylistReport,
        hint: jioPlaylistReport
          ? "The playlist report says which address answered, or what each attempt got back."
          : "The catalogue has not been read yet in this process. Request /api/live/channels?source=jio first.",
      });
    }

    const probe = async (raw: string, headers: Record<string, string>) => {
      try {
        const res = await fetch(raw, { headers, redirect: "manual" });
        const text = res.status === 200 ? await res.text().catch(() => "") : "";
        return {
          url: raw,
          status: res.status,
          location: res.headers.get("location") ?? null,
          contentType: res.headers.get("content-type") ?? null,
          isManifest: /#EXTM3U|<MPD/i.test(text.slice(0, 2048)),
        };
      } catch (e) {
        return { url: raw, status: 0, error: e instanceof Error ? e.message : String(e) };
      }
    };

    const ua = ch.userAgent || JIO_UA_OVERRIDE;
    const headers: Record<string, string> = {
      "User-Agent": ua,
      ...(ch.cookie ? { Cookie: ch.cookie } : {}),
      Accept: "*/*",
    };
    const opened = ch.cmd ? await probe(ch.cmd, headers) : null;
    const finalUrl = opened?.location ?? ch.cmd ?? "";

    let references: Array<{ kind: string; url: string; status: number | null }> = [];
    if (ch.mediaType === "dash" && finalUrl) {
      const body = await fetch(finalUrl, { headers, redirect: "follow" })
        .then((r) => (r.ok ? r.text() : ""))
        .catch(() => "");
      const base = finalUrl.slice(0, finalUrl.lastIndexOf("/") + 1);
      for (const m of body.matchAll(/<(?:BaseURL|SegmentTemplate|SegmentURL)[^>]*>/gi)) {
        const tag = m[0];
        const attr = /(media|initialization|sourceURL|index|href)="([^"]+)"/i.exec(tag);
        if (!attr?.[2]) continue;
        let abs: string;
        try {
          abs = new URL(attr[2].replace(/\$(Number|RepresentationID|Time|Bandwidth)\$[^/]*$/, "1"), base).toString();
        } catch {
          continue;
        }
        const r = await fetch(abs, { headers: { ...headers, Range: "bytes=0-1023" }, redirect: "manual" }).catch(() => null);
        references.push({ kind: attr[1]!, url: abs, status: r ? r.status : 0 });
      }
    }

    return jsonResponse(200, {
      ok: true,
      channel: { id: ch.id, name: ch.name, source: ch.source, mediaType: ch.mediaType ?? null },
      // What the CDN answered for the segments the player actually asked for. This is the
      // fact that decides whether a black picture is a refusal or a decoder problem.
      recentSegments: [...segmentLog.entries()]
        .flatMap(([, list]) => list)
        .sort((a, b) => b.at - a.at)
        .slice(0, 12)
        .map((a) => ({
          secondsAgo: Math.round((Date.now() - a.at) / 100) / 10,
          status: a.status,
          bytes: a.bytes,
          range: a.range,
          url: a.url,
        })),
      hasCookie: Boolean(ch.cookie),
      // The playlist this channel came from, and how that playlist answered.
      playlist: jioPlaylistReport,
      advertisedUrl: ch.cmd ?? null,
      firstResponse: opened,
      resolvedUrl: finalUrl,
      references,
      hint:
        references.length === 0
          ? "No DASH references were found. The manifest may be HLS, or the CDN refused the manifest fetch."
          : references.every((r) => r.status === 200 || r.status === 206)
            ? "Every reference answered. Playback should work."
            : "At least one reference was refused; the statuses above say which and where.",
    });
  }

  if (path.startsWith("/api/live/dashseg/")) {
    const rest = path.slice("/api/live/dashseg/".length);
    const slash = rest.indexOf("/");
    if (slash < 0) return jsonResponse(400, { ok: false, error: "Malformed DASH segment path" });
    const tok = decodeURIComponent(rest.slice(0, slash));
    const file = rest.slice(slash + 1);
    const verified = await verifySegmentToken(tok, cfg.proxySecret);
    if (!verified) return rejectedSegment("dashseg", `invalid token for ${file}`, 403);
    const target = new URL(file, verified.u.endsWith("/") ? verified.u : `${verified.u}/`);
    if (url.search) target.search = url.search;
    else if (!target.search) {
      // RFC 3986 drops the base query when resolving a relative reference, but that query
      // is the signature this segment needs. Put it back.
      const baseSearch = verified.u.includes("?") ? verified.u.slice(verified.u.indexOf("?")) : "";
      if (baseSearch) target.search = baseSearch;
    }
    return proxyMediaStream(
      target.toString(),
      verified.m,
      publicOrigin,
      cfg.proxySecret,
      overrideHeadersFromToken(verified, target.toString()),
      undefined,
      request.headers.get("range"),
    );
  }

  if (path.startsWith("/api/live/stream/")) {
    const rawId = decodeURIComponent(path.slice("/api/live/stream/".length)).replace(/\.(?:ts|m3u8|mpd)$/i, "");
    const preferHls = path.toLowerCase().endsWith(".m3u8");
    const isProbe = url.searchParams.get("probe") === "1";

    // Consume a cached manifest or resolved MPEG-TS link prepared by `?probe=1` milliseconds earlier
    // so we never call Stalker `create_link` or Jio redirectors twice on channel start.
    const handoff = !isProbe ? takeStreamHandoff(rawId) : null;
    if (handoff && (handoff.kind === "dash" || handoff.kind === "hls") && handoff.body !== undefined && handoff.headers) {
      return new Response(handoff.body, { status: 200, headers: handoff.headers });
    }

    const pool = rawId.startsWith("xt:")
      ? await loadStarshareCached(cfg, false)
      : rawId.startsWith("tg:")
        ? await loadTgTvCached(cfg, false)
        : await getChannelsBySource(cfg, "all", false);
    const ch = pool.find((c) => c.id === rawId || c.rawId === rawId);
    if (!ch) return jsonResponse(404, { ok: false, error: "Channel not found" });

    if (handoff && handoff.kind === "mpegts" && handoff.resolvedUrl) {
      return proxyMediaStream(
        handoff.resolvedUrl,
        ch.source === "maxtv" ? "mag" : "xtream",
        publicOrigin,
        cfg.proxySecret,
        undefined,
        undefined,
        request.headers.get("range"),
      );
    }

    if (ch.source === "starshare") {
      const origin = (xtreamState.workingOrigin || cfg.starshareServer).replace(/\/+$/, "");
      const u = encodeURIComponent(xtreamState.canonUser || cfg.starshareUser);
      const p = encodeURIComponent(xtreamState.canonPass || cfg.starsharePass);
      return proxyMediaStream(
        `${origin}/live/${u}/${p}/${ch.rawId}.${preferHls ? "m3u8" : "ts"}`,
        "xtream",
        publicOrigin,
        cfg.proxySecret,
        undefined,
        undefined,
        request.headers.get("range"),
        { isProbe, handoffId: ch.id },
      );
    }

    if (ch.source === "tgtv") {
      if (!ch.cmd) return jsonResponse(503, { ok: false, error: "This TG TV channel has no stream URL" });
      return proxyMediaStream(
        ch.cmd,
        "xtream",
        publicOrigin,
        cfg.proxySecret,
        {
          "User-Agent": ch.userAgent || "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
          ...(ch.cookie ? { Cookie: ch.cookie } : {}),
        },
        undefined,
        request.headers.get("range"),
        { isProbe, handoffId: ch.id },
      );
    }

    // JioTV backup entries carry their own media URL, so they need no portal round-trip.
    if (ch.source === "jio") {
      if (!ch.cmd) return jsonResponse(503, { ok: false, error: "This backup channel has no stream URL" });

      let current = ch;
      let opened = await fetchJioMedia(current.cmd!, { userAgent: current.userAgent, cookie: current.cookie })
        .catch(() => null);
      if (opened && isJioRefusal(opened.res.status)) {
        const refreshed = await refreshJioChannel(cfg, current);
        if (refreshed) {
          current = refreshed;
          opened = await fetchJioMedia(current.cmd!, {
            userAgent: current.userAgent,
            cookie: current.cookie,
          }).catch(() => null);
        }
      }

      if (!opened) return jsonResponse(502, { ok: false, error: "The JioTV CDN did not answer this channel" });
      if (isJioRefusal(opened.res.status)) {
        return jsonResponse(502, {
          ok: false,
          error: `The JioTV CDN refused this channel (HTTP ${opened.res.status}) even after re-reading the playlist for a fresh token.`,
        });
      }
      try {
        return await proxyMediaStream(
          opened.finalUrl,
          "xtream",
          publicOrigin,
          cfg.proxySecret,
          {
            "User-Agent": current.userAgent || JIO_UA_OVERRIDE,
            ...(current.cookie ? { Cookie: current.cookie } : {}),
          },
          current.drm?.keys,
          request.headers.get("range"),
          { isProbe, handoffId: current.id },
        );
      } catch (e) {
        return jsonResponse(502, { ok: false, error: e instanceof Error ? e.message : "Backup stream failed" });
      }
    }

    if (String(ch.cmd || "").startsWith("seed://")) {
      return jsonResponse(503, {
        ok: false,
        error: "No live source is reachable. maxTV did not answer and the backup playlist is unavailable.",
      });
    }

    try {
      const resolved = await resolveMaxtvLink(cfg, ch.cmd || "");
      const acct = stalkerAccount(cfg);
      const magCookie = `mac=${encodeURIComponent(acct.mac)}; stb_lang=en; timezone=Europe%2FLondon`;
      return await proxyMediaStream(
        resolved,
        "mag",
        publicOrigin,
        cfg.proxySecret,
        {
          "User-Agent": MAG_USER_AGENT,
          Cookie: magCookie,
          Referer: `${acct.portal.replace(/\/+$/, "")}/`,
        },
        undefined,
        request.headers.get("range"),
        { isProbe, handoffId: ch.id },
      );
    } catch (e) {
      return jsonResponse(502, { ok: false, error: e instanceof Error ? e.message : "Failed to resolve maxTV link" });
    }
  }

  if (path.startsWith("/embed/")) {
    const rawId = decodeURIComponent(path.slice("/embed/".length));
    const pool = await getChannelsBySource(cfg, "all", false);
    const ch = pool.find((c) => c.id === rawId || c.rawId === rawId);
    const dto = ch
      ? toDto(ch, publicOrigin)
      : {
          id: rawId,
          source: "maxtv" as const,
          name: rawId,
          category: "Live TV",
          rawCategory: "Live TV",
          logo: "",
          embedUrl: `${publicOrigin}${path}`,
          iframeCode: "",
          streamUrl: `${publicOrigin}/api/live/stream/${encodeURIComponent(rawId)}.ts`,
        };
    return new Response(renderStandaloneEmbedHtml(dto), {
      status: 200,
      headers: corsEmbedHeaders({ "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" }),
    });
  }

  return null;
}
