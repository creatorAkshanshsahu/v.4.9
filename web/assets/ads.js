/*
 * STREAMR WEB — ad layer.
 *
 * Two paths, both driving the same <video> element:
 *   1. VAST 2.0/3.0 linear pre-roll. The tag is fetched, the first playable <Linear> is chosen, and the
 *      creative plays in the player itself (so a TV browser without IMA support still works). Impression,
 *      start / quartile / complete / skip tracking URIs and the click-through are all honoured.
 *   2. House bumper. Used when there is no tag, no fill, a bad tag, or CORS blocks the XML. Overlay card
 *      with a countdown. Everything is local — no third-party request.
 *
 * Nothing here decides what the ad is worth; that is the ad network's job. This only guarantees the
 * impression is counted once, that the content is not reachable behind a played ad, and that an ad
 * failure never blocks the channel.
 */
(function () {
  'use strict';

  const state = { impressions: [] };

  function fire(url) {
    if (!url) return;
    const src = url.trim();
    if (!src) return;
    state.impressions.push(src);
    // Tracking pixels are fire-and-forget; no-cors keeps an ad server without CORS headers from erroring.
    try { fetch(src, { mode: 'no-cors', keepalive: true, cache: 'no-store' }).catch(() => {}); } catch (_) {}
    try { const img = new Image(); img.referrerPolicy = 'no-referrer'; img.src = src; } catch (_) {}
  }

  function parseDuration(value) {
    if (!value) return 0;
    const m = String(value).trim().match(/^(\d+):(\d{1,2}):(\d{1,2}(?:\.\d+)?)$/);
    if (!m) return 0;
    return (+m[1]) * 3600 + (+m[2]) * 60 + parseFloat(m[3]);
  }

  function pickMedia(files) {
    const playable = files
      .map((f) => ({
        url: (f.url || '').trim(),
        type: (f.type || '').toLowerCase(),
        bitrate: parseInt(f.bitrate || '0', 10) || 0,
        width: parseInt(f.width || '0', 10) || 0
      }))
      .filter((f) => f.url);

    const hls = playable.filter((f) => /mpegurl|m3u8/.test(f.type) || /\.m3u8(\?|$)/i.test(f.url));
    const mp4 = playable.filter((f) => /mp4|webm/.test(f.type) || /\.(mp4|webm)(\?|$)/i.test(f.url));
    const pool = hls.length && window.Hls && window.Hls.isSupported() ? hls : (mp4.length ? mp4 : playable);
    pool.sort((a, b) => (b.width - a.width) || (b.bitrate - a.bitrate));
    return pool[0] || null;
  }

  /** Parse a VAST document into a small linear-ad record, or null when there is no fill. */
  function parseVast(xml) {
    const doc = new DOMParser().parseFromString(xml, 'text/xml');
    if (!doc || doc.querySelector('parsererror')) return null;

    for (const ad of Array.from(doc.querySelectorAll('Ad'))) {
      const linear = ad.querySelector('Linear');
      if (!linear) continue; // skip companion-only / non-linear wrappers
      const media = pickMedia(Array.from(linear.querySelectorAll('MediaFile')).map((el) => ({
        url: el.textContent,
        type: el.getAttribute('type'),
        bitrate: el.getAttribute('bitrate'),
        width: el.getAttribute('width')
      })));
      if (!media) continue;

      const text = (sel) => {
        const el = linear.querySelector(sel);
        return el && el.textContent ? el.textContent.trim() : '';
      };
      const all = (sel) => Array.from(linear.querySelectorAll(sel))
        .map((el) => (el.textContent || '').trim())
        .filter(Boolean);

      const events = {};
      for (const el of Array.from(linear.querySelectorAll('Tracking'))) {
        const name = (el.getAttribute('event') || '').toLowerCase();
        const uri = (el.textContent || '').trim();
        if (!name || !uri) continue;
        (events[name] = events[name] || []).push(uri);
      }

      return {
        kind: 'vast',
        media,
        duration: parseDuration(text('Duration')),
        impressions: all('Impression'),
        events,
        clickThrough: text('ClickThrough'),
        clickTracking: all('ClickTracking'),
        skippable: !!linear.querySelector('Skipoffset') || !!ad.querySelector('Skippable'),
        skipAfter: parseDuration(linear.querySelector('Skipoffset') ? linear.querySelector('Skipoffset').textContent : '')
      };
    }
    return null;
  }

  async function fetchVast(tagUrl, timeoutMs) {
    if (!tagUrl) return null;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs || 4000);
    try {
      const res = await fetch(tagUrl, { signal: ctrl.signal, credentials: 'omit', cache: 'no-store' });
      if (!res.ok) return null;
      return parseVast(await res.text());
    } catch (_) {
      return null; // CORS, offline, timeout — fall through to the house bumper
    } finally {
      clearTimeout(timer);
    }
  }

  function fmt(seconds) {
    const s = Math.max(0, Math.ceil(seconds));
    return s >= 60 ? Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') : s + 's';
  }

  /**
   * Play one pre-roll in `video`, then resolve. Never rejects: a broken ad must not stop the channel.
   * opts: { tagUrl, skipAfter, flagEl, countdownEl, skipEl, houseEl, house }
   */
  function playPreroll(video, opts) {
    const o = opts || {};
    return new Promise((resolve) => {
      let settled = false;
      const finish = () => { if (!settled) { settled = true; cleanup(); resolve(); } };
      const flag = o.flagEl;

      const setFlag = (on) => { if (flag) flag.classList.toggle('show', !!on); };
      const setCountdown = (text) => {
        if (!o.countdownEl) return;
        o.countdownEl.textContent = text || '';
        o.countdownEl.classList.toggle('show', !!text);
      };

      const cleanup = () => {
        setFlag(false);
        setCountdown('');
        if (o.skipEl) { o.skipEl.classList.remove('show'); o.skipEl.onclick = null; }
        if (o.houseEl) o.houseEl.classList.remove('show');
        video.onclick = null;
        video.style.cursor = '';
        video.removeEventListener('timeupdate', onTime);
        video.removeEventListener('ended', onEnded);
        video.removeEventListener('error', finish);
        video.pause();
        video.removeAttribute('src');
        video.load();
      };

      let ad = null;
      let clicked = false;
      let quartiles = {};
      let onTime = () => {};
      let onEnded = finish;

      const house = () => {
        const cfg = o.house;
        if (!cfg || !cfg.enabled || !o.houseEl) { finish(); return; }
        const el = o.houseEl;
        el.innerHTML = '';
        const h = document.createElement('h4'); h.textContent = cfg.headline || 'Advertisement';
        const p = document.createElement('p'); p.textContent = cfg.body || '';
        el.append(h, p);
        if (cfg.href) {
          const a = document.createElement('a');
          a.href = cfg.href; a.target = '_blank'; a.rel = 'noopener noreferrer';
          a.textContent = cfg.cta || 'Learn more';
          el.append(a);
        }
        el.classList.add('show');
        setFlag(true);
        let left = Math.max(1, cfg.seconds || 5);
        setCountdown('Ad · ' + left + 's');
        const tick = setInterval(() => {
          left -= 1;
          if (left <= 0) {
            clearInterval(tick);
            setCountdown('');
            finish();
          } else {
            setCountdown('Ad · ' + left + 's');
          }
        }, 1000);
        const stopAll = () => clearInterval(tick);
        video.addEventListener('play', stopAll, { once: true });
      };

      const runAd = () => {
        setFlag(true);
        ad.impressions.forEach(fire);
        (ad.events['creativeview'] || []).forEach(fire);
        (ad.events['start'] || []).forEach(fire);

        let skipTimer = null;
        if (ad.skippable && o.skipEl) {
          const after = ad.skipAfter || o.skipAfter || 5;
          o.skipEl.textContent = 'Skip ad';
          o.skipEl.classList.remove('show');
          skipTimer = setTimeout(() => { o.skipEl.classList.add('show'); }, after * 1000);
          o.skipEl.onclick = () => {
            (ad.events['skip'] || []).forEach(fire);
            setFlag(false);
            finish();
          };
        }

        const src = ad.media.url;
        const isHls = /mpegurl|m3u8/.test(ad.media.type) || /\.m3u8(\?|$)/i.test(src);
        if (isHls && window.Hls && window.Hls.isSupported()) {
          const hls = new Hls({ enableWorker: false });
          hls.on(Hls.Events.ERROR, () => { hls.destroy(); house(); });
          hls.loadSource(src);
          hls.attachMedia(video);
          window.__aktvAdHls = hls;
        } else {
          video.src = src;
        }
        video.muted = false;
        video.play().catch(() => { /* autoplay blocked: drop to the house bumper rather than stalling */ house(); });

        if (ad.clickThrough) {
          video.style.cursor = 'pointer';
          video.onclick = () => {
            if (clicked) return;
            clicked = true;
            ad.clickTracking.forEach(fire);
            window.open(ad.clickThrough, '_blank', 'noopener');
          };
        }

        onTime = () => {
          const total = ad.duration || video.duration || 0;
          if (!total || !isFinite(total)) return;
          const pct = video.currentTime / total;
          const marks = { firstquartile: 0.25, midpoint: 0.5, thirdquartile: 0.75 };
          for (const [name, at] of Object.entries(marks)) {
            if (pct >= at && !quartiles[name]) {
              quartiles[name] = true;
              (ad.events[name] || []).forEach(fire);
            }
          }
          setCountdown('Ad · ' + fmt(Math.max(0, total - video.currentTime)));
        };
        onEnded = () => {
          (ad.events['complete'] || []).forEach(fire);
          if (window.__aktvAdHls) { window.__aktvAdHls.destroy(); window.__aktvAdHls = null; }
          video.onclick = null;
          video.style.cursor = '';
          finish();
        };
        video.addEventListener('timeupdate', onTime);
        video.addEventListener('ended', onEnded);
      };

      video.addEventListener('error', finish);
      fetchVast(o.tagUrl, 4000).then((parsed) => {
        if (settled) return;
        if (!parsed) { house(); return; }
        ad = parsed;
        try { runAd(); } catch (_) { house(); }
      });
    });
  }

  /** Fill a companion/banner slot with a house card, or leave the placeholder text. */
  function companion(el, cfg) {
    if (!el || !cfg || !cfg.enabled) return;
    el.innerHTML = '';
    el.style.borderStyle = 'solid';
    el.style.borderColor = 'rgba(224,36,58,0.35)';
    el.style.background = 'linear-gradient(120deg, rgba(196,22,28,0.18), rgba(27,30,38,0.9))';
    const a = document.createElement('a');
    a.href = (cfg.house && cfg.house.href) || '#';
    a.target = '_blank';
    a.rel = 'noopener noreferrer';
    a.style.cssText = 'display:flex;flex-direction:column;gap:2px;align-items:center;color:#F5F5F5;text-transform:none;letter-spacing:0;';
    const strong = document.createElement('strong');
    strong.textContent = (cfg.house && cfg.house.headline) || 'Advertisement';
    strong.style.fontSize = '13.5px';
    const small = document.createElement('span');
    small.textContent = (cfg.house && cfg.house.cta) || 'Learn more';
    small.style.cssText = 'font-size:11.5px;color:#E8A7AC;text-transform:uppercase;letter-spacing:0.12em;';
    a.append(strong, small);
    el.append(a);
  }

  window.Ads = { playPreroll, companion, parseVast, fetchVast, impressions: () => state.impressions.slice() };
})();
