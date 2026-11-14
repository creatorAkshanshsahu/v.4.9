# Website Live TV Playback Fix (`creatorAkshanshsahu/screen-safari-studio`)

Production-verified fix for **`https://screen-safari-studio.lovable.app`** (`creatorAkshanshsahu/screen-safari-studio`, built cleanly against `main` @ `63a50a2` with **18/18 Vitest suites / 83 tests passing**, **`tsc --noEmit` = 0 errors**, and a clean **`vite build` (`cloudflare-module`)**).

> `main` already contains the Browser-Direct CDN release (PR #12). This update fixes the follow-up production error below on top of it.

---

## The Error This Fixes

**`Direct CDN stream is taking too long to deliver video frames. Press Retry to switch token/server.`**

Root cause chain (all in the direct-play path shipped by PR #12):

1. `LiveVideoSurface.tsx` treated `player.load(manifest)` resolving as success and returned immediately — but a manifest can load while its segments stall forever (slow/rotated `__hdnea__` token, congested edge). Shaka's own retry budget (`12s × 2 attempts` ≈ 24s+) exceeds the player's 18s watchdog, so no Shaka error ever fired before the watchdog showed the manual-Retry message.
2. Failover only happened when `player.load()` *threw*. A loaded-but-frozen stream never tried Server 2 / the fresh GioTV token by itself.
3. `/api/live/direct-config` waited for the slowest metadata leg: when TG `_serverFn` discovery hung, GioTV candidates (ready in ~200ms) were delayed behind it, and expired embedded tokens were handed to the player, wasting one full attempt each.

---

## What Changed

1. **Frame-verified direct playback (`src/components/tv/LiveVideoSurface.tsx`)**
   - Every DASH/HLS direct candidate must render a real video frame within 6–7s (after an 8s load race); otherwise the player destroys it and automatically tries the next token/server, showing `Trying TG Live · Server 2…` etc.
   - Shaka failures are captured *before/during* `load()` (not after): a fast `HTTP 403/450` refusal still gets one bare-URL retry, while a *stall* skips it and moves to the next server immediately.
   - One automatic fresh-token pass (`&refresh=1`) runs inside the same attempt when all cached-token candidates fail — no manual Retry needed for expired tokens.
   - The 18s watchdog now spends one automatic backup token/server switch (`Switching to backup token/server…`) before ever showing the manual-Retry error, and `onWaiting` can no longer overwrite a concrete error with `Buffering…`.
   - `/embed/:id` standalone player (`renderStandaloneEmbedHtml`) gets the same frame-gated failover (7s fetch / 8s load / 6–7s frame budgets, 40s pass cap, 25s watchdog).

2. **Faster, fresher `/api/live/direct-config` (`src/lib/live-gateway.ts`)**
   - TG RPC leg is budget-raced at 5s: GioTV candidates are served on time even when `_serverFn` hangs (the background TG call still warms the verified fn-id cache for the retry), and normal plays reuse the 10-minute `_serverFn` discovery cache.
   - Provably-expired `__hdnea__` tokens are pruned when the same URL also has a fresh variant, and candidates are ordered freshest-first (stable, so TG Server 1 still precedes Server 2).

3. **Test toolchain repair (`package.json`)**
   - Restores `@testing-library/dom@^10.4.2`, accidentally removed by `683ea35` (`Work in progress`), which broke all 18 Vitest suites (`screen`/`fireEvent`/`waitFor` unresolved).

4. **New regression test (`src/test/playback-fixes.test.ts`)**
   - `prunes expired embedded tokens and orders freshest candidates first`.

---

## Apply & Deploy to `screen-safari-studio`

### Option A — One-Command Git Sync
```bash
./lovable-screen-safari-studio/sync-to-lovable.sh
cd screen-safari-studio
git add -A
git commit -m "fix(live): auto-switch direct CDN token/server on slow frames + prune expired tokens"
git push origin main
```

### Option B — Apply Git Patch Directly inside `screen-safari-studio`
```bash
git am < lovable-screen-safari-studio/fix-website-live-channels.patch
git push origin main
```

### Option C — Open `creatorAkshanshsahu/screen-safari-studio` in Arena Agent Mode
Paste:
> Copy `lovable-screen-safari-studio/files/src/` + `lovable-screen-safari-studio/files/package.json` from `creatorAkshanshsahu/v.4.9` (`arena/a7aea110-v-4-9`) into this repo, run `vitest run` and `vite build`, and open/merge the PR to `main`.
