/*
 * STREAMR WEB — watch page and embed route.
 *
 * One script drives both: /watch.html?id=… for the site, /embed.html?id=… for a partner iframe. The only
 * difference is which chrome exists in the document, so every element is optional here.
 *
 * Order of business on a tune: resolve the channel, play the pre-roll (never longer than it must be, never
 * blocking on failure), then start the stream. Ads are counted once per tune.
 */
(function () {
  'use strict';

  const cfg = window.STREAMR || {};
  const adCfg = cfg.ads || {};
  const q = Player.parseQuery();
  const isEmbed = document.body.classList.contains('embed');

  const el = (id) => document.getElementById(id);
  const video = el('video');
  const overlay = el('overlay');
  const screen = el('screen');

  function showOverlay(title, body) {
    if (!overlay) return;
    const t = el('overlayTitle') || overlay.querySelector('h3');
    const b = el('overlayBody') || overlay.querySelector('p');
    if (t) t.textContent = title;
    if (b) b.textContent = body || '';
    overlay.classList.add('show');
  }

  function hideOverlay() { if (overlay) overlay.classList.remove('show'); }

  function initials(name) {
    return (name || '?').split(/\s+/).slice(0, 2).map((w) => w[0] || '').join('').toUpperCase();
  }

  function setMeta(channel, channels) {
    if (el('name')) el('name').textContent = channel.name;
    if (el('sub')) {
      el('sub').textContent = [channel.category, channel.live ? 'Live' : 'On demand', channel.type && channel.type.toUpperCase()]
        .filter(Boolean).join(' · ');
    }
    if (el('licenceNote')) el('licenceNote').textContent = channel.licence ? 'Source: ' + channel.licence : '';
    if (el('banner')) {
      if (adCfg.companion) Ads.companion(el('banner'), adCfg);
      else el('banner').remove();
    }
    if (el('others')) {
      const others = el('others');
      others.innerHTML = '';
      channels.filter((c) => c.id !== channel.id).slice(0, 8).forEach((c) => {
        const a = document.createElement('a');
        a.className = 'row-link';
        a.href = 'watch.html?id=' + encodeURIComponent(c.id);
        a.innerHTML = '<span class="dot"></span><span class="name"></span><span class="kind"></span>';
        a.querySelector('.name').textContent = c.name;
        a.querySelector('.kind').textContent = c.live ? 'Live' : 'VOD';
        others.append(a);
      });
    }
    if (el('embedCode')) {
      const link = el('embedCode');
      const url = location.origin + location.pathname.replace(/watch\.html$/, 'embed.html') + '?id=' + encodeURIComponent(channel.id);
      link.textContent = '<iframe src="' + url + '" allow="autoplay; fullscreen" …>';
      link.href = url;
    }
    if (isEmbed && cfg.embed && cfg.embed.showBranding) {
      const credit = document.createElement('div');
      credit.className = 'credit';
      credit.innerHTML = 'Powered by <b>STREAMR</b>';
      screen.append(credit);
    }
  }

  function adShown() {
    try { return sessionStorage.getItem('aktv_ad_shown') === '1'; } catch (_) { return false; }
  }

  function markAdShown() {
    try { sessionStorage.setItem('aktv_ad_shown', '1'); } catch (_) { /* storage blocked in some iframes */ }
  }

  function play(channel, channels) {
    setMeta(channel, channels);
    hideOverlay();
    document.title = channel.name + ' — STREAMR';

    const everyTune = adCfg.prerollOnEveryTune !== false;
    const once = !everyTune && adShown();
    const preroll = once ? Promise.resolve() : Ads.playPreroll(video, {
      tagUrl: adCfg.adTagUrl,
      skipAfter: adCfg.skipAfter,
      flagEl: el('adFlag'),
      countdownEl: el('adCountdown'),
      skipEl: el('adSkip'),
      houseEl: el('houseAd'),
      house: adCfg.house
    });

    preroll.then(() => {
      markAdShown();
      showOverlay('Starting ' + channel.name + '…', '');
      Player.attach(video, channel, {
        onPlaying: () => hideOverlay(),
        onError: (message) => showOverlay('Can\'t play this channel', message)
      });
    });
  }

  Player.loadChannels().then((channels) => {
    if (!channels.length) {
      showOverlay('No channels yet', 'Point STREAMR.sourcesUrl at your catalogue endpoint, or add entries to STREAMR.channels.');
      return;
    }
    const wanted = q.id || (channels[0] && channels[0].id);
    const channel = channels.find((c) => c.id === wanted) || channels[0];
    play(channel, channels);
  });

  // Remote / keyboard on the player: enter toggles, f fullscreen, m mute, arrows seek.
  document.addEventListener('keydown', (e) => {
    if (!video) return;
    const tag = (document.activeElement && document.activeElement.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    switch (e.key) {
      case 'Enter':
      case ' ':
        if (video.paused) video.play().catch(() => {}); else video.pause();
        e.preventDefault();
        break;
      case 'f':
      case 'F':
        if (screen && screen.requestFullscreen) screen.requestFullscreen().catch(() => {});
        break;
      case 'm':
      case 'M':
        video.muted = !video.muted;
        break;
      case 'ArrowRight':
        video.currentTime = Math.min((video.duration || Infinity), video.currentTime + 10);
        e.preventDefault();
        break;
      case 'ArrowLeft':
        video.currentTime = Math.max(0, video.currentTime - 10);
        e.preventDefault();
        break;
      default:
        break;
    }
  });
})();
