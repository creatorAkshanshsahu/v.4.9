package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class TgDiscoveryTest {
    private val site = "https://livetgtv.lovable.app"

    @Test fun `page script and preload tags support both quote styles and query strings`() {
        val html = """<script type="module" src="/assets/main-new.js?v=2"></script>
          <link rel='modulepreload' href='/assets/TgPlayer-new.js'>
          <script src='https://unrelated.example/track.js'></script>"""
        assertEquals(listOf("$site/assets/TgPlayer-new.js", "$site/assets/main-new.js?v=2"), TgDiscovery.scripts(html, "$site/watch/872", site))
    }

    @Test fun `lazy imports and Vite dependency maps resolve against the right base`() {
        val js = """const load=()=>import('./servers-NEW.mjs?x=1'); const deps=["assets/watch-lazy.js","assets/player.js"]; import x from "../helper.js";"""
        val scripts = TgDiscovery.scripts(js, "$site/assets/main.js", site)
        assertTrue(scripts.contains("$site/assets/servers-NEW.mjs?x=1"))
        assertTrue(scripts.contains("$site/assets/watch-lazy.js"))
        assertTrue(scripts.contains("$site/assets/player.js"))
        assertTrue(scripts.contains("$site/helper.js"))
        assertFalse(scripts.any { it.contains("assets/assets") })
    }

    @Test fun `function IDs are discovered without pinning a deployment hash`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val js = """const x=rpc("$a"); const meta={id:'$b'}; const url="/_serverFn/player_config_v3";"""
        assertEquals(setOf(a, b, "player_config_v3"), TgDiscovery.functions(js).toSet())
    }

    @Test fun `quoted function metadata keys from newer bundles are supported`() {
        val id = "d".repeat(64)
        assertEquals(listOf(id), TgDiscovery.functions("""createClientRpc({"id":"$id"})"""))
    }

    @Test fun `bare hashes and ClearKey values are not probed as server functions`() {
        val js = """const checksum="${"f".repeat(64)}"; const drm={keyId:"${"a".repeat(32)}",key:"${"b".repeat(32)}"};"""
        assertTrue(TgDiscovery.functions(js).isEmpty())
    }

    @Test fun `external and credentialed script locations are excluded`() {
        val js = """import "https://livetgtv.lovable.app.evil.test/servers.js"; import "https://user:pass@livetgtv.lovable.app/servers.js";"""
        assertTrue(TgDiscovery.scripts(js, site, site).isEmpty())
    }

    @Test fun `Rolldown static backtick function ids and lazy imports are supported`() {
        val id = "c".repeat(64)
        // Shape observed in TG's deployed servers/watch chunks; no deployment hash is pinned.
        val js = """var r=n({method:`GET`}).handler(t(`$id`)); import(`./servers-latest.js`);"""
        assertEquals(listOf(id), TgDiscovery.functions(js))
        assertEquals(listOf("$site/assets/servers-latest.js"), TgDiscovery.scripts(js, "$site/assets/watch.js", site))
        assertEquals(listOf(id), TgDiscovery.functions("""rpc({id:`$id`})"""))
    }

    @Test fun `interpolated templates and mismatched quotes are not literal endpoints`() {
        val id = "c".repeat(64)
        val js = """import(`./servers-${'$'}{deployment}.js`); rpc(`$id'); rpc(`${'$'}{id}`);"""
        assertTrue(TgDiscovery.scripts(js, "$site/assets/main.js", site).isEmpty())
        assertTrue(TgDiscovery.functions(js).isEmpty())
    }

}
