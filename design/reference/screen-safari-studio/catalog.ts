export type Title = { id: string; name: string; year: number; tag: string; hue: number; kind: "movie" | "series"; progress?: number | undefined };

const mk = (id: string, name: string, year: number, tag: string, hue: number, kind: Title["kind"], progress?: number): Title => ({ id, name, year, tag, hue, kind, progress });

export const titles: Title[] = [
  mk("dune-echo", "Echoes of Dust", 2026, "Sci‑Fi", 40, "movie", 62),
  mk("north", "The North Line", 2025, "Thriller", 210, "series", 30),
  mk("velvet", "Velvet Hour", 2024, "Drama", 330, "movie"),
  mk("kiln", "Kiln", 2026, "Mystery", 20, "series", 80),
  mk("orbit", "Low Orbit", 2025, "Sci‑Fi", 250, "movie"),
  mk("saltwater", "Saltwater", 2023, "Adventure", 190, "series"),
  mk("neon", "Neon Saints", 2026, "Crime", 300, "series", 15),
  mk("meadow", "After the Meadow", 2024, "Romance", 120, "movie"),
  mk("atlas", "Atlas Falls", 2025, "Action", 60, "movie"),
  mk("quiet", "Quiet Hours", 2026, "Horror", 0, "series"),
];

export const channels = [
  { no: 101, name: "Nova News", now: "World Tonight", next: "Market Wrap", hue: 220, pct: 45 },
  { no: 102, name: "Arena Sports", now: "Champions League Live", next: "Post‑match", hue: 140, pct: 70 },
  { no: 103, name: "CineMax One", now: "Atlas Falls", next: "Low Orbit", hue: 30, pct: 20 },
  { no: 104, name: "Kids Planet", now: "Tiny Rockets", next: "Ocean Pals", hue: 300, pct: 55 },
  { no: 105, name: "Wild Earth", now: "Savanna Nights", next: "Deep Blue", hue: 100, pct: 85 },
  { no: 106, name: "Tune TV", now: "Top 40 Countdown", next: "Unplugged", hue: 350, pct: 10 },
];

export const poster = (hue: number) =>
  `linear-gradient(160deg, oklch(0.55 0.15 ${hue}) 0%, oklch(0.25 0.08 ${hue + 30}) 60%, oklch(0.12 0.02 ${hue}) 100%)`;
