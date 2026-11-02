// Read-only browser trace: compare actual source selection/headers with native metadata resolution.
// Never emit stream query values, cookies, key IDs, licence bodies or raw browser console messages.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { compactTrace } = require('./compact-tg-trace.cjs');

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
  if (raw === 'null') return 'null';
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
async function trace(browser, channel, entrypoint = "TG-embedded") {
  const context = await browser.newContext();
  const records = [], metadata = [], outstanding = new Set();
  const startTime = Date.now();
  const record = value => { if (records.length < 90) records.push({ elapsedMs: Date.now() - startTime, ...value }); };
  const requests = new WeakMap();
  let requestNumber = 0;
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
  page.on('request', request => {
    const url = request.url();
    if (!permitted(url) || !/\.(mpd|m3u8|m4s|mp4|ts)(?:$|\?)/i.test(url)) return;
    const id = ++requestNumber;
    requests.set(request, { id, start: Date.now() });
    let frame = '';
    try { frame = new URL(request.frame().url()).origin === JJ ? 'JJTV' : 'TG'; } catch {}
    record({ event: 'media-request', request: id, frame, url: safeUrl(url),
      redirectedFrom: requests.get(request.redirectedFrom())?.id || null });
  });
  page.on('response', response => {
    const work = (async () => {
      const request = response.request();
      const url = request.url();
      const parsed = new URL(url);
      const type = (response.headers()['content-type'] || '').split(';')[0];
      // Metadata had not been included in the earlier media-only traces. Report request
      // policy, not cookie/key documents or response text, to diagnose pre-player HTTP 403.
      let stage = null;
      if (parsed.origin === JJ && parsed.pathname === '/pind') stage = 'player-page';
      else if (parsed.origin === JJ && /\.json$/i.test(parsed.pathname)) stage = 'channel-catalogue';
      else if (/cookie/i.test(parsed.pathname) && allowed.has(parsed.hostname))
        stage = /jstarcookie/i.test(parsed.pathname) ? 'star-cookie' : 'general-cookie';
      if (stage && metadata.length < 16) {
        const headers = await request.allHeaders();
        metadata.push({ stage, url: safeUrl(url), status: response.status(),
          type: /^(?:application\/json|text\/plain|text\/html|application\/javascript)$/.test(type) ? type : 'other',
          origin: headers.origin ? safeUrl(headers.origin) : '',
          referer: headers.referer ? safeUrl(headers.referer) : '',
          fetchMode: headers['sec-fetch-mode'] || '', fetchSite: headers['sec-fetch-site'] || '',
          cookiePresent: !!headers.cookie, authorizationPresent: !!headers.authorization,
          challenge: response.headers()['cf-mitigated'] === 'challenge',
          referrerPolicy: ['no-referrer', 'same-origin', 'strict-origin-when-cross-origin', 'origin'].includes(response.headers()['referrer-policy']) ? response.headers()['referrer-policy'] : '' });
      }
      if (parsed.pathname.includes('/_serverFn/')) {
        try {
          const shape = configShape(await response.json());
          if (shape) record({ event: 'browser-player-config', status: response.status(), ...shape });
        } catch {}
      }
      const media = /\.(mpd|m3u8|m4s|mp4|ts)(?:$|\?)/i.test(url) || /dash\+xml|mpegurl|video\/|audio\//i.test(type);
      if (media && records.length < 45) {
        const headers = await request.allHeaders();
        record({ event: 'media-response', request: requests.get(request)?.id || null,
          durationMs: requests.has(request) ? Date.now() - requests.get(request).start : null,
          url: safeUrl(url), status: response.status(), type,
          tokenSecondsLeft: tokenAge(url), userAgent: (headers['user-agent'] || '').slice(0, 240),
          origin: headers.origin ? safeUrl(headers.origin) : '', referer: headers.referer ? safeUrl(headers.referer) : '' });
      }
    })().catch(() => {});
    outstanding.add(work); work.finally(() => outstanding.delete(work));
  });
  page.on('framenavigated', frame => record({ event: 'frame', url: safeUrl(frame.url()) }));
  page.on('requestfailed', request => {
    if (permitted(request.url()) && /\.mpd|\.m3u8|_serverFn/.test(request.url()) && records.length < 45) {
      const text = request.failure()?.errorText || '';
      record({ event: 'request-failed', url: safeUrl(request.url()), code: text.match(/net::[A-Z_]+/)?.[0] || 'network failure' });
    }
  });
  try {
    await page.goto(entrypoint === 'JJTV-direct' ? JJ + '/pind?id=' + channel : TG + '/watch/' + channel, { waitUntil: 'domcontentloaded', timeout: 30000 });
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
      record({ event: 'state', elapsedSeconds: (round + 1) * 5, frames });
      if (frames.some(f => f.videos.some(v => v.time >= 3 && v.width > 0 && !v.paused))) break;
    }
  } catch (error) { record({ event: 'trace-error', name: error.name }); }
  await Promise.allSettled([...outstanding]);
  await context.close();
  return { channel, entrypoint, videoObserved: lastFrames.some(f => f.videos.some(v => v.time >= 3 && v.width > 0 && !v.paused)),
    visitedJjtv: records.some(record => record.event === 'frame' && record.url.startsWith(JJ)),
    lastFrames, metadata, blockedHosts, blockedNonPlayerRequests: blocked, records };
}
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true,
    args: ['--autoplay-policy=no-user-gesture-required', '--disable-dev-shm-usage'] });
  try {
    // Target the two reported TG Server 1 channels as well as the existing known-good controls.
    const cases = ['165', '156', '892', '872', '1108', '1106', '362'].map(channel => [channel, 'TG-embedded']);
    const results = await Promise.all(cases.map(([channel, entrypoint]) => trace(browser, channel, entrypoint)));
    for (const result of results) {
      const channel = result.channel;
      // Keep the final state and actual requests, not thirteen duplicate video-state snapshots.
      // Seven cases x at most two notices: do not lose the last channel to GitHub's step notice limit.
      const report = compactTrace(result);
      for (let start = 0; start < Math.min(report.length, 6000); start += 3000) {
        const text = report.slice(start, start + 3000).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
        console.log(`::notice title=Browser trace ${result.entrypoint} ${channel} ${start / 3000 + 1}::${text}`);
      }
    }
  } finally { await browser.close(); }
})().catch(error => {
  console.log(`::warning title=Browser trace unavailable::${error.name || 'BrowserError'}; no playback conclusion can be drawn.`);
  process.exitCode = 1;
});
