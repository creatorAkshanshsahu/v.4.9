# AK TV design correction — 4.9.29

The last implementation drifted from the selected concept images. This correction removes the boxed mark that caused the largest logo mismatch: the real vector is now a transparent, wide AK/play monogram with cyan A, violet K and a small amber-outlined play cutout, matching the approved entrance preview more closely.

The CI now exports screenshots rendered from the actual Android views (`brand-mark.webp`, `entrance.webp`, `home-tv.webp`, `home-phone.webp`, `home-movies.webp`) as the `AK-TV-native-ui-previews` artifact. These are implementation previews, not AI concept images. The home preview sample has been updated to the selected cyan focus / dark cinematic direction, and the actual native player layouts remain in the same build.
