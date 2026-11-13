/*
 * STREAMR WEB — one config file for the site, the watch page and the embed.
 *
 * Nothing in this build points at a scraped source. `channels` below are Creative-Commons test
 * assets so the player, the ad slots and the embed route can be exercised end to end. To go live
 * you set `sourcesUrl` to a JSON endpoint you control (see sources.sample.json for the contract),
 * or fill `channels` by hand with streams you are licensed to distribute.
 */
window.STREAMR = {
  brand: 'STREAMR',
  tagline: 'Live channels and films, on any screen.',
  apk: 'https://github.com/creatorAkshanshsahu/v.4.9/releases/download/latest/app-latest.apk',

  /* Your backend. Expected: {"channels":[ … ]} — same shape as sources.sample.json.
     Leave empty to use the built-in demo list. */
  sourcesUrl: '',
  sourcesHeaders: {},

  /* Ads ------------------------------------------------------------------ */
  ads: {
    /* VAST 2.0/3.0 linear tag. Empty string = house bumper only. */
    adTagUrl: '',
    /* Shown when there is no tag, no fill, or the tag fails. */
    house: {
      enabled: true,
      seconds: 5,
      headline: 'Your ad here',
      body: 'Reach every viewer of this channel.',
      cta: 'Advertise',
      href: 'mailto:ads@example.com'
    },
    /* Ask for a pre-roll before every tune, or only once per session. */
    prerollOnEveryTune: true,
    /* Skip button appears after this many seconds of a linear ad. 0 = never skippable. */
    skipAfter: 5,
    /* Companion banner under the player. */
    companion: true
  },

  /* Embedding -------------------------------------------------------------- */
  /* Who may frame /embed.html. Keep in sync with web/_headers (CSP frame-ancestors).
     A page cannot grant this to itself — the response header is what browsers obey. */
  embed: {
    allowedParents: ["'self'"],   // e.g. ["'self'","https://partner.example"]
    allowFullscreen: true,
    showBranding: true
  },

  /* Demo channels — public, CC-licensed / vendor test streams. Replace all of them. */
  channels: [
    {
      id: 'bbb',
      name: 'Big Buck Bunny',
      category: 'Films',
      logo: '',
      type: 'hls',
      url: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
      licence: 'CC BY 3.0 — Blender Foundation',
      live: false
    },
    {
      id: 'sintel',
      name: 'Sintel',
      category: 'Films',
      logo: '',
      type: 'dash',
      url: 'https://bitmovin-a.akamaihd.net/content/sintel/sintel.mpd',
      licence: 'CC BY 3.0 — Blender Foundation',
      live: false
    },
    {
      id: 'tears',
      name: 'Tears of Steel',
      category: 'Films',
      logo: '',
      type: 'hls',
      url: 'https://test-streams.mux.dev/test_001/stream.m3u8',
      licence: 'CC BY 3.0 — Blender Foundation',
      live: false
    }
  ]
};
