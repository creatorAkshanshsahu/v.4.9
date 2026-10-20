# Live programme guide sources

The live player continues to prefer the app site's V1 schedule endpoint:

- `https://livetgtv.lovable.app/api/public/guide/{channelId}`

On 20 October 2026, this endpoint returned `{"error":"Channel guide unavailable"}` for sampled live channel IDs 143, 144, 165 and 872. The app therefore keeps the endpoint as its first choice, then falls back to a public JioTV XMLTV feed:

- Project and feed documentation: <https://github.com/mitthu786/tvepg>
- JioTV XMLTV feed: <https://raw.githubusercontent.com/mitthu786/tvepg/main/jiotv/epg.xml.gz>

The feed was last updated on 19 October 2026 when checked. Its 1.8 MB gzip contained 42,468 programme records and current/future listings for sampled channels 143 and 144. The same check found no current or upcoming rows for 165 or 872. That distinction is reflected in the UI: the guide shows actual listings when the feed has them and says when it does not; it never invents a programme. Coverage and freshness depend on the independent feed maintainer.

The app downloads schedule metadata only, caches the compressed file for up to eight hours (using a recent cache offline), parses only near-current listings, and never uses this source for playback, stream URLs, provider credentials, or DRM. V2/24-7 catalogue channels are not mapped to JioTV IDs and continue to report that no schedule feed is available for them.
