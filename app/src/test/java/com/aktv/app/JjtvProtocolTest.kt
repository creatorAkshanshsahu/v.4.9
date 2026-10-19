package com.aktv.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JjtvProtocolTest {
    private val site = JjtvResolver.SITE
    private val page = "$site/pind?id=872"
    private val config = JjtvProtocol.Config("$site/channels.json", "https://tokens.example/general.json", null,
        "https://tokens.example/star.json", mapOf("Referer" to "https://www.jiotv.com/", "User-Agent" to "plaYtv/7.1.5"))
    private val keyData = """"keys":[{"keyId":"${"1".repeat(32)}","key":"${"2".repeat(32)}"},{"keyId":"${"3".repeat(32)}","key":"${"4".repeat(32)}"}]"""
    private fun streams(json: String, regular: String = "__hdnea__=regular", fallback: String = "", star: String = "__hdnea__=star") =
        JjtvProtocol.streams(JSONObject(json), config, regular, fallback, star, page, site)

    @Test fun `deployed constants and request headers are discovered without pinning endpoint filenames`() {
        val js = """const CHANNELS_URL = `/channels-new.json`; const COOKIE_PRIMARY = 'https://tokens.example/current.json';
            const COOKIE_FALLBACK = ""; const COOKIE_STAR_SPORTS = "/star-new.json";
            request.headers["Referer"] = "https://www.jiotv.com/"; request.headers['User-Agent'] = `plaYtv/new`;"""
        val result = requireNotNull(JjtvProtocol.config(js, page))
        assertEquals("$site/channels-new.json", result.channels)
        assertEquals("https://tokens.example/current.json", result.primaryCookie)
        assertNull(result.fallbackCookie)
        assertEquals("$site/star-new.json", result.starCookies)
        assertEquals("plaYtv/new", result.mediaHeaders["User-Agent"])
        assertEquals("https://www.jiotv.com/", result.mediaHeaders["Referer"])
    }

    @Test fun `dynamic and credentialed configuration URLs are not executed or fetched`() {
        assertNull(JjtvProtocol.config("""const CHANNELS_URL = `/data-${'$'}{build}.json`;""", page))
        assertNull(JjtvProtocol.config("""const CHANNELS_URL = 'https://user:pass@data.example/channels.json';""", page))
        assertNull(JjtvProtocol.config("""const CHANNELS_URL = 'file:///private/data';""", page))
        assertNull(JjtvProtocol.config("""const CHANNELS_URL = 'http://data.example/channels.json';""", page))
    }

    @Test fun `catalog accepts arrays and the deployed comma separated format with exact ids`() {
        val body = """{"id":816,"name":"Cartoon Network Hindi"},{"id":"872","name":"Sony Yay Hindi"},{"id":"873","name":"Sony Yay Tamil"}"""
        for (text in listOf(body, "[$body]")) {
            val catalog = JjtvProtocol.catalog(text)
            assertEquals("Sony Yay Hindi", catalog["872"]?.getString("name"))
            assertEquals("Cartoon Network Hindi", catalog["816"]?.getString("name"))
            assertNull(catalog["87"])
        }
    }

    @Test fun `first exact channel wins and unrelated cards are not channels`() {
        val catalog = JjtvProtocol.catalog("""[{"id":"872","name":"first"},{"id":"872","name":"second"},{"telegram":"https://example.com/"}]""")
        assertEquals(1, catalog.size)
        assertEquals("first", catalog["872"]?.getString("name"))
    }

    @Test fun `Sony and Star multi-key format retains both keys and overrides old single-key fields`() {
        val source = streams("""{"url":"https://media.example/sony.mpd",$keyData,"keyId":"${"a".repeat(32)}","key":"${"b".repeat(32)}"}""").single()
        val license = JSONObject(String(requireNotNull(ClearKey.license(requireNotNull(source.drm)))))
        assertEquals(2, license.getJSONArray("keys").length())
        assertEquals(site, source.requestOrigin)
        assertEquals("plaYtv/7.1.5", source.headers["User-Agent"])
    }

    @Test fun `Cartoon Network 816 legacy single-key format remains supported`() {
        val source = streams("""{"url":"https://media.example/cartoon.mpd","keyId":"${"a".repeat(32)}","key":"${"b".repeat(32)}"}""").single()
        assertEquals(1, ClearKey.pairs(requireNotNull(source.drm)).size)
    }

    @Test fun `each PL source uses only its own channel-specific cookie and URL order is stable`() {
        val sources = streams("""{"url":"https://media.example/bpk-tv//first.mpd",
            "url1":"https://jiotvpllive.cdn.jio.com/bpk-tv/star/index.mpd",
            "url2":"https://media.example/third.mpd",$keyData}""", fallback = "__hdnea__=second")
        assertEquals(listOf("__hdnea__=regular", "__hdnea__=second", "__hdnea__=star", "__hdnea__=regular", "__hdnea__=second"), sources.map { it.cookie })
        assertEquals("https://media.example/bpk-tv/first.mpd", sources.first().url)
        assertTrue(sources[2].url.contains("jiotvpllive.cdn.jio.com"))
        assertEquals(listOf(1, 2, 3, 4, 5).map { "JJTV · Server $it" }, sources.map { it.label })
    }

    @Test fun `missing Star cookie never falls back to the regular cookie or a stale signed URL`() {
        val sources = streams("""{"url":"https://jiotvpllive.cdn.jio.com/live.mpd?__hdnea__=old",$keyData}""", star = "")
        assertTrue(sources.isEmpty())
        val spaced = streams("""{"url":"  https://jiotvpllive.cdn.jio.com/live.mpd  ",$keyData}""", star = "__hdnea__=only-star")
        assertEquals("__hdnea__=only-star", spaced.single().cookie)
        assertEquals("https://jiotvpllive.cdn.jio.com/live.mpd", spaced.single().url)
    }

    @Test fun `regular sources can use a fallback cookie or no cookie but do not duplicate rounds`() {
        val json = """{"url":"https://media.example/live.mpd"}"""
        assertEquals("__hdnea__=second", streams(json, regular = "", fallback = "__hdnea__=second").single().cookie)
        assertEquals("", streams(json, regular = "").single().cookie)
        assertEquals(1, streams(json, regular = "__hdnea__=same", fallback = "__hdnea__=same").size)
    }

    @Test fun `the deployed primary cookie array skips its timestamp record`() {
        assertEquals("__hdnea__=current", JjtvProtocol.regularCookie("""[{"last_updated":"today"},{"cookie":"__hdnea__=current"}]"""))
        assertEquals("__hdnea__=second", JjtvProtocol.regularCookie("""{"cookie":"__hdnea__=second"}""", fallback = true))
        assertEquals("", JjtvProtocol.regularCookie("not-json"))
    }

    @Test fun `Star cookie uses matching failed probe redirects and preserves signed bytes`() {
        val raw = "st%3D1~exp%3D4102444800~acl%3D%2Fbpk-tv%2Fstar%2F*~hmac%3Dfixture"
        val body = """{"successful_results":[{"channel_id":"1108","final_url":"https://cdn.example/a?__hdnea__=wrong-language"}],
            "failed_results":[{"channel_id":"1106","error_details":{"http_code":450,"final_url":"https://cdn.example/star.mpd?keep=1&__hdnea__=$raw&other=keep"}}]}"""
        assertEquals("__hdnea__=$raw", JjtvProtocol.starCookie(body, "1106"))
        assertEquals("", JjtvProtocol.starCookie(body, "872"))
    }

    @Test fun `Star cookie also accepts successful and raw-token shapes advertised by the page`() {
        assertEquals("__hdnea__=success", JjtvProtocol.starCookie("""{"successful_results":[{"channel_id":1106,"final_url":"https://cdn.example/a?__hdnea__=success"}]}""", "1106"))
        assertEquals("__hdnea__=raw", JjtvProtocol.starCookie("""{"failed_results":[{"channel_id":"1106","final_url":"__hdnea__=raw"}]}""", "1106"))
    }

    @Test fun `a fresh matching Star probe is preferred to an expired first record`() {
        val body = """{"successful_results":[
            {"channel_id":"1108","final_url":"https://cdn.example/a?__hdnea__=exp=9999~hmac=wrong-channel"},
            {"channel_id":"1106","final_url":"https://cdn.example/a?__hdnea__=exp=50~hmac=old"}],
            "failed_results":[{"channel_id":"1106","error_details":{"final_url":"https://cdn.example/a?__hdnea__=exp=500~hmac=current"}}]}"""
        assertEquals("__hdnea__=exp=500~hmac=current", JjtvProtocol.starCookie(body, "1106", now = 100_000))
    }

    @Test fun `all-expired Star data stays explicit and never turns into another channel's token`() {
        val body = """{"failed_results":[
            {"channel_id":"1106","final_url":"__hdnea__=exp=50~hmac=old"},
            {"channel_id":"1108","final_url":"__hdnea__=exp=9999~hmac=wrong-channel"}]}"""
        assertEquals("__hdnea__=exp=50~hmac=old", JjtvProtocol.starCookie(body, "1106", now = 100_000))
    }

    @Test fun `regular cookies prefer a longer lived advertised token over an older first entry`() {
        val body = """[{"cookie":"__hdnea__=exp=120~hmac=short"},{"cookie":"__hdnea__=exp=900~hmac=long"}]"""
        assertEquals("__hdnea__=exp=900~hmac=long", JjtvProtocol.regularCookie(body, now = 100_000))
    }

    @Test fun `Star prefers the longest lived current credential but never borrows another variants token`() {
        val body = """{"failed_results":[
            {"channel_id":"1108","final_url":"__hdnea__=exp=9999~hmac=other"},
            {"channel_id":"1106","final_url":"__hdnea__=exp=120~hmac=short"},
            {"channel_id":"1106","final_url":"__hdnea__=exp=900~hmac=long"}]}"""
        assertEquals("__hdnea__=exp=900~hmac=long", JjtvProtocol.starCookie(body, "1106", 100_000))
    }

    @Test fun `an opaque supplied credential is retained when dated records are already expired`() {
        val body = """[{"cookie":"__hdnea__=exp=10~hmac=old"},{"cookie":"__hdnea__=opaque"}]"""
        assertEquals("__hdnea__=opaque", JjtvProtocol.regularCookie(body, now = 100_000))
    }

    @Test fun `a longer expiry with a future start does not displace a currently usable supplied token`() {
        val body = """[{"cookie":"__hdnea__=st=80~exp=200~hmac=future"},{"cookie":"__hdnea__=st=1~exp=100~hmac=current"}]"""
        assertTrue(JjtvProtocol.regularCookie(body, now = 50_000).contains("hmac=current"))
    }

}
