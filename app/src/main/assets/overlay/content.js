// Hides the "TGFlix" branding the embedded player draws over the video.
// It looks for any small element (text, image, link, watermark) that carries the name and hides it;
// big containers are never hidden, so the player itself is left alone.
(function () {
  var RE = /tgflix/i;
  var queued = false;

  function mentions(el) {
    var s = (el.alt || '') + ' ' + (el.title || '') + ' ' + (el.id || '') + ' ' +
      (typeof el.className === 'string' ? el.className : '') + ' ' +
      (el.getAttribute && (el.getAttribute('src') || '') + ' ' + (el.getAttribute('href') || '') + ' ' + (el.getAttribute('aria-label') || ''));
    return RE.test(s);
  }
  function hide(el) { el.style.setProperty('display', 'none', 'important'); el.setAttribute('data-aktv-hidden', '1'); }

  function sweep() {
    queued = false;
    var all = document.body ? document.body.querySelectorAll('*') : [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.getAttribute('data-aktv-hidden')) continue;
      var tag = el.tagName;
      if (tag === 'VIDEO' || tag === 'SCRIPT' || tag === 'STYLE') continue;
      var small = (el.textContent || '').length < 60;
      if (mentions(el) && small) { hide(el); continue; }
      if (el.children.length === 0 && RE.test(el.textContent || '')) {
        hide(el);
        var p = el.parentElement;               // hide a wrapper that only held that label
        if (p && p !== document.body && (p.textContent || '').trim().length < 40 && p.querySelector('video') === null) hide(p);
      }
    }
  }
  function queue() { if (!queued) { queued = true; setTimeout(sweep, 150); } }

  function start() {
    sweep();
    new MutationObserver(queue).observe(document.documentElement, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
