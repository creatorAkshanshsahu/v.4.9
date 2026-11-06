import { createFileRoute } from "@tanstack/react-router";
import { TopNav } from "@/components/tv/TopNav";
import { Hero, Rail } from "@/components/tv/Rails";
import { titles } from "@/lib/catalog";

export const Route = createFileRoute("/series")({
  head: () => ({
    meta: [
      { title: "Series — Streamr" },
      { name: "description", content: "Browse TV series on Streamr." },
      { property: "og:title", content: "Series — Streamr" },
      { property: "og:description", content: "Browse TV series on Streamr." },
    ],
  }),
  component: () => {
    const s = titles.filter((t) => t.kind === "series");
    return (
      <main className="min-h-screen pb-16">
        <TopNav />
        <Hero title="The North Line" id="north" />
        <div className="-mt-10 relative space-y-6">
          <Rail label="Binge‑worthy" items={s} wide />
          <Rail label="New Episodes" items={[...s].reverse()} />
        </div>
      </main>
    );
  },
});
