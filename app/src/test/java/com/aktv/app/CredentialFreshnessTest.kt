package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class CredentialFreshnessTest {
    private fun source(token: String) = TgStream("JJTV", "https://jiotvmblive.cdn.jio.com/bpk-tv/SonyYAYHin_MOB/WDVLive/index.mpd",
        "dash", cookie = "__hdnea__=$token", requestOrigin = JjtvResolver.SITE)
    private fun trusted(now: Long = 100_000) = HttpReferenceClock(wall = { 1 }, elapsed = { 0 }).apply { observe(now) }

    @Test fun `wrong TV wall clock alone cannot reject a supplied credential`() {
        val clock = HttpReferenceClock(wall = { 4_102_444_800_000 }, elapsed = { 0 })
        assertFalse(clock.hasReference())
        assertNull(CredentialFreshness.issue(source("st=1~exp=90~hmac=synthetic"), clock))
    }
    @Test fun `trusted metadata time identifies a definitely expired direct credential`() {
        val issue = CredentialFreshness.issue(source("st=1~exp=50~hmac=synthetic"), trusted())
        assertEquals(CredentialIssue.EXPIRED, issue)
    }
    @Test fun `boundary expiry has a grace period while future-start tokens are distinct`() {
        assertNull(CredentialFreshness.issue(source("exp=90~hmac=synthetic"), trusted()))
        assertEquals(CredentialIssue.NOT_YET_VALID, CredentialFreshness.issue(source("st=200~exp=500~hmac=synthetic"), trusted()))
    }
    @Test fun `unknown expiry remains unknown rather than automatically invalid`() {
        assertNull(CredentialFreshness.issue(source("opaque-synthetic-value"), trusted()))
    }
    @Test fun `a relay owns its query and can mint a new redirect despite an old TG cookie field`() {
        val relay = source("exp=1~hmac=old").copy(url = "https://allinonereborn2.online/jtv.mpd?id=872", requestOrigin = TgResolver.SITE)
        assertNull(CredentialFreshness.issue(relay, trusted()))
        assertEquals(listOf(relay), CredentialFreshness.usable(LiveProvider.TG, listOf(relay), trusted()))
    }
    @Test fun `only unusable sources are filtered and no source keys or credentials are substituted`() {
        val old = source("exp=1~hmac=old")
        val valid = source("exp=500~hmac=current")
        val result = CredentialFreshness.usable(LiveProvider.JJTV, listOf(old, valid), trusted())
        assertEquals(1, result.size); assertSame(valid, result.single())
    }
    @Test fun `all expired credentials produce a typed availability error without a fake HTTP status`() {
        val error = assertThrows(SourceCredentialsUnavailableException::class.java) {
            CredentialFreshness.usable(LiveProvider.JJTV, listOf(source("exp=1~hmac=private-value")), trusted())
        }
        assertEquals(CredentialIssue.EXPIRED, error.issue)
        assertFalse(error.message.orEmpty().contains("private-value"))
        assertFalse(error.message.orEmpty().contains("403"))
    }
    @Test fun `server time continues advancing when the device wall clock changes`() {
        var elapsed = 0L
        var wall = 1L
        val clock = HttpReferenceClock(wall = { wall }, elapsed = { elapsed })
        clock.observe(100_000)
        val stream = source("exp=160~hmac=synthetic")
        assertNull(CredentialFreshness.issue(stream, clock))
        wall = 4_102_444_800_000; elapsed = 95_000
        assertEquals(CredentialIssue.EXPIRED, CredentialFreshness.issue(stream, clock))
    }
    @Test fun `not-before parsing preserves opaque credentials and tolerates encoded fields`() {
        assertEquals(120_000L, StreamRequestPolicy.notBefore("", "__hdnea__=st%3D120%7Eexp%3D300%7Ehmac%3Dsynthetic"))
        assertNull(StreamRequestPolicy.notBefore("", "__hdnea__=opaque"))
    }
}
