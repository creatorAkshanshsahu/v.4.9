# Visual reference: screen-safari-studio

Vendored from `github.com/creatorAkshanshsahu/screen-safari-studio` @ `ad4db5e`, the design the app is
being rebuilt against. Only the files that define the look are kept: the design tokens, the two TV
components, the five routes, and the sample catalogue.

It is a five-route TanStack Start app, so there is far less here than "whole app" implies — every screen
in it is one of: home (hero + four rails), movies, series, live player with a channel guide, and the
`watch/$id` player. `src/components/ui/**` is stock shadcn and is not copied.

Numbers that matter (see `styles.css`): background `oklch(0.14 0.01 260)`, card `oklch(0.2 0.012 260)`,
primary near-white, accent `oklch(0.72 0.15 55)`, live `oklch(0.62 0.23 25)`, radius 12, bar `px-12 py-6`,
hero full-bleed `70vh`, rail `px-12 gap-5` pulled up `-mt-10`, focus = white 3px ring + `scale(1.06)`.
