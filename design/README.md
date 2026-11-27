# AK TV identity — 4.10 "Aurora Glass"

- `ak-tv-logo.png`: the mark (liquid-glass squircle, rose → orchid → iris aurora, AK ligature with a ▶ counter).
- `ak-tv-banner.png`: TV banner artwork (the shipped 320×180 `banner_img.png` is rendered from the same code).
- `ak-tv-mark-transparent.png`: the AK ligature alone, transparent background.

All three, plus the vector drawables in the APK, come from `tools/brand_mark.py`:

    python3 tools/brand_mark.py emit     # vector drawables (aktv_logo, aktv_icon_fg/bg/mono)
    python3 tools/brand_mark.py raster   # PNGs (requires Pillow)

`previews/4.10.0/` holds screens rendered from the production Android views in Robolectric, with
labelled sample content; they are not recordings from a TV. See `docs/ui-liquid-glass-4.10.0.md`.
