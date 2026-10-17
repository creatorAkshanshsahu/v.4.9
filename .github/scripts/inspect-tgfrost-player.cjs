// Read-only feasibility probe for the user-supplied TG Frost site.
// No media files, licence bodies, source tokens or browser storage are saved or printed.
const SITE = 'https://tgfrost.lovable.app';
const { URL } = require('node:url');
const https = require('node:https');

function safeUrl(raw) {
  if (raw === 'null') return 'null';
  try {
    const url = new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return url.protocol;
    let path;
    if (url.origin === SITE) {
      if (/^\/assets\/[a-z0-9_.-]+\.m?js$/i.test(url.pathname)) path = url.pathname;
      else path = url.pathname.split('/').map(part =>
        /^(?:|watch|embed|movie|movies|tv|api|v[1-9]|resolve|resolver|source|sources|stream|streams|proxy|provider|providers|episode|episodes|season|seasons|download|subtitles|[0-9]{1,10})$/.test(part) ? part : '[path redacted]').join('/');
    } else {
      const extension = url.pathname.match(/\.(m3u8|mpd|mp4|m4s|ts|vtt|srt|js)$/i)?.[1]?.toLowerCase();
      path = '/[path redacted]' + (extension ? '.' + extension : '');
    }
    const knownQueries = /^(?:id|tmdb|tmdbId|type|season|episode|s|e|server|source|provider|url|src|file|quality|lang|language|token|auth|expires|signature|sig|hmac|ts|timestamp|range|start|end|referer|referrer|origin|headers|base|hash|payload|key|apikey|api_key|color|progress|subtitle)$/i;
    const names = [...new Set([...url.searchParams.keys()].map(key => knownQueries.test(key) ? key : '[other]'))];
    return url.origin + path + (names.length ? '?' + names.map(key => key + '=[redacted]').join('&') : '');
  } catch { return '[invalid URL]'; }
}
function redactCode(code) {
  return code.replace(/https?:\/\/[^\s"'`<>\\]+/g, safeUrl)
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[JWT redacted]')
    .replace(/\b[0-9a-f]{32,}\b/gi, '[hex redacted]')
    .replace(/(["'`])[A-Za-z0-9_+/=-]{20,}\1/g, '"[opaque literal redacted]"')
    .replace(/(\b(?:authorization|cookie|token|key|kid|keyid|key_id|secret|api_key|apikey|[a-z_$][a-z0-9_$]*(?:token|secret|apikey))\b["']?\s*[:=]\s*)(["'`])[^"'`]*\2/gi, '$1"[credential redacted]"');
}
function sourceShape(value) {
  const fields = new Set(), urls = new Set(), formats = new Set(), headerNames = new Set();
  let objects = 0, arrays = 0, encrypted = false;
  const known = /^(?:sources?|streams?|url|src|file|playlist|manifest|headers?|tracks?|subtitles?|captions?|quality|qualities|resolution|format|type|mimeType|provider|server|data|result|success|error|season|episode|tmdbId|id|drm|licenseUrl|license_url|clearKeys)$/i;
  const urlField = /^(?:url|src|file|playlist|manifest|streamUrl|sourceUrl|downloadUrl|licenseUrl)$/i;
  function visit(node, key = '', depth = 0) {
    if (depth > 7 || objects > 80) return;
    if (Array.isArray(node)) { arrays++; node.slice(0, 10).forEach(item => visit(item, key, depth + 1)); return; }
    if (node && typeof node === 'object') {
      objects++;
      if (/headers?/i.test(key)) {
        for (const name of Object.keys(node)) if (/^(?:origin|referer|user-agent|authorization|cookie|range|accept)$/i.test(name)) headerNames.add(name.toLowerCase());
        return;
      }
      for (const [name, child] of Object.entries(node).slice(0, 60)) {
        if (known.test(name)) fields.add(name);
        if (/^(?:drm|licenseUrl|license_url|clearKeys)$/i.test(name) && child) encrypted = true;
        visit(child, name, depth + 1);
      }
    } else if (typeof node === 'string') {
      if (urlField.test(key) && /^https?:\/\//.test(node)) urls.add(safeUrl(node));
      if (/^(?:type|format|mimeType)$/i.test(key) && /^(?:hls|dash|mp4|video\/[a-z0-9.+-]+|application\/[a-z0-9.+-]+)$/i.test(node)) formats.add(node);
    }
  }
  visit(value);
  return { fields: [...fields], mediaUrls: [...urls].slice(0, 12), formats: [...formats], headerNames: [...headerNames], drmConfigurationPresent: encrypted, objects, arrays };
}
function publicHttps(raw) {
  try {
    const url = new URL(raw), host = url.hostname.toLowerCase();
    return url.protocol === 'https:' && !url.username && !url.password &&
      !/^(?:localhost|\d+\.\d+\.\d+\.\d+|\[)/.test(host) && !/(?:^|\.)(?:localhost|local|internal|lan|home)$/.test(host);
  } catch { return false; }
}

/** Inspect at most 4 KiB, then close the connection. Never buffer or save a complete movie. */
function probeBytes(raw, originalHeaders, redirects = 0) {
  if (!publicHttps(raw)) return Promise.resolve({ error: 'non-public-or-insecure-url' });
  const headers = Object.fromEntries(Object.entries(originalHeaders).filter(([name]) =>
    !['host', 'connection', 'content-length', 'accept-encoding', 'range'].includes(name.toLowerCase())));
  headers.range = 'bytes=0-4095';
  return new Promise(resolve => {
    let completed = false;
    const finish = result => { if (!completed) { completed = true; resolve(result); } };
    const request = https.request(raw, { headers, method: 'GET', timeout: 8000 }, response => {
      const status = response.statusCode;
      if ([301, 302, 303, 307, 308].includes(status) && response.headers.location && redirects < 3) {
        let next;
        try { next = new URL(response.headers.location, raw); }
        catch { finish({ status, error: 'invalid-redirect' }); response.destroy(); return; }
        const nextHeaders = { ...headers };
        if (new URL(raw).origin !== next.origin) {
          delete nextHeaders.authorization; delete nextHeaders.cookie;
          if (nextHeaders.origin) nextHeaders.origin = 'null';
        }
        response.destroy();
        probeBytes(next.href, nextHeaders, redirects + 1).then(result => finish({ ...result, redirected: true })).catch(() => finish({ error: 'redirect-failed' }));
        return;
      }
      let bytesRead = 0, sample = Buffer.alloc(0);
      const result = () => ({ status, type: String(response.headers['content-type'] || '').split(';')[0],
        finalUrl: safeUrl(raw), partialContent: status === 206 || !!response.headers['content-range'], bytesRead,
        mp4Header: sample.length >= 12 && sample.toString('ascii', 4, 8) === 'ftyp',
        hlsHeader: sample.toString('utf8').trimStart().startsWith('#EXTM3U') });
      response.on('data', data => {
        const part = data.subarray(0, Math.max(0, 4096 - bytesRead));
        if (sample.length < 32) sample = Buffer.concat([sample, part.subarray(0, 32 - sample.length)]);
        bytesRead += part.length;
        if (bytesRead >= 4096) { finish(result()); response.destroy(); request.destroy(); }
      });
      response.on('end', () => finish(result()));
      response.on('error', () => finish({ ...result(), interrupted: true }));
    });
    request.on('timeout', () => { finish({ error: 'timeout' }); request.destroy(); });
    request.on('error', error => finish({ error: typeof error.code === 'string' && /^[A-Z0-9_]+$/.test(error.code) ? error.code : 'network-error' }));
    request.end();
  });
}

async function trace(browser, sample) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const started = Date.now();
  const records = [], work = new Set(), scripts = [], metadata = [], blocked = new Set();
  let movieBytesDeclared = 0, sawMedia = false, probesStarted = 0, probesComplete = 0;
  const record = item => { if (records.length < 65) records.push({ elapsedMs: Date.now() - started, ...item }); };
  const trustedScriptHosts = new Set(['tgfrost.lovable.app', 'cdnjs.cloudflare.com', 'cdn.jsdelivr.net', 'unpkg.com']);
  await context.route('**/*', async route => {
    const request = route.request();
    const raw = request.url();
    if (/^(?:blob:|data:)/.test(raw)) return route.continue();
    let url; try { url = new URL(raw); } catch { return route.abort(); }
    const type = request.resourceType();
    const ad = /(?:doubleclick|googlesyndication|google-analytics|googletagmanager|profitablerate|popads|adsterra|monetag|veilimportantly|al5sm)/i.test(url.hostname) || /(?:^\/__l5e\/|^\/~flock)/.test(url.pathname);
    const denied = !publicHttps(raw) || ad || ['image', 'font'].includes(type) ||
      (type === 'document' && url.origin !== SITE) ||
      (type === 'script' && !trustedScriptHosts.has(url.hostname)) ||
      (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && url.origin !== SITE);
    if (denied) {
      if (blocked.size < 30) blocked.add(url.hostname + ':' + type);
      return route.abort('blockedbyclient');
    }
    // Block normal movie/segment downloads. A bounded GET below validates the container and Range
    // response only; this probe intentionally cannot establish decoded browser/native playback.
    const mediaBytes = type === 'media' || /\.(?:mp4|m4s|ts|mkv|webm|aac)(?:$|\?)/i.test(raw);
    if (mediaBytes) {
      sawMedia = true;
      if (probesStarted++ < 3) {
        const headers = await request.allHeaders();
        const probe = await probeBytes(raw, headers);
        record({ event: 'bounded-media-probe', url: safeUrl(raw), probe,
          cookiePresent: !!headers.cookie, authorizationPresent: !!headers.authorization,
          origin: headers.origin ? safeUrl(headers.origin) : '', referer: headers.referer ? safeUrl(headers.referer) : '' });
        probesComplete++;
      }
      return route.abort('blockedbyclient');
    }
    return route.continue();
  });
  page.on('popup', popup => popup.close().catch(() => {}));
  await page.addInitScript(() => {
    window.__movieProbe = [];
    const save = value => { if (window.__movieProbe.length < 20) window.__movieProbe.push(value); };
    document.addEventListener('encrypted', event => save({ event: 'encrypted', initDataType: event.initDataType }), true);
    const original = navigator.requestMediaKeySystemAccess?.bind(navigator);
    if (original) navigator.requestMediaKeySystemAccess = async (system, configs) => {
      save({ event: 'eme', system: ['com.widevine.alpha', 'org.w3.clearkey', 'com.microsoft.playready'].includes(system) ? system : 'other' });
      return original(system, configs);
    };
  });
  page.on('framenavigated', frame => record({ event: 'frame', url: safeUrl(frame.url()), main: frame === page.mainFrame() }));
  page.on('requestfailed', request => {
    if (request.failure()?.errorText?.includes('ERR_BLOCKED_BY_CLIENT')) return;
    if (['fetch', 'xhr', 'media'].includes(request.resourceType())) record({ event: 'failed', url: safeUrl(request.url()),
      error: request.failure()?.errorText?.match(/net::[A-Z_]+/)?.[0] || 'network failure' });
  });
  page.on('response', response => {
    const task = (async () => {
      const request = response.request();
      const url = new URL(request.url());
      const type = (response.headers()['content-type'] || '').split(';')[0].toLowerCase();
      const size = Number(response.headers()['content-length'] || 0);
      if (['fetch', 'xhr'].includes(request.resourceType()) && !/^(?:api|image)\.themoviedb\.org$/.test(url.hostname))
        record({ event: 'api-response', url: safeUrl(url.href), status: response.status(), type });
      const media = /\.(?:mp4|m3u8|mpd|m4s|ts)(?:$|\?)/i.test(request.url()) || /(?:video\/|audio\/|mpegurl|dash\+xml)/i.test(type);
      if (media) {
        sawMedia = true; movieBytesDeclared += Number.isFinite(size) ? size : 0;
        const headers = await request.allHeaders();
        const item = { event: 'media', url: safeUrl(request.url()), status: response.status(), type,
          requestMethod: request.method(), rangeRequested: !!headers.range, rangeResponse: !!response.headers()['content-range'],
          origin: headers.origin ? safeUrl(headers.origin) : '', referer: headers.referer ? safeUrl(headers.referer) : '',
          cookiePresent: !!headers.cookie, authorizationPresent: !!headers.authorization };
        // Only read small playlist text, never movie/segment/licence bodies.
        if (/mpegurl/.test(type) || url.pathname.endsWith('.m3u8')) {
          try {
            const text = await response.text();
            if (text.length < 512 * 1024) item.playlist = { master: text.includes('#EXT-X-STREAM-INF:'),
              encryptionMethods: [...new Set([...text.matchAll(/#EXT-X-KEY:[^\n]*METHOD=([A-Z0-9-]+)/g)].map(match => match[1]))],
              variantCount: (text.match(/#EXT-X-STREAM-INF:/g) || []).length };
          } catch {}
        }
        record(item);
      }
      if (url.origin === SITE && request.resourceType() === 'script' && /\/(?:assets|_app)\//.test(url.pathname) && size < 2 * 1024 * 1024) {
        let text; try { text = await response.text(); } catch { return; }
        if (text.length > 2 * 1024 * 1024) return;
        const relevant = /MovieBox|moviebox|\/api\/|\.loadSource\(|new Hls|hls\.js|videojs|\.m3u8|streamUrl|iframe/i.test(text);
        const imports = [...new Set([...text.matchAll(/["'`]([^"'`\s]+\.m?js)["'`]/g)].map(match => {
          try { return safeUrl(new URL(match[1], url.href).href); } catch { return ''; }
        }).filter(Boolean))].slice(0, 20);
        // Emit feature flags, not minified code: arbitrary source literals may contain API credentials.
        const features = { movieBox: /MovieBox|moviebox/.test(text), hls: /\.loadSource\(|new Hls|hls\.js/.test(text),
          dash: /dashjs|shaka\.Player|application\/dash\+xml/.test(text),
          mediaSource: /MediaSource|HTMLVideoElement|\.src\s*=/.test(text), iframe: /iframe/.test(text) };
        if (scripts.length < 24) scripts.push({ url: safeUrl(url.href), bytes: text.length, relevant, imports, features });
      }
      if (['fetch', 'xhr'].includes(request.resourceType()) && /json/.test(type) && !/^(?:api|image)\.themoviedb\.org$/.test(url.hostname) && size < 2 * 1024 * 1024 && metadata.length < 16) {
        try {
          const shape = sourceShape(await response.json());
          metadata.push({ url: safeUrl(url.href), status: response.status(), ...shape });
        } catch {}
      }
    })().catch(() => {});
    work.add(task); task.finally(() => work.delete(task));
  });
  let state = {};
  try {
    await page.goto(SITE + sample.path, { waitUntil: 'domcontentloaded', timeout: 30000 });
    for (let attempt = 0; attempt < 12; attempt++) {
      await page.waitForTimeout(2500);
      state = await page.evaluate(() => ({
        frostTitle: /TG FROST/i.test(document.title),
        videos: [...document.querySelectorAll('video')].map(video => ({
          sourceKind: video.currentSrc.startsWith('blob:') ? 'blob' : video.currentSrc ? 'url' : 'none',
          ready: video.readyState, currentTime: Math.round(video.currentTime * 10) / 10,
          duration: Number.isFinite(video.duration) ? Math.round(video.duration) : null,
          width: video.videoWidth, height: video.videoHeight, paused: video.paused, error: video.error?.code || null
        })),
        iframeCount: document.querySelectorAll('iframe').length,
        controls: [...document.querySelectorAll('button')].map(button => (button.textContent || button.getAttribute('aria-label') || '').trim())
          .map(text => text.match(/^(?:quality|audio|subtitles?|next episode|server|source|play|1080p|720p|480p)/i)?.[0]).filter(Boolean).slice(0, 20),
        drm: window.__movieProbe || []
      }));
      if (probesComplete > 0 || state.videos.some(video => video.currentTime >= 1 && video.width > 0 && !video.paused)) break;
    }
  } catch (error) { record({ event: 'probe-error', name: error.name }); }
  // Close promptly after the bounded media check. No decoded playback conclusion is drawn.
  await page.evaluate(() => document.querySelectorAll('video').forEach(video => video.pause())).catch(() => {});
  await Promise.race([Promise.allSettled([...work]), new Promise(resolve => setTimeout(resolve, 5000))]);
  await context.close();
  return { sample: sample.name, path: sample.path, sawMediaRequest: sawMedia,
    playbackTested: false, recognizedMediaProbeMaxBytes: 4096,
    state, metadata, records, scripts, blocked: [...blocked], declaredResponseBytes: movieBytesDeclared };
}

async function main() {
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
  const browser = await chromium.launch({ channel: 'chrome', headless: true,
    args: ['--autoplay-policy=no-user-gesture-required', '--disable-dev-shm-usage'] });
  try {
    const samples = [
      { name: 'movie-1288069', path: '/watch/movie/1288069' },
      { name: 'episode-213562-S1E1', path: '/watch/tv/213562/1/1' },
      { name: 'movie-253412', path: '/watch/movie/253412' }
    ];
    const results = await Promise.all(samples.map(sample => trace(browser, sample)));
    const reports = [];
    const assets = new Map();
    for (const result of results) {
      for (const script of result.scripts) if (!assets.has(script.url)) assets.set(script.url, script);
      delete result.scripts;
      reports.push('FROST_PLAYBACK ' + JSON.stringify(result));
    }
    reports.push('FROST_ASSETS ' + JSON.stringify([...assets.values()].filter(asset => asset.relevant)));
    const text = reports.join('\n').slice(0, 24000);
    for (let offset = 0; offset < text.length; offset += 3000) {
      const chunk = text.slice(offset, offset + 3000).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
      console.log(`::notice title=TG Frost inspection ${offset / 3000 + 1}::${chunk}`);
    }
  } finally { await browser.close(); }
}
module.exports = { safeUrl, redactCode, sourceShape, publicHttps };
if (require.main === module) main().catch(error => {
  console.log(`::warning title=TG Frost inspection unavailable::${error.name || 'ProbeError'}; native playback feasibility was not established.`);
  process.exitCode = 1;
});
