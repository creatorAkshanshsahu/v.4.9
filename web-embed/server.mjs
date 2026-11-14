import http from "node:http";
import https from "node:https";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, "public");
const SEED_PATH = path.join(__dirname, "seed-channels.json");

export const CONFIG = {
  port: Number(process.env.PORT || 8787),
  host: process.env.HOST || "0.0.0.0",
  maxtvPortal: (process.env.MAXTV_PORTAL || "http://new.maxtv.be/stalker_portal/c/").trim(),
  maxtvMac: (process.env.MAXTV_MAC || "00:1A:79:EE:03:9F").trim(),
  maxtvSerial: (process.env.MAXTV_SERIAL || "92180365D21FE").trim(),
  maxtvDeviceId: (
    process.env.MAXTV_DEVICE_ID ||
    "3610DEAFB556781F84CBC8A6AF876A8F20637668862B28DEECFACE00D67A6070"
  ).trim(),
  starshareServer: (process.env.STARSHARE_SERVER || "http://starshare.cx").trim(),
  starshareFallback: (process.env.STARSHARE_FALLBACK || "http://livebox.pro:8080").trim(),
  starshareUser: (process.env.STARSHARE_USER || "Hud1234").trim(),
  starsharePass: (process.env.STARSHARE_PASS || "hud1234").trim(),
  catalogueTtlMs: Number(process.env.CATALOGUE_TTL_MS || 15 * 60 * 1000),
  proxySecret: process.env.PROXY_SECRET || crypto.randomBytes(24).toString("hex")
};

export const MAG_USER_AGENT =
  "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2110 Safari/533.3";
export const MAG_DEVICE_UA = "Model: MAG254; Link: WiFi";
export const XTREAM_USER_AGENT = "IPTVSmartersPlayer";

// ---------- Indian Hindi + Sports/Cricket + Dual-Audio Filter ----------

const ADULT_RE = /\b(?:XXX|ADULT|ADULTS|FOR\s+ADULTS|18\+|PORN|PLAYBOY|BRAZZERS|EROTIC|ONLYFANS)\b/i;
const HINDI_EXPLICIT_RE =
  /\b(?:HINDI|BOLLYWOOD|HIN\s*DUB|HINDI\s*DUB(?:BED)?|DUAL\s*AUDIO|DESI|BIGG\s*BOSS)\b/i;
const INDIA_TAG_RE =
  /(?:^|[|\[(\{\s:\-])(?:IN|IND|INDIA|INDIAN|BHARAT|HINDUSTAN|TATA\s*PLAY|TATA\s*SKY|JIO(?:\s*TV|\s*CINEMA|\s*HOTSTAR)?|AIRTEL|DISH\s*TV|D2H|DOORDARSHAN|HOTSTAR|ZEE5|SONYLIV|SONY\s*LIV|MX\s*PLAYER|TVF)(?:$|[|\])\}\s:\-])/i;
const CRICKET_RE =
  /\b(?:CRICKET|IPL|BCCI|ICC|WPL|ASIA\s*CUP|WILLOW|ASTRO\s*CRICKET|STAR\s*SPORTS|SPORTS\s*18|SPORTS18|SONY\s*(?:SPORTS|TEN)|TEN\s*SPORTS|DD\s*SPORTS|FANCODE|FAN\s*CODE)\b/i;
const INDIAN_SPORTS_RE = /\b(?:PRO\s*KABADDI|ISL|INDIAN\s*SUPER\s*LEAGUE|EUROSPORT\s*INDIA)\b/i;
const REGIONAL_NON_HINDI_RE =
  /\b(?:TAMIL|TAMIZH|TELUGU|TELGU|MALAYALAM|MALYALAM|KERALAM|KANNADA|MARATHI|BENGALI|BANGLA|PUNJABI|GUJARATI|GUJRATI|ODIA|ORIYA|ASSAMESE|ASSAM|BHOJPURI|URDU|NEPALI|SINHALA|SUN\s*TV|SUN\s*MUSIC|KTV|ADITHYA|UDAYA|SURYA\s*TV|GEMINI|STAR\s*(?:MAA|VIJAY|JALSHA|SUVARNA|PRAVAH|KIRAN)|ASIANET|MAZHAVIL|KAIRALI|MANORAMA|KALAIGNAR|POLIMER|THANTHI|JAYA\s*TV|ZEE\s*(?:TAMIL|TELUGU|KANNADA|KERALAM|MARATHI|BANGLA|PUNJABI|SARTHAK|TALKIES|PICCHAR|24\s*TAAS|24\s*GHANTA)|COLORS\s*(?:TAMIL|KANNADA|MARATHI|BANGLA|GUJARATI|ODIA)|SONY\s*(?:MARATHI|AATH)|PTC\s*PUNJABI)\b/i;
const FOREIGN_STRICT_RE =
  /(?:^|[|\[(\{\s:\-])(?:US|USA|UK|UNITED\s*KINGDOM|CA|CANADA|AU|AUSTRALIA|NZ|PK|PAK|PAKISTAN|BD|BANGLADESH|AF|AFGHANISTAN|AR|ARA|ARAB|ARABIC|AE|UAE|SAUDI|QATAR|OSN|MBC|TR|TURKEY|IR|IRAN|FR|FRANCE|DE|GERMANY|ES|SPAIN|LATAM|PT|PORTUGAL|BR|BRAZIL|IT|ITALY|NL|NETHERLANDS|PL|POLAND|RU|RUSSIA|RO|ROMANIA|GR|GREECE|AL|ALBANIA|EX[-\s]*YU|AFRICA|DSTV|PH|MY|SG|ID|TH|VN|CN|HK|TW|JP|KR|CARIBBEAN|NFL|NBA|NHL|MLB)(?:$|[|\])\}\s:\-])/i;
const FOREIGN_CHANNEL_PREFIX_RE =
  /^(?:US|USA|UK|CA|AU|NZ|PK|PAK|BD|AF|AR|AE|TR|IR|FR|DE|ES|PT|BR|IT|NL|PL|RU|RO|GR|AL|ZA|PH|MY|ID|TH|VN|CN|HK|JP|KR)\b\s*[:|\-]/i;
const INDIAN_BROADCASTER_RE =
  /\b(?:STAR\s*(?:PLUS|BHARAT|UTSAV|GOLD|MOVIES|WORLD)|SONY\s*(?:SET|SAB|MAX|PIX|PAL|WAH|YAY|BBC\s*EARTH)|SET\s*(?:INDIA|HD)|SAB\s*(?:TV|HD)|ZEE\s*(?:TV|CINEMA|BOLLYWOOD|ACTION|CLASSIC|ANMOL|CAFE|ZEST|NEWS|BUSINESS|BHARAT)|COLORS(?:\s*(?:HD|SD|TV|INFINITY|CINEPLEX|RISHTEY))?|&(?:\s*TV|\s*PICTURES|\s*FLICKS|\s*PRIVE)|AND\s*(?:TV|PICTURES|FLICKS)|DANGAL|SHEMAROO|GOLDMINES|B4U\s*(?:MOVIES|MUSIC|KADAK)|UTV\s*(?:MOVIES|ACTION|BINDASS)|BINDASS|EPIC\s*TV|AAJ\s*TAK|INDIA\s*TODAY|NDTV|INDIA\s*TV|ABP\s*NEWS|NEWS\s*18\s*INDIA|NEWS\s*24|REPUBLIC\s*(?:TV|BHARAT)|TIMES\s*NOW|MIRROR\s*NOW|CNBC\s*(?:AWAAZ|TV18)|WION|DD\s*(?:NATIONAL|NEWS|INDIA|BHARATI|KISAN|SPORTS)|SANSAD\s*TV|TV9\s*BHARATVARSH|DISCOVERY|ANIMAL\s*PLANET|TLC|HISTORY\s*TV\s*18|NAT\s*GEO|NATIONAL\s*GEOGRAPHIC|TRAVELXP|HUNGAMA|POGO|CARTOON\s*NETWORK|NICKELODEON|NICK|SONIC\s*NICKELODEON|DISNEY|9XM|9X\s*JALWA|MASTIII|ZOOM\s*TV|MTV\s*(?:BEATS|INDIA)|SHOWBOX|E24|ZING|AASTHA|SANSKAR|SADHNA)\b/i;

export function displayCategoryName(category = "") {
  const stripped = String(category)
    .trim()
    .replace(/^[|\[(\{\s]*(?:IN|IND|INDIA|INDIAN|VIP)[|\])\}\s:\-–—]+/i, "")
    .replace(/^[|:\-–—\s]+|[|:\-–—\s]+$/g, "")
    .replace(/\s{2,}/g, " ")
    .trim();
  return stripped || String(category).trim() || "Live TV";
}

export function categoryRank(category = "") {
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

export function keepsIndianHindi(name = "", category = "") {
  const combined = `${category} ${name}`;
  if (ADULT_RE.test(combined)) return false;
  const explicitHindi = HINDI_EXPLICIT_RE.test(combined);
  if (!explicitHindi && REGIONAL_NON_HINDI_RE.test(combined)) return false;
  const isCricket = CRICKET_RE.test(combined);
  if (!explicitHindi && !INDIA_TAG_RE.test(category) && !isCricket) {
    if (FOREIGN_STRICT_RE.test(category) || FOREIGN_STRICT_RE.test(name)) return false;
  }
  if (!explicitHindi && !isCricket && FOREIGN_CHANNEL_PREFIX_RE.test(String(name).trim())) {
    return false;
  }
  return (
    explicitHindi ||
    INDIA_TAG_RE.test(combined) ||
    isCricket ||
    INDIAN_SPORTS_RE.test(combined) ||
    INDIAN_BROADCASTER_RE.test(name)
  );
}

export function keepsByFilter(name = "", category = "", filter = "hindi") {
  const f = String(filter || "hindi").toLowerCase();
  if (f === "all") return !ADULT_RE.test(`${category} ${name}`);
  if (f === "en_hi" || f === "english") {
    if (ADULT_RE.test(`${category} ${name}`)) return false;
    if (REGIONAL_NON_HINDI_RE.test(`${category} ${name}`) && !HINDI_EXPLICIT_RE.test(`${category} ${name}`)) {
      return false;
    }
    return !FOREIGN_CHANNEL_PREFIX_RE.test(String(name).trim());
  }
  return keepsIndianHindi(name, category);
}

// ---------- Stalker / maxTV Wire Protocol ----------

export function stalkerCandidates(portalUrl) {
  const configured = (portalUrl || "http://new.maxtv.be/c").trim().replace(/\/+$/, "");
  let origin = configured;
  try {
    const u = new URL(configured);
    origin = `${u.protocol}//${u.host}`;
  } catch {}
  const set = new Set();
  const add = (u) => u.startsWith("http") && set.add(u);
  if (configured.endsWith("/c")) add(`${configured.slice(0, -2)}/stalker_portal/server/load.php`);
  add(`${origin}/stalker_portal/server/load.php`);
  if (configured.endsWith("/c")) add(`${configured.slice(0, -2)}/server/load.php`);
  add(`${configured}/server/load.php`);
  add(`${origin}/server/load.php`);
  add(`${origin}/portal.php`);
  add(`${origin}/stalker_portal/portal.php`);
  return [...set];
}

export function parseStalkerEnvelope(body = "") {
  const text = String(body).trim().replace(/^\/\*\*\//, "").trim();
  const brace = text.indexOf("{");
  const bracket = text.indexOf("[");
  const starts = [brace, bracket].filter((i) => i >= 0);
  if (!starts.length) return null;
  try {
    return JSON.parse(text.slice(Math.min(...starts)));
  } catch {
    return null;
  }
}

export function extractStalkerStreamUrl(cmd = "") {
  const clean = String(cmd)
    .trim()
    .replace(/^ff(?:mpeg|rt\d*)\s+/i, "")
    .trim()
    .split("|")[0]
    .trim();
  if (/^(?:https?|rtsp):\/\//i.test(clean)) return clean;
  return null;
}

export function isStalkerExpired(body = "") {
  const root = parseStalkerEnvelope(body);
  if (!root || typeof root !== "object" || Array.isArray(root)) return false;
  const js = root.js && typeof root.js === "object" ? root.js : root;
  if (js.not_valid === true || (typeof js.not_valid === "number" && js.not_valid !== 0)) return true;
  const msg = `${js.error || ""} ${js.msg || ""} ${root.error || ""}`.toLowerCase();
  return msg.includes("authorization failed") || msg.includes("not valid") || (msg.includes("token") && msg.includes("expire"));
}

async function fetchText(url, headers = {}, timeoutMs = 18000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { headers, redirect: "follow", signal: controller.signal });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

export class StalkerClient {
  constructor(cfg = CONFIG, transport = fetchText) {
    this.portal = cfg.maxtvPortal;
    this.mac = cfg.maxtvMac;
    this.serial = cfg.maxtvSerial;
    this.deviceId = cfg.maxtvDeviceId;
    this.transport = transport;
    this.endpoint = null;
    this.token = null;
    this.profiled = false;
  }

  get origin() {
    try {
      const u = new URL(this.portal);
      return `${u.protocol}//${u.host}`;
    } catch {
      return "http://new.maxtv.be";
    }
  }

  headers(auth = true) {
    const h = {
      Cookie: `mac=${encodeURIComponent(this.mac)}; stb_lang=en; timezone=Europe/London`,
      "User-Agent": MAG_USER_AGENT,
      "X-User-Agent": MAG_DEVICE_UA,
      Accept: "*/*",
      Referer: `${this.portal.replace(/\/+$/, "")}/`
    };
    if (auth && this.token) h.Authorization = `Bearer ${this.token}`;
    return h;
  }

  buildUrl(base, type, action, params = {}) {
    const u = new URL(base);
    u.searchParams.set("type", type);
    u.searchParams.set("action", action);
    for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v);
    u.searchParams.set("JsHttpRequest", "1-xml");
    return u.toString();
  }

  async handshake() {
    const candidates = this.endpoint ? [this.endpoint] : stalkerCandidates(this.portal);
    let lastErr = null;
    for (const candidate of candidates) {
      try {
        const body = await this.transport(
          this.buildUrl(candidate, "stb", "handshake", { token: "", prehash: "0" }),
          this.headers(false),
          12000
        );
        const root = parseStalkerEnvelope(body);
        const js = root && root.js ? root.js : root;
        const tok = js && typeof js.token === "string" ? js.token.trim() : "";
        if (tok) {
          this.endpoint = candidate;
          this.token = tok;
          this.profiled = false;
          return tok;
        }
      } catch (err) {
        lastErr = err;
      }
    }
    this.endpoint = null;
    throw lastErr || new Error("maxTV handshake issued no token");
  }

  async ensureSession() {
    if (!this.token || !this.endpoint) await this.handshake();
    if (!this.profiled) {
      this.profiled = true;
      try {
        await this.request("stb", "get_profile", {
          hd: "1",
          auth_second_step: "1",
          prehash: "0",
          num_banks: "2",
          stb_type: "MAG254",
          sn: this.serial,
          device_id: this.deviceId,
          device_id2: this.deviceId,
          signature: "",
          client_type: "STB",
          image_version: "218",
          video_out: "hdmi",
          hw_version: "1.7-BD-00",
          api_signature: "262"
        });
      } catch {}
    }
  }

  async request(type, action, params = {}, retried = false) {
    await this.ensureSession();
    const body = await this.transport(this.buildUrl(this.endpoint, type, action, params), this.headers(true), 25000);
    if (!retried && isStalkerExpired(body)) {
      this.token = null;
      await this.handshake();
      return this.request(type, action, params, true);
    }
    return body;
  }

  async fetchCatalogue() {
    const genresBody = await this.request("itv", "get_genres", {}).catch(() => "");
    const genresMap = new Map();
    const gEnv = parseStalkerEnvelope(genresBody);
    const gArr = Array.isArray(gEnv)
      ? gEnv
      : (gEnv && (gEnv.js?.data || gEnv.js || gEnv.data)) || [];
    if (Array.isArray(gArr)) {
      for (const item of gArr) {
        if (item?.id && item?.title && item.id !== "*" && String(item.title).toLowerCase() !== "all") {
          genresMap.set(String(item.id).trim(), String(item.title).trim());
        }
      }
    }

    const chBody = await this.request("itv", "get_all_channels", {});
    const chEnv = parseStalkerEnvelope(chBody);
    const data = chEnv?.js?.data || chEnv?.data || [];
    if (!Array.isArray(data) || data.length === 0) {
      throw new Error("maxTV returned an empty channel catalogue");
    }
    const out = [];
    for (const item of data) {
      const cmd = String(item?.cmd || "").trim();
      const name = String(item?.name || item?.title || "").trim();
      const rawId = String(item?.id || item?.ch_id || "").trim();
      if (!cmd || !name) continue;
      const genreId = String(item?.tv_genre_id || "").trim();
      const logoRaw = String(item?.logo || "").trim();
      const logo = logoRaw.startsWith("/") ? `${this.origin}${logoRaw}` : logoRaw;
      out.push({
        id: `st:${rawId || Buffer.from(name).toString("hex").slice(0, 10)}`,
        rawId: rawId || name,
        source: "maxtv",
        name,
        category: genresMap.get(genreId) || "maxTV",
        logo,
        cmd
      });
    }
    return out;
  }

  async resolveLink(cmd) {
    const body = await this.request("itv", "create_link", {
      cmd,
      series: "",
      forced_storage: "",
      disable_ad: "0",
      download: "0",
      force_ch_link_check: "0"
    });
    const env = parseStalkerEnvelope(body);
    const rawCmd = env?.js?.cmd || env?.cmd || "";
    const resolved = extractStalkerStreamUrl(rawCmd) || extractStalkerStreamUrl(cmd);
    if (!resolved) throw new Error("maxTV create_link returned no playable URL");
    return resolved;
  }
}

// ---------- Optional StarShare Xtream Live Catalogue ----------

export class XtreamLiveClient {
  constructor(cfg = CONFIG, transport = fetchText) {
    this.origins = [cfg.starshareServer, cfg.starshareFallback].filter(Boolean).map((u) => u.replace(/\/+$/, ""));
    this.username = cfg.starshareUser;
    this.password = cfg.starsharePass;
    this.transport = transport;
    this.workingOrigin = this.origins[0] || "http://livebox.pro:8080";
    this.canonUser = this.username;
    this.canonPass = this.password;
  }

  get configured() {
    return Boolean(this.workingOrigin && this.username && this.password);
  }

  async fetchCatalogue() {
    if (!this.configured) return [];
    let lastErr = null;
    for (const origin of this.origins) {
      try {
        const u = encodeURIComponent(this.username);
        const p = encodeURIComponent(this.password);
        const authRaw = await this.transport(
          `${origin}/player_api.php?username=${u}&password=${p}`,
          { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
          15000
        );
        const auth = JSON.parse(authRaw);
        if (!auth?.user_info || !(auth.user_info.auth == 1 || auth.user_info.status === "Active")) {
          throw new Error("StarShare auth rejected");
        }
        this.workingOrigin = origin;
        this.canonUser = String(auth.user_info.username || this.username).trim();
        this.canonPass = String(auth.user_info.password || this.password).trim();

        const cu = encodeURIComponent(this.canonUser);
        const cp = encodeURIComponent(this.canonPass);
        const catsRaw = await this.transport(
          `${origin}/player_api.php?username=${cu}&password=${cp}&action=get_live_categories`,
          { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
          20000
        ).catch(() => "[]");
        const catsMap = new Map();
        for (const c of JSON.parse(catsRaw)) {
          if (c?.category_id && c?.category_name) catsMap.set(String(c.category_id), String(c.category_name).trim());
        }

        const streamsRaw = await this.transport(
          `${origin}/player_api.php?username=${cu}&password=${cp}&action=get_live_streams`,
          { "User-Agent": XTREAM_USER_AGENT, Accept: "application/json" },
          35000
        );
        const arr = JSON.parse(streamsRaw);
        return arr
          .filter((item) => item?.stream_id && item?.name)
          .map((item) => ({
            id: `xt:${item.stream_id}`,
            rawId: String(item.stream_id),
            source: "starshare",
            name: String(item.name).trim(),
            category: catsMap.get(String(item.category_id)) || "StarShare Live",
            logo: String(item.stream_icon || "").trim()
          }));
      } catch (err) {
        lastErr = err;
      }
    }
    throw lastErr || new Error("StarShare unreachable");
  }

  streamUrl(rawId, ext = "ts") {
    const u = encodeURIComponent(this.canonUser || this.username);
    const p = encodeURIComponent(this.canonPass || this.password);
    return `${this.workingOrigin}/live/${u}/${p}/${rawId}.${ext}`;
  }
}

// ---------- Signed URL Token & HLS Manifest Rewriter ----------

export function signProxyUrl(targetUrl, uaMode = "mag", secret = CONFIG.proxySecret) {
  const payload = Buffer.from(JSON.stringify({ u: targetUrl, m: uaMode })).toString("base64url");
  const sig = crypto.createHmac("sha256", secret).update(payload).digest("base64url").slice(0, 22);
  return `${payload}.${sig}`;
}

export function verifyProxyToken(token, secret = CONFIG.proxySecret) {
  const [payload, sig] = String(token || "").split(".");
  if (!payload || !sig) return null;
  const expected = crypto.createHmac("sha256", secret).update(payload).digest("base64url").slice(0, 22);
  if (sig !== expected) return null;
  try {
    const parsed = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (!parsed?.u || !/^https?:\/\//i.test(parsed.u)) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function rewriteHlsManifest(manifestText, baseUrl, uaMode = "mag", baseOrigin = "", secret = CONFIG.proxySecret) {
  const prefix = baseOrigin ? `${baseOrigin.replace(/\/+$/, "")}/api/segment?t=` : `/api/segment?t=`;
  const resolveRef = (ref) => {
    const trimmed = ref.trim();
    if (!trimmed) return ref;
    let abs;
    try {
      abs = new URL(trimmed, baseUrl).toString();
    } catch {
      return ref;
    }
    return `${prefix}${encodeURIComponent(signProxyUrl(abs, uaMode, secret))}`;
  };

  return String(manifestText)
    .split(/\r?\n/)
    .map((line) => {
      const trimmed = line.trim();
      if (!trimmed) return line;
      if (trimmed.startsWith("#")) {
        return line.replace(/URI="([^"]+)"/g, (_, uri) => `URI="${resolveRef(uri)}"`);
      }
      return resolveRef(trimmed);
    })
    .join("\n");
}

export function buildM3uPlaylist(channels, publicBase) {
  const lines = ["#EXTM3U"];
  for (const ch of channels) {
    const safeName = ch.name.replace(/[\r\n]+/g, " ").trim();
    const safeGroup = displayCategoryName(ch.category).replace(/"/g, "'");
    const safeLogo = (ch.logo || "").replace(/"/g, "");
    lines.push(
      `#EXTINF:-1 tvg-id="${ch.id}" tvg-name="${safeName.replace(/"/g, "'")}" tvg-logo="${safeLogo}" group-title="${safeGroup}",${safeName}`
    );
    lines.push(`${publicBase}/api/stream/${encodeURIComponent(ch.id)}.ts`);
  }
  return lines.join("\n") + "\n";
}

// ---------- Upstream Stream Follower (Preserves STB Headers Across 302 Redirects) ----------

function openUpstreamStream(targetUrl, headers = {}, redirectsLeft = 6) {
  return new Promise((resolve, reject) => {
    let u;
    try {
      u = new URL(targetUrl);
    } catch (e) {
      reject(e);
      return;
    }
    const lib = u.protocol === "https:" ? https : http;
    const req = lib.request(
      u,
      {
        method: "GET",
        headers: {
          ...headers,
          Host: u.host
        },
        timeout: 15000
      },
      (res) => {
        if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (redirectsLeft <= 0) {
            reject(new Error("Too many upstream redirects"));
            return;
          }
          const nextUrl = new URL(res.headers.location, u).toString();
          resolve(openUpstreamStream(nextUrl, headers, redirectsLeft - 1));
          return;
        }
        resolve({ req, res, finalUrl: u.toString() });
      }
    );
    req.on("timeout", () => {
      req.destroy(new Error("Upstream connection timed out"));
    });
    req.on("error", reject);
    req.end();
  });
}

// ---------- Gateway State & HTTP Server Factory ----------

export function createGatewayServer(customOpts = {}) {
  const cfg = { ...CONFIG, ...customOpts };
  const stalker = customOpts.stalkerClient || new StalkerClient(cfg);
  const xtream = customOpts.xtreamClient || new XtreamLiveClient(cfg);

  const seedChannels = (() => {
    try {
      return JSON.parse(fs.readFileSync(SEED_PATH, "utf8"));
    } catch {
      return [];
    }
  })();

  const cache = {
    maxtv: { items: [], fetchedAt: 0, error: null, usingSeed: false, inflight: null },
    starshare: { items: [], fetchedAt: 0, error: null, inflight: null }
  };

  async function loadMaxtv(force = false) {
    const now = Date.now();
    if (!force && cache.maxtv.items.length > 0 && !cache.maxtv.usingSeed && now - cache.maxtv.fetchedAt < cfg.catalogueTtlMs) {
      return cache.maxtv.items;
    }
    if (cache.maxtv.inflight) return cache.maxtv.inflight;
    cache.maxtv.inflight = (async () => {
      try {
        const live = await stalker.fetchCatalogue();
        cache.maxtv = { items: live, fetchedAt: Date.now(), error: null, usingSeed: false, inflight: null };
        return live;
      } catch (err) {
        const fallback = cache.maxtv.items.length > 0 ? cache.maxtv.items : seedChannels;
        cache.maxtv = {
          items: fallback,
          fetchedAt: Date.now(),
          error: err?.message || String(err),
          usingSeed: fallback === seedChannels,
          inflight: null
        };
        return fallback;
      }
    })();
    return cache.maxtv.inflight;
  }

  async function loadStarshare(force = false) {
    const now = Date.now();
    if (!force && cache.starshare.items.length > 0 && now - cache.starshare.fetchedAt < cfg.catalogueTtlMs) {
      return cache.starshare.items;
    }
    if (cache.starshare.inflight) return cache.starshare.inflight;
    cache.starshare.inflight = (async () => {
      try {
        const live = await xtream.fetchCatalogue();
        cache.starshare = { items: live, fetchedAt: Date.now(), error: null, inflight: null };
        return live;
      } catch (err) {
        cache.starshare = {
          items: cache.starshare.items,
          fetchedAt: Date.now(),
          error: err?.message || String(err),
          inflight: null
        };
        return cache.starshare.items;
      }
    })();
    return cache.starshare.inflight;
  }

  async function getCombinedChannels(source = "maxtv", force = false) {
    const src = String(source || "maxtv").toLowerCase();
    if (src === "starshare") return loadStarshare(force);
    if (src === "all") {
      const [m, s] = await Promise.all([loadMaxtv(force), loadStarshare(force)]);
      return [...m, ...s];
    }
    return loadMaxtv(force);
  }

  async function findChannelById(rawChannelId) {
    const id = decodeURIComponent(String(rawChannelId || ""))
      .replace(/\.(?:ts|m3u8)$/i, "")
      .trim();
    const list = id.startsWith("xt:")
      ? await loadStarshare(false)
      : id.startsWith("st:")
        ? await loadMaxtv(false)
        : await getCombinedChannels("all", false);
    return (
      list.find((c) => c.id === id || c.rawId === id || `st_${c.rawId}` === id || `xt_${c.rawId}` === id) || null
    );
  }

  function publicBaseOf(req) {
    if (process.env.PUBLIC_BASE_URL) return process.env.PUBLIC_BASE_URL.replace(/\/+$/, "");
    const proto = (req.headers["x-forwarded-proto"] || "http").toString().split(",")[0].trim();
    const host = (req.headers["x-forwarded-host"] || req.headers.host || `localhost:${cfg.port}`)
      .toString()
      .split(",")[0]
      .trim();
    return `${proto}://${host}`;
  }

  function formatChannelDto(ch, base) {
    const encodedId = encodeURIComponent(ch.id);
    const embedUrl = `${base}/embed/${encodedId}`;
    return {
      id: ch.id,
      source: ch.source,
      name: ch.name,
      category: displayCategoryName(ch.category),
      rawCategory: ch.category,
      logo: ch.logo || "",
      embedUrl,
      iframeCode: `<iframe src="${embedUrl}" width="100%" height="100%" frameborder="0" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>`,
      streamUrl: `${base}/api/stream/${encodedId}.ts`
    };
  }

  function setCorsAndEmbedHeaders(res) {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
    res.setHeader("Access-Control-Allow-Headers", "Range, Content-Type, Accept, Origin");
    res.setHeader("Access-Control-Expose-Headers", "Content-Type, Content-Length, X-Stream-Container");
    // Explicitly allow embedding inside any external website (OG website + Arena Live Preview)
    res.setHeader("Content-Security-Policy", "frame-ancestors *;");
  }

  function sendJson(res, status, data) {
    const body = JSON.stringify(data, null, 2);
    res.writeHead(status, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    });
    res.end(body);
  }

  const probeHandoff = new Map();

  async function pipeUpstreamMedia(req, res, upstreamUrl, uaMode, probeOpts = null) {
    const headers =
      uaMode === "xtream"
        ? {
            "User-Agent": XTREAM_USER_AGENT,
            Accept: "*/*",
            "Icy-MetaData": "1"
          }
        : {
            "User-Agent": MAG_USER_AGENT,
            Accept: "*/*"
          };

    const { req: upReq, res: upRes, finalUrl } = await openUpstreamStream(upstreamUrl, headers);
    const status = upRes.statusCode || 502;
    if (status < 200 || status >= 300) {
      upRes.resume();
      sendJson(res, status === 404 ? 404 : 502, { ok: false, error: `Upstream media returned HTTP ${status}` });
      return;
    }

    const contentType = String(upRes.headers["content-type"] || "").toLowerCase();
    const looksHlsByHeader =
      contentType.includes("mpegurl") ||
      finalUrl.split("?")[0].toLowerCase().endsWith(".m3u8");

    // Peek the first chunk so we know for sure whether this Stalker stream is #EXTM3U (HLS) or 0x47 (MPEG-TS)!
    const iterator = upRes[Symbol.asyncIterator]();
    const first = await iterator.next();
    if (first.done || !first.value) {
      sendJson(res, 502, { ok: false, error: "Upstream stream closed immediately" });
      return;
    }
    const firstBuf = Buffer.isBuffer(first.value) ? first.value : Buffer.from(first.value);
    const headAscii = firstBuf.subarray(0, 32).toString("utf8").trimStart();
    const isHls = looksHlsByHeader || headAscii.startsWith("#EXTM3U");

    if (isHls) {
      const chunks = [firstBuf];
      for await (const chunk of { [Symbol.asyncIterator]: () => iterator }) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        if (chunks.reduce((n, b) => n + b.length, 0) > 2 * 1024 * 1024) break;
      }
      const text = Buffer.concat(chunks).toString("utf8");
      const rewritten = rewriteHlsManifest(text, finalUrl, uaMode, publicBaseOf(req), cfg.proxySecret);
      if (probeOpts && probeOpts.isProbe && probeOpts.handoffId) {
        probeHandoff.set(probeOpts.handoffId, { at: Date.now(), kind: "hls", body: rewritten });
      }
      res.writeHead(200, {
        "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
        "Cache-Control": "no-store",
        "X-Stream-Container": "hls"
      });
      res.end(rewritten);
      return;
    }

    if (probeOpts && probeOpts.isProbe) {
      upReq.destroy();
      upRes.destroy();
      if (probeOpts.handoffId) {
        probeHandoff.set(probeOpts.handoffId, { at: Date.now(), kind: "mpegts", resolvedUrl: finalUrl });
      }
      res.writeHead(200, {
        "Content-Type": "video/mp2t",
        "Cache-Control": "no-store",
        "X-Stream-Container": "mpegts"
      });
      res.end();
      return;
    }

    // Raw MPEG-TS live stream — pipe chunks directly to mpegts.js / VLC / browser player!
    res.writeHead(200, {
      "Content-Type": "video/mp2t",
      "Cache-Control": "no-store",
      Connection: "keep-alive",
      "X-Stream-Container": "mpegts"
    });
    const cleanup = () => {
      upReq.destroy();
      upRes.destroy();
    };
    req.on("close", cleanup);
    res.on("close", cleanup);

    res.write(firstBuf);
    try {
      for await (const chunk of { [Symbol.asyncIterator]: () => iterator }) {
        if (res.writableEnded || res.destroyed) break;
        const ok = res.write(chunk);
        if (!ok) await new Promise((r) => res.once("drain", r));
      }
    } catch {}
    if (!res.writableEnded) res.end();
  }

  const server = http.createServer(async (req, res) => {
    setCorsAndEmbedHeaders(res);
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    const reqUrl = new URL(req.url || "/", "http://localhost");
    const pathname = reqUrl.pathname;
    const base = publicBaseOf(req);

    try {
      // 1. Health & status
      if (pathname === "/api/health") {
        await loadMaxtv(false);
        sendJson(res, 200, {
          ok: true,
          service: "STREAMR maxTV Live TV API & Embed Gateway",
          maxtvPortal: cfg.maxtvPortal,
          maxtvChannelsCached: cache.maxtv.items.length,
          maxtvLiveConnected: !cache.maxtv.usingSeed && !cache.maxtv.error,
          maxtvUsingPreviewSeed: cache.maxtv.usingSeed,
          maxtvNotice: cache.maxtv.error || null,
          starshareConfigured: xtream.configured,
          endpoints: {
            channelsJson: `${base}/api/channels?source=maxtv&filter=hindi`,
            categoriesJson: `${base}/api/categories?source=maxtv&filter=hindi`,
            m3uPlaylist: `${base}/api/playlist.m3u?source=maxtv&filter=hindi`,
            iframeEmbedTemplate: `${base}/embed/{channelId}`,
            widgetSdk: `${base}/embed-sdk.js`
          }
        });
        return;
      }

      // 2. Channels JSON API
      if (pathname === "/api/channels") {
        const source = reqUrl.searchParams.get("source") || "maxtv";
        const filter = reqUrl.searchParams.get("filter") || "hindi";
        const q = (reqUrl.searchParams.get("q") || "").trim().toLowerCase();
        const categoryFilter = (reqUrl.searchParams.get("category") || "").trim().toLowerCase();
        const refresh = reqUrl.searchParams.get("refresh") === "1";
        const limit = Math.min(Math.max(Number(reqUrl.searchParams.get("limit") || 1500), 1), 10000);

        const raw = await getCombinedChannels(source, refresh);
        let filtered = raw.filter((c) => keepsByFilter(c.name, c.category, filter));
        if (filtered.length === 0 && filter !== "all") filtered = raw;

        if (categoryFilter) {
          filtered = filtered.filter(
            (c) =>
              displayCategoryName(c.category).toLowerCase() === categoryFilter ||
              c.category.toLowerCase() === categoryFilter
          );
        }
        if (q) {
          const toks = q.split(/\s+/).filter(Boolean);
          filtered = filtered.filter((c) => {
            const hay = `${c.name} ${c.category}`.toLowerCase();
            return toks.every((t) => hay.includes(t));
          });
        }

        filtered.sort((a, b) => {
          const r = categoryRank(a.category) - categoryRank(b.category);
          return r !== 0 ? r : a.name.localeCompare(b.name);
        });

        const categories = [...new Set(filtered.map((c) => displayCategoryName(c.category)))];
        const sliced = filtered.slice(0, limit).map((c) => formatChannelDto(c, base));

        sendJson(res, 200, {
          ok: true,
          source,
          filter,
          totalMatching: filtered.length,
          returned: sliced.length,
          usingPreviewSeed: source !== "starshare" && cache.maxtv.usingSeed,
          categories,
          channels: sliced
        });
        return;
      }

      // 3. Categories JSON API
      if (pathname === "/api/categories") {
        const source = reqUrl.searchParams.get("source") || "maxtv";
        const filter = reqUrl.searchParams.get("filter") || "hindi";
        const raw = await getCombinedChannels(source, false);
        let filtered = raw.filter((c) => keepsByFilter(c.name, c.category, filter));
        if (filtered.length === 0 && filter !== "all") filtered = raw;

        const counts = new Map();
        for (const ch of filtered) {
          const label = displayCategoryName(ch.category);
          counts.set(label, (counts.get(label) || 0) + 1);
        }
        const categories = [...counts.entries()]
          .sort((a, b) => categoryRank(a[0]) - categoryRank(b[0]) || a[0].localeCompare(b[0]))
          .map(([name, count]) => ({ name, count }));

        sendJson(res, 200, { ok: true, source, filter, categories });
        return;
      }

      // 4. Single channel lookup
      if (pathname.startsWith("/api/channels/")) {
        const id = pathname.slice("/api/channels/".length);
        const ch = await findChannelById(id);
        if (!ch) {
          sendJson(res, 404, { ok: false, error: "Channel not found" });
          return;
        }
        sendJson(res, 200, { ok: true, channel: formatChannelDto(ch, base) });
        return;
      }

      // 5. Dynamic M3U Playlist for maxTV & StarShare
      if (pathname === "/api/playlist.m3u" || pathname === "/playlist.m3u") {
        const source = reqUrl.searchParams.get("source") || "maxtv";
        const filter = reqUrl.searchParams.get("filter") || "hindi";
        const raw = await getCombinedChannels(source, false);
        let filtered = raw.filter((c) => keepsByFilter(c.name, c.category, filter));
        if (filtered.length === 0 && filter !== "all") filtered = raw;
        filtered.sort((a, b) => categoryRank(a.category) - categoryRank(b.category) || a.name.localeCompare(b.name));

        const m3u = buildM3uPlaylist(filtered, base);
        res.writeHead(200, {
          "Content-Type": "audio/x-mpegurl; charset=utf-8",
          "Content-Disposition": `inline; filename="streamr-${source}-${filter}.m3u"`,
          "Cache-Control": "no-store"
        });
        res.end(m3u);
        return;
      }

      // 6. Live Stream Proxy (/api/stream/:id)
      if (pathname.startsWith("/api/stream/")) {
        const rawParam = pathname.slice("/api/stream/".length);
        const preferHls = rawParam.toLowerCase().endsWith(".m3u8") || reqUrl.searchParams.get("format") === "m3u8";
        const isProbe = reqUrl.searchParams.get("probe") === "1";
        const ch = await findChannelById(rawParam);
        if (!ch) {
          sendJson(res, 404, { ok: false, error: "Channel not found" });
          return;
        }
        if (!isProbe) {
          const hit = probeHandoff.get(ch.id);
          if (hit) {
            probeHandoff.delete(ch.id);
            if (Date.now() - hit.at < 8000) {
              if (hit.kind === "hls" && hit.body) {
                res.writeHead(200, {
                  "Content-Type": "application/vnd.apple.mpegurl; charset=utf-8",
                  "Cache-Control": "no-store",
                  "X-Stream-Container": "hls"
                });
                res.end(hit.body);
                return;
              }
              if (hit.kind === "mpegts" && hit.resolvedUrl) {
                await pipeUpstreamMedia(req, res, hit.resolvedUrl, ch.source === "starshare" ? "xtream" : "mag");
                return;
              }
            }
          }
        }
        if (ch.source === "starshare") {
          const target = xtream.streamUrl(ch.rawId, preferHls ? "m3u8" : "ts");
          await pipeUpstreamMedia(req, res, target, "xtream", { isProbe, handoffId: ch.id });
          return;
        }
        if (cache.maxtv.usingSeed && String(ch.cmd).includes("localhost/ch/")) {
          sendJson(res, 503, {
            ok: false,
            error:
              "Preview sandbox has outbound IPTV ports restricted; deploy web-embed on Render/Railway/VPS or run `node server.mjs` on an internet-connected host to stream live maxTV channels."
          });
          return;
        }
        const resolvedUrl = await stalker.resolveLink(ch.cmd);
        await pipeUpstreamMedia(req, res, resolvedUrl, "mag", { isProbe, handoffId: ch.id });
        return;
      }

      // 7. Signed HLS Segment / Sub-Manifest Proxy (/api/segment?t=...)
      if (pathname === "/api/segment") {
        const token = reqUrl.searchParams.get("t") || "";
        const verified = verifyProxyToken(token, cfg.proxySecret);
        if (!verified) {
          sendJson(res, 403, { ok: false, error: "Invalid segment token" });
          return;
        }
        await pipeUpstreamMedia(req, res, verified.u, verified.m);
        return;
      }

      // 8. Standalone Embeddable <iframe> Player (/embed/:id)
      if (pathname.startsWith("/embed/")) {
        const rawId = pathname.slice("/embed/".length);
        const ch = await findChannelById(rawId);
        const html = fs.readFileSync(path.join(PUBLIC_DIR, "embed.html"), "utf8");
        const initial = ch
          ? formatChannelDto(ch, base)
          : {
              id: decodeURIComponent(rawId),
              source: "maxtv",
              name: decodeURIComponent(rawId),
              category: "Live TV",
              logo: "",
              embedUrl: `${base}${pathname}`,
              streamUrl: `${base}/api/stream/${encodeURIComponent(decodeURIComponent(rawId))}.ts`
            };
        const injected = html.replace(
          "/*__CHANNEL_BOOT__*/",
          `window.__STREAMR_CHANNEL__ = ${JSON.stringify(initial).replace(/</g, "\\u003c")};`
        );
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
        res.end(injected);
        return;
      }

      // 9. Drop-in <script> Widget SDK (/embed-sdk.js)
      if (pathname === "/embed-sdk.js") {
        const sdk = fs.readFileSync(path.join(PUBLIC_DIR, "embed-sdk.js"), "utf8");
        res.writeHead(200, { "Content-Type": "application/javascript; charset=utf-8", "Cache-Control": "public, max-age=300" });
        res.end(sdk);
        return;
      }

      // 10. Static Assets (/ , /vendor/mpegts.js, /vendor/hls.min.js)
      const safeRel = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
      const resolvedFile = path.resolve(PUBLIC_DIR, safeRel);
      if (resolvedFile.startsWith(PUBLIC_DIR) && fs.existsSync(resolvedFile) && fs.statSync(resolvedFile).isFile()) {
        const ext = path.extname(resolvedFile).toLowerCase();
        const mime =
          ext === ".html"
            ? "text/html; charset=utf-8"
            : ext === ".js"
              ? "application/javascript; charset=utf-8"
              : ext === ".css"
                ? "text/css; charset=utf-8"
                : ext === ".svg"
                  ? "image/svg+xml"
                  : "application/octet-stream";
        res.writeHead(200, { "Content-Type": mime });
        fs.createReadStream(resolvedFile).pipe(res);
        return;
      }

      sendJson(res, 404, { ok: false, error: "Not found" });
    } catch (err) {
      if (!res.headersSent) {
        sendJson(res, 500, { ok: false, error: err?.message || "Internal server error" });
      } else {
        res.end();
      }
    }
  });

  return server;
}

// Start server when executed directly
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const srv = createGatewayServer();
  srv.listen(CONFIG.port, CONFIG.host, () => {
    console.log(
      `[STREAMR maxTV Embed Gateway] Listening on http://${CONFIG.host}:${CONFIG.port} (portal: ${CONFIG.maxtvPortal})`
    );
  });
}
