import { createFileRoute } from "@tanstack/react-router";
import { TopNav } from "@/components/tv/TopNav";
import { Hero, Rail } from "@/components/tv/Rails";
import { titles } from "@/lib/catalog";

export const Route = createFileRoute("/")({
  head: () => ({
    meta: [
      { title: "Streamr — TV Home" },
      { name: "description", content: "Streaming TV app home with movies, series and live channels." },
      { property: "og:title", content: "Streamr — TV Home" },
      { property: "og:description", content: "Streaming TV app home with movies, series and live channels." },
    ],
  }),
  component: Home,
});

function Home() {
  return (
    <main className="min-h-screen pb-16">
      <TopNav />
      <Hero />
      <div className="-mt-10 relative space-y-6">
        <Rail label="Continue Watching" items={titles.filter((t) => t.progress)} wide />
        <Rail label="Trending Now" items={titles} />
        <Rail label="New Series" items={titles.filter((t) => t.kind === "series")} />
        <Rail label="Top Movies" items={titles.filter((t) => t.kind === "movie")} wide />
      </div>
    </main>
  );
}
