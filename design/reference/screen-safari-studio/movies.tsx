import { createFileRoute } from "@tanstack/react-router";
import { TopNav } from "@/components/tv/TopNav";
import { Hero, Rail } from "@/components/tv/Rails";
import { titles } from "@/lib/catalog";

export const Route = createFileRoute("/movies")({
  head: () => ({
    meta: [
      { title: "Movies — Streamr" },
      { name: "description", content: "Browse films on Streamr." },
      { property: "og:title", content: "Movies — Streamr" },
      { property: "og:description", content: "Browse films on Streamr." },
    ],
  }),
  component: () => {
    const m = titles.filter((t) => t.kind === "movie");
    return (
      <main className="min-h-screen pb-16">
        <TopNav />
        <Hero />
        <div className="-mt-10 relative space-y-6">
          <Rail label="Blockbusters" items={m} wide />
          <Rail label="Because you watched Echoes of Dust" items={[...m].reverse()} />
        </div>
      </main>
    );
  },
});
