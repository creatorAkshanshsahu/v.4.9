# AK TV redesign direction — 4.9.28

This revision follows the user's selected visual previews in `design/previews/4.9.28/` and returns to the bold, legible AK/play identity they liked from 4.9.21. It uses original artwork and styling rather than copying another streaming service.

## Selected concepts

- `identity-entrance.png`: cyan-to-violet AK mark, small warm play accent, quiet centered entrance.
- `home-screen.png`: ink-black cinematic hero, rail navigation, large featured title, visible Live and poster shelves, crisp cyan focus.
- `live-player.png`: full-picture live player with an unobtrusive lower-third, now/next progress and remote-friendly Guide/Source/Quality/Retry actions.
- `vod-player.png`: artwork-first movie/series playback with a title header, native transport, episode/audio/subtitle/source controls in an accessible lower dock.

## Native implementation

The preview images are design references, not packaged media. Home shelves use the real channel catalogue and TMDB metadata/artwork; they react to channel, movie and series focus. Live playback and VOD remain native Media3. The custom player dock acts on the native player and keeps its native timeline/transport visible. The entrance remains skippable, uses the saved sound toggle, routes sound through the media stream and respects mute/silent settings.
