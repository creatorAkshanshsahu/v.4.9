// Read-only browser trace: compare actual source selection/headers with native metadata resolution.
// Never emit stream query values, cookies, key IDs, licence bodies or raw browser console messages.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const TG = 'https://livetgtv.lovable.app';
const JJ = 'https://jjtvxweb.pages.dev';
const allowed = new Set(['livetgtv.lovable.app', 'jjtvxweb.pages.dev', 'allinonereborn2.online',
  'cdnjs.cloudflare.com', 'cdn.jsdelivr.net']);
function permitted(raw) {
  try {
    const url = new URL(raw);
    return url.protocol === 'https:' && (allowed.has(url.hostname) || url.hostname.endsWith('.cdn.jio.com'));
  } catch { return raw.startsWith('blob:') || raw.startsWith('data:') || raw === 'about:blank'; }
}
function safeUrl(raw) {
  try {
    const url = new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return url.protocol;
    const path = url.pathname.replace(/[A-Za-z0-9_+=%-]{50,}/g, '[opaque]');
    return url.origin + path + (url.search ? '?' + [...new Set(url.searchParams.keys())].map(k => k + '=[redacted]').join('&') : '');
  } catch { return '[invalid URL]'; }
}
function tokenAge(raw) {
  try {
    const token = new URL(raw).searchParams.get('__hdnea__') || '';
    const exp = token.match(/(?:^|~)exp=(\d+)(?:~|$)/);
    return exp ? Math.floor(Number(exp[1]) - Date.now() / 1000) : null;
  } catch { return null; }
}
function configShape(value) {
  const config = value && (value.result || value.data || value);
  if (!config || typeof config !== 'object' || !Array.isArray(config.urls)) return null;
  return { id: String(config.id || ''), sources: [config, ...(config.backups || [])].map((source, index) => ({
    index: index + 1, upstreamLabel: typeof source.label === 'string' ? source.label.slice(0, 40) : '',
    urls: (source.urls || []).map(safeUrl), cookiePresent: !!source.cookie,
    singleKeyPresent: !!(source.keyId && source.key), clearKeyCount: Object.keys(source.clearKeys || {}).length,
    keyEntryCount: Array.isArray(source.keys) ? source.keys.length : 0
  })) };
}
async function trace(browser, channel) {
  const context = await browser.newContext();
  const records = [], outstanding = new Set();
  let blocked = 0;
  const blockedHosts = {};
  let lastFrames = [];
  await context.route('**/*', route => {
    const request = route.request();
    if (!permitted(request.url()) || ['image', 'font'].includes(request.resourceType())) {
      blocked++;
      try { const host = new URL(request.url()).hostname; if (Object.keys(blockedHosts).length < 20 || host in blockedHosts) blockedHosts[host] = (blockedHosts[host] || 0) + 1; } catch {}
      return route.abort('blockedbyclient');
    }
    return route.continue();
  });
  await context.addInitScript(() => {
    window.__nativeComparison = [];
    const save = value => { if (window.__nativeComparison.length < 30) window.__nativeComparison.push(value); };
    if (navigator.requestMediaKeySystemAccess) {
      const original = navigator.requestMediaKeySystemAccess.bind(navigator);
      navigator.requestMediaKeySystemAccess = async (system, configs) => {
        save({ event: 'key-system', system, initTypes: configs.map(c => c.initDataTypes || []) });
        try { return await original(system, configs); }
        catch (error) { save({ event: 'key-system-failed', name: error.name }); throw error; }
      };
    }
    if (typeof MediaKeySession !== 'undefined') {
      const generate = MediaKeySession.prototype.generateRequest;
      MediaKeySession.prototype.generateRequest = function(type, data) {
        let count = null;
        if (type === 'keyids') { try { count = JSON.parse(new TextDecoder().decode(data)).kids.length; } catch {} }
        save({ event: 'init-data', type, keyIdCount: count });
        return generate.call(this, type, data);
      };
      const update = MediaKeySession.prototype.update;
      MediaKeySession.prototype.update = function(data) {
        let count = null;
        try { const value = JSON.parse(new TextDecoder().decode(data)); count = Array.isArray(value.keys) ? value.keys.length : null; } catch {}
        save({ event: 'licence-update', keyCount: count });
        this.addEventListener('keystatuseschange', () => save({ event: 'key-status', statuses: [...this.keyStatuses.values()] }), { once: true });
        return update.call(this, data);
      };
    }
  });
  const page = await context.newPage();
  page.on('response', response => {
    const work = (async () => {
      const request = response.request();
      const url = request.url();
      const parsed = new URL(url);
      const type = (response.headers()['content-type'] || '').split(';')[0];
      if (parsed.pathname.includes('/_serverFn/')) {
        try {
          const shape = configShape(await response.json());
          if (shape) records.push({ event: 'browser-player-config', status: response.status(), ...shape });
        } catch {}
      }
      const media = /\.(mpd|m3u8|m4s|mp4|ts)(?:$|\?)/i.test(url) || /dash\+xml|mpegurl|video\/|audio\//i.test(type);
      if (media && records.length < 45) {
        const headers = await request.allHeaders();
        records.push({ event: 'media-response', url: safeUrl(url), status: response.status(), type,
          tokenSecondsLeft: tokenAge(url), userAgent: (headers['user-agent'] || '').slice(0, 240),
          origin: headers.origin ? safeUrl(headers.origin) : '', referer: headers.referer ? safeUrl(headers.referer) : '' });
      }
    })().catch(() => {});
    outstanding.add(work); work.finally(() => outstanding.delete(work));
  });
  page.on('framenavigated', frame => records.push({ event: 'frame', url: safeUrl(frame.url()) }));
  page.on('requestfailed', request => {
    if (permitted(request.url()) && /\.mpd|\.m3u8|_serverFn/.test(request.url()) && records.length < 45) {
      const text = request.failure()?.errorText || '';
      records.push({ event: 'request-failed', url: safeUrl(request.url()), code: text.match(/net::[A-Z_]+/)?.[0] || 'network failure' });
    }
  });
  try {
    await page.goto(TG + '/watch/' + channel, { waitUntil: 'domcontentloaded', timeout: 30000 });
    for (let round = 0; round < 13; round++) {
      await page.waitForTimeout(5000);
      const frames = [];
      for (const frame of page.frames()) {
        if (!permitted(frame.url())) continue;
        try {
          const state = await frame.evaluate(() => ({
            servers: [...document.querySelectorAll('button')].map(b => b.textContent.trim()).filter(t => /^Server \d+$/.test(t)),
            videos: [...document.querySelectorAll('video')].map(v => ({ ready: v.readyState, time: Math.round(v.currentTime * 10) / 10,
              paused: v.paused, width: v.videoWidth, height: v.videoHeight, error: v.error?.code || null })),
            drm: window.__nativeComparison || []
          }));
          frames.push({ url: safeUrl(frame.url()), ...state });
        } catch {}
      }
      lastFrames = frames;
      records.push({ event: 'state', elapsedSeconds: (round + 1) * 5, frames });
      if (frames.some(f => f.videos.some(v => v.time >= 3 && v.width > 0 && !v.paused))) break;
    }
  } catch (error) { records.push({ event: 'trace-error', name: error.name }); }
  await Promise.allSettled([...outstanding]);
  await context.close();
  return { channel, videoObserved: lastFrames.some(f => f.videos.some(v => v.time >= 3 && v.width > 0 && !v.paused)),
    lastFrames, blockedHosts, blockedNonPlayerRequests: blocked, records };
}
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true,
    args: ['--autoplay-policy=no-user-gesture-required', '--disable-dev-shm-usage'] });
  try {
    const results = await Promise.all(['1106', '872'].map(channel => trace(browser, channel)));
    for (const result of results) {
      const channel = result.channel;
      const report = JSON.stringify(result);
      for (let start = 0; start < Math.min(report.length, 12000); start += 3000) {
        const text = report.slice(start, start + 3000).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
        console.log(`::notice title=Browser trace ${channel} ${start / 3000 + 1}::${text}`);
      }
    }
  } finally { await browser.close(); }
})().catch(error => {
  console.log(`::warning title=Browser trace unavailable::${error.name || 'BrowserError'}; no playback conclusion can be drawn.`);
  process.exitCode = 1;
});
