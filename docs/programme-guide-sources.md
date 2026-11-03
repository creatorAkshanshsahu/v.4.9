# Live programme guide sources

The live player now uses the community JioTV XMLTV feed as its main schedule source:

- Project and feed documentation: <https://github.com/mitthu786/tvepg>
- JioTV XMLTV feed: <https://raw.githubusercontent.com/mitthu786/tvepg/main/jiotv/epg.xml.gz>

The app matches the feed's numeric JioTV channel IDs to V1 live-channel IDs. It uses the app site's V1 schedule endpoint only when this feed has no current or upcoming listing for the selected channel:

- `https://livetgtv.lovable.app/api/public/guide/{channelId}`

On 20 October 2026, the TG endpoint returned `{"error":"Channel guide unavailable"}` for sampled IDs 143, 144, 165 and 872. The community feed was updated daily by its repository workflow; one 1.8 MB gzip contained 42,468 programme records. At 17:57 UTC it had active rows for 143 and 144, but none for 165 or 872. By 18:58 UTC its latest programme had ended at 18:29 UTC and those sampled channels had no current or upcoming row. This feed is useful, but its once-daily refresh can leave gaps and channel coverage is not guaranteed. The UI therefore displays only actual listings and reports when none are available.

The app checks for feed updates at launch and while open, on its five-minute background refresh cadence. The compressed file is refreshed when at least eight hours old, using a recent cached copy offline; when a new file lands, the visible V1 guide is reloaded. Parsing retains only near-current listings. Schedule metadata is never used for playback, stream URLs, provider credentials, or DRM. V2/24-7 catalogue channels are not mapped to JioTV IDs and continue to report that no schedule feed is available for them.
