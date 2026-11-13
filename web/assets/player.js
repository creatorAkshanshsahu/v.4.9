/*
 * STREAMR WEB — playback.
 *
 * HLS through hls.js (or the browser's own HLS on Safari/iOS), DASH through dash.js, progressive files
 * straight into the element. Widevine is wired but inert unless the channel carries a licence server you
 * are entitled to use — the licence URL is never guessed, and there is no key extraction anywhere here.
 */
(function () {
  'use strict';

  function scriptOnce(src) {
    return new Promise((resolve, reject) => {
      const existing = document.querySelector('script[data-src="' + src + '"]');
      if (existing) { existing.dataset.ready ? resolve() : existing.addEventListener('load', () => resolve()); return; }
      const el = document.createElement('script');
      el.src = src;
      el.dataset.src = src;
      el.crossOrigin = 'anonymous';
      el.addEventListener('load', () => { el.dataset.ready = '1'; resolve(); });
      el.addEventListener('error', () => reject(new Error('Could not load ' + src)));
      document.head.append(el);
    });
  }

  const CDN = {
    hls: 'https://cdn.jsdelivr.net/npm/hls.js@1.5.13/dist/hls.min.js',
    dash: 'https://cdn.jsdelivr.net/npm/dashjs@4.7.4/dist/dash.all.min.js'
  };

  function widevine(channel) {
    const drm = channel && channel.drm;
    const wv = drm && drm.widevine;
    return wv && wv.licenseUrl ? wv : null;
  }

  /**
   * Start `channel` in `video`. Returns { destroy() }.
   * opts: { onError(message), onPlaying() }
   */
  function attach(video, channel, opts) {
    const o = opts || {};
    let hls = null;
    let dash = null;
    let destroyed = false;

    const fail = (message) => { if (!destroyed && o.onError) o.onError(message); };

    const type = channel.type || (channel.url.indexOf('.mpd') > -1 ? 'dash' : 'hls');

    function progressive() {
      video.src = channel.url;
      video.play().catch(() => {});
    }

    function startHls() {
      if (video.canPlayType('application/vnd.apple.mpegurl')) {
        video.src = channel.url;
        video.play().catch(() => {});
        return;
      }
      scriptOnce(CDN.hls).then(() => {
        if (destroyed) return;
        if (!(window.Hls && window.Hls.isSupported())) { fail('This browser cannot play HLS.'); return; }
        const wv = widevine(channel);
        hls = new Hls(wv ? {
          emeEnabled: true,
          drmSystems: { 'com.widevine.alpha': { licenseUrl: wv.licenseUrl, licenseHeaders: wv.headers || {} } }
        } : {});
        hls.on(Hls.Events.ERROR, (_e, data) => {
          if (!data || !data.fatal) return;
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR && data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR) {
            fail('The channel did not answer. It may be offline, or refused this page.');
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            hls.recoverMediaError();
          } else {
            fail('Playback stopped: ' + (data.details || 'unknown error'));
          }
        });
        hls.loadSource(channel.url);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
      }).catch(() => fail('The player library could not be loaded.'));
    }

    function startDash() {
      scriptOnce(CDN.dash).then(() => {
        if (destroyed) return;
        if (!window.dashjs) { fail('The DASH player could not be loaded.'); return; }
        dash = window.dashjs.MediaPlayer().create();
        const wv = widevine(channel);
        if (wv) {
          dash.setProtectionData({ 'com.widevine.alpha': { serverURL: wv.licenseUrl, httpRequestHeaders: wv.headers || {} } });
        }
        dash.on('error', (e) => fail('Playback stopped: ' + ((e && e.error && e.error.message) || 'stream error')));
        dash.initialize(video, channel.url, true);
      }).catch(() => fail('The DASH library could not be loaded.'));
    }

    if (type === 'dash') startDash();
    else if (type === 'hls') startHls();
    else progressive();

    if (o.onPlaying) video.addEventListener('playing', o.onPlaying, { once: true });

    return {
      destroy() {
        destroyed = true;
        video.removeEventListener('playing', o.onPlaying || (() => {}));
        try { if (hls) hls.destroy(); } catch (_) {}
        try { if (dash) dash.reset(); } catch (_) {}
        try { video.pause(); video.removeAttribute('src'); video.load(); } catch (_) {}
      }
    };
  }

  function parseQuery() {
    const out = {};
    new URLSearchParams(location.search).forEach((value, key) => { out[key] = value; });
    return out;
  }

  async function loadChannels() {
    const cfg = window.STREAMR || {};
    if (cfg.sourcesUrl) {
      try {
        const res = await fetch(cfg.sourcesUrl, { headers: cfg.sourcesHeaders || {}, cache: 'no-store' });
        if (res.ok) {
          const body = await res.json();
          const list = Array.isArray(body) ? body : (body.channels || []);
          if (list.length) return list;
        }
      } catch (_) { /* fall through to the built-in list */ }
    }
    return (cfg.channels || []).slice();
  }

  window.Player = { attach, loadChannels, parseQuery };
})();
