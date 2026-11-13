/**
 * STREAMR Live TV Drop-In Widget SDK for External Websites (e.g. OG Website).
 *
 * Usage on any external page:
 *   <div id="og-live-tv"></div>
 *   <script src="https://YOUR-GATEWAY-HOST/embed-sdk.js"
 *           data-target="#og-live-tv"
 *           data-source="maxtv"
 *           data-filter="hindi"></script>
 */
(function () {
  const scriptEl =
    document.currentScript ||
    document.querySelector('script[src*="embed-sdk.js"]');
  if (!scriptEl) return;

  const gatewayOrigin = new URL(scriptEl.src, location.href).origin;
  const targetSelector = scriptEl.getAttribute("data-target") || "#og-live-tv";
  const source = scriptEl.getAttribute("data-source") || "maxtv";
  const filter = scriptEl.getAttribute("data-filter") || "hindi";
  const mount = document.querySelector(targetSelector);
  if (!mount) return;

  mount.innerHTML = `
    <div style="background:#080711;border:1px solid rgba(255,255,255,0.1);border-radius:16px;overflow:hidden;color:#f5f7fb;font-family:system-ui,-apple-system,sans-serif;">
      <div style="position:relative;width:100%;aspect-ratio:16/9;background:#000;">
        <iframe id="streamr-sdk-frame" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen
          style="width:100%;height:100%;border:0;display:block;"></iframe>
      </div>
      <div style="padding:14px 16px;border-top:1px solid rgba(255,255,255,0.08);">
        <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;justify-content:space-between;margin-bottom:12px;">
          <strong style="font-size:14px;letter-spacing:0.04em;">LIVE TV CHANNELS</strong>
          <input id="streamr-sdk-search" type="search" placeholder="Search channel (Star Sports, Sony, Aaj Tak…)"
            style="flex:1;min-width:200px;max-width:340px;background:#121022;border:1px solid rgba(255,255,255,0.14);color:#fff;padding:7px 12px;border-radius:999px;font-size:12px;outline:none;" />
        </div>
        <div id="streamr-sdk-grid" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;max-height:280px;overflow-y:auto;"></div>
      </div>
    </div>
  `;

  const iframe = mount.querySelector("#streamr-sdk-frame");
  const search = mount.querySelector("#streamr-sdk-search");
  const grid = mount.querySelector("#streamr-sdk-grid");
  let allChannels = [];
  let activeId = "";

  function selectChannel(ch) {
    activeId = ch.id;
    iframe.src = gatewayOrigin + "/embed/" + encodeURIComponent(ch.id);
    renderList(search.value);
  }

  function renderList(q) {
    const query = (q || "").trim().toLowerCase();
    const list = query
      ? allChannels.filter((c) => (c.name + " " + c.category).toLowerCase().includes(query))
      : allChannels;
    grid.innerHTML = "";
    list.slice(0, 120).forEach((ch) => {
      const btn = document.createElement("button");
      const active = ch.id === activeId;
      btn.type = "button";
      btn.style.cssText =
        "text-align:left;padding:9px 11px;border-radius:10px;cursor:pointer;border:1px solid " +
        (active ? "#00ecf7" : "rgba(255,255,255,0.09)") +
        ";background:" +
        (active ? "rgba(0,236,247,0.12)" : "#121022") +
        ";color:#fff;font-size:12px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;";
      btn.textContent = ch.name;
      btn.title = ch.name + " (" + ch.category + ")";
      btn.onclick = () => selectChannel(ch);
      grid.appendChild(btn);
    });
  }

  fetch(
    gatewayOrigin +
      "/api/channels?source=" +
      encodeURIComponent(source) +
      "&filter=" +
      encodeURIComponent(filter)
  )
    .then((r) => r.json())
    .then((data) => {
      allChannels = data.channels || [];
      if (allChannels.length > 0) selectChannel(allChannels[0]);
    })
    .catch(() => {
      grid.innerHTML = '<div style="color:#9da3b8;font-size:12px;">Unable to load Live TV channel list.</div>';
    });

  search.addEventListener("input", () => renderList(search.value));
})();
