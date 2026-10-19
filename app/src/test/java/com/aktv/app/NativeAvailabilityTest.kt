package com.aktv.app

import org.junit.Assert.*
import org.junit.Test

class NativeAvailabilityTest {
    private fun source(token: String = "old", channel: String = "SonyYAYHin_MOB") = TgStream("JJTV browser",
        "https://jiotvmblive.cdn.jio.com/bpk-tv/$channel/WDVLive/index.mpd?__hdnea__=catalogue",
        "dash", cookie = "__hdnea__=$token", requestOrigin = JjtvResolver.SITE, omitReferrer = true)
    private fun problem(code: Int = 403, retry: Long? = null, challenge: Boolean = false) = PlaybackDiagnostics.Problem(
        PlaybackDiagnostics.Family.HTTP, http = code,
        reply = HttpReply.of(code, "text/html", "<html>not authorized</html>".toByteArray(), challenge).copy(retryAfterMs = retry))

    @Test fun `immediate identical refusals are skipped but a newly supplied credential is eligible`() {
        val policy = NativeAvailability(); val source = source()
        policy.failed(source, LiveProvider.JJTV, problem(), 1000)
        assertTrue(policy.requestRefused(source, 2000))
        assertFalse(policy.requestRefused(source("renewed"), 2000))
        assertFalse(policy.requestRefused(source, 61_000))
    }
    @Test fun `changed provided headers are a different request while renamed labels are not`() {
        val policy = NativeAvailability(); val source = source()
        policy.failed(source, LiveProvider.JJTV, problem(), 0)
        assertTrue(policy.requestRefused(source.copy(label = "renamed"), 10))
        assertFalse(policy.requestRefused(source.copy(headers = mapOf("User-Agent" to "declared-native")), 10))
    }
    @Test fun `a fresh configured cookie masks an old catalogue query in the effective fingerprint`() {
        val source = source("fresh")
        assertEquals(NativeAvailability.requestKey(source), NativeAvailability.requestKey(source.copy(
            url = source.url.replace("=catalogue", "=another-catalogue"))))
    }
    @Test fun `no refusals can leak across channels or a new manual session`() {
        val policy = NativeAvailability()
        policy.failed(source(), LiveProvider.JJTV, problem(), 0)
        assertFalse(policy.requestRefused(source(channel = "ZeeCinemaHD"), 10))
        policy.reset(); assertFalse(policy.requestRefused(source(), 10))
        assertNull(policy.nextCheckDelay(10))
    }
    @Test fun `an unchanged relay URL can generate a fresh signed redirect and is not blocked as a static credential`() {
        val relay = source().copy(url = "https://allinonereborn2.online/jiotv-m3u/jtv.mpd?id=872", requestOrigin = TgResolver.SITE)
        val policy = NativeAvailability()
        policy.failed(relay, LiveProvider.TG, problem(), 0)
        assertNull(NativeAvailability.requestKey(relay))
        assertFalse(policy.requestRefused(relay, 1))
    }
    @Test fun `automatic checks have increasing waits and stop after three`() {
        val policy = NativeAvailability()
        policy.failed(source(), LiveProvider.JJTV, problem(), 0)
        assertEquals(30_000L, policy.nextCheckDelay(1))
        assertEquals(60_000L, policy.nextCheckDelay(40_000))
        assertEquals(120_000L, policy.nextCheckDelay(110_000))
        assertNull(policy.nextCheckDelay(250_000))
    }
    @Test fun `metadata refusal does not immediately re-fetch the same denied provider`() {
        val policy = NativeAvailability()
        policy.failed(null, LiveProvider.JJTV, problem(), 1000, metadata = true)
        assertNotNull(policy.lookupRefusal(LiveProvider.JJTV, 2000))
        assertNull(policy.lookupRefusal(LiveProvider.TG, 2000))
        assertNull(policy.lookupRefusal(LiveProvider.JJTV, 61_000))
    }
    @Test fun `rate limit applies to relay requests too and a long Retry-After is not shortened`() {
        val policy = NativeAvailability()
        val relay = source().copy(url = "https://allinonereborn2.online/jtv.mpd?id=872", requestOrigin = TgResolver.SITE)
        policy.failed(relay, LiveProvider.TG, problem(429, 3_600_000), 0)
        assertNotNull(policy.lookupRefusal(LiveProvider.TG, 60_000))
        assertTrue(policy.requestRefused(relay, 60_000))
        assertNull(policy.nextCheckDelay(60_000))
    }
    @Test fun `ordinary Retry-After delays the next check without looping`() {
        val policy = NativeAvailability()
        policy.failed(source(), LiveProvider.JJTV, problem(429, 90_000), 1000)
        assertEquals(89_000L, policy.nextCheckDelay(2000))
    }
    @Test fun `server challenges are not retried with another header style`() {
        val policy = NativeAvailability()
        policy.failed(source(), LiveProvider.JJTV, problem(challenge = true), 0)
        assertNull(policy.nextCheckDelay(1000))
        assertNotNull(policy.lookupRefusal(LiveProvider.JJTV, 600_000))
        assertTrue(policy.requestRefused(source("new").copy(headers = mapOf("User-Agent" to "other")), 600_000))
    }
    @Test fun `an unrelated provider can still recover without retrying the challenged one`() {
        val policy = NativeAvailability()
        policy.failed(source(), LiveProvider.JJTV, problem(challenge = true), 0)
        policy.failed(source().copy(requestOrigin = TgResolver.SITE), LiveProvider.TG, problem(), 100)
        assertEquals(30_000L, policy.nextCheckDelay(1000))
        assertNotNull(policy.lookupRefusal(LiveProvider.JJTV, 1000))
    }
    @Test fun `a decoder DRM format or geographical failure alone is not an automatic credential retry`() {
        for (failure in listOf(PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.DECODER),
            PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.DRM), PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.FORMAT), problem(451))) {
            val policy = NativeAvailability()
            policy.failed(source(), LiveProvider.JJTV, failure, 0)
            assertNull(policy.nextCheckDelay(1000))
        }
    }
    @Test fun `expired JJTV data still gets checked when a separate TG source has unsupported DRM`() {
        val policy = NativeAvailability()
        policy.failed(null, LiveProvider.JJTV, PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.CREDENTIALS,
            credentialIssue = CredentialIssue.EXPIRED), 0, metadata = true)
        policy.failed(null, LiveProvider.TG, PlaybackDiagnostics.Problem(PlaybackDiagnostics.Family.DRM), 1000)
        assertEquals(30_000L, policy.nextCheckDelay(1000))
        assertNull(policy.lookupRefusal(LiveProvider.JJTV, 30_000))
    }
    @Test fun `public report and request fingerprints do not disclose input credentials`() {
        val policy = NativeAvailability(); val stream = source("private-synthetic-token")
        policy.failed(stream, LiveProvider.JJTV, problem(), 0)
        assertFalse(policy.report().contains("private-synthetic-token"))
        assertFalse(policy.report().contains("https://"))
        assertTrue(NativeAvailability.requestKey(stream)!!.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun `delayed checks cannot resume a different channel session or background activity`() {
        val gate = NativeRecheck("v1:872", 4, 30_000)
        assertEquals(20_000L, gate.remaining(10_000, "v1:872", 4, true, false))
        assertEquals(0L, gate.remaining(31_000, "v1:872", 4, true, false))
        assertNull(gate.remaining(31_000, "v1:165", 4, true, false))
        assertNull(gate.remaining(31_000, "v1:872", 5, true, false))
        assertNull(gate.remaining(31_000, "v1:872", 4, false, false))
        assertNull(gate.remaining(31_000, "v1:872", 4, true, true))
        assertNull(gate.remaining(31_000, null, 4, true, false))
    }

}
