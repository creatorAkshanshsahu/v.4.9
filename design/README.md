# AK TV visual refresh

The user selected **option 2** of the generated AK monogram, retained as `ak-tv-logo.png`.
The transparent mark and TV banner are deterministic derivatives of that selected artwork,
not newly generated alternatives. `tools/build-brand-assets.py` creates the Android-size assets.

- Palette: midnight `#080D14`, aqua `#32E5CE`, ice-white `#EFF6FA`.
- Native UI: compact left rail, generous featured panel, restrained cards, readable focused state.
- Entrance: 1.6-second reveal, any-key/tap skip, reduced-animation support, no audio/flash/particle burst.
- Playback baseline: the working **4.9.18** network/source/DRM flow; new work is UI and stable rendition selection.

## Preview evidence

`previews/home-tv.webp`, `previews/home-phone.webp` and `previews/home-movies.webp`
are rendered from the native Android layouts in Robolectric, using clearly labelled static
sample content (including fictional poster titles). No network or decoded playback is involved.

`previews/direction-*.webp` are the five 4.9.24 redesign direction concepts (plus player
overlay and Movies studies) rendered by `tools/render-ui-directions.py` with the Sora
typefaces and the selected mark; see `docs/ui-directions-4.9.24.md`. They are concepts
for the user's selection, not app screenshots.

`entrance-preview.gif` is a motion illustration of the implemented reveal timing/selected mark,
composited over the native TV layout preview; it is not a recording from a physical TV.
