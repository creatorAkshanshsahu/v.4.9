package com.aktv.app

import java.io.IOException
import java.net.URI

internal enum class CredentialIssue { EXPIRED, NOT_YET_VALID }

internal class SourceCredentialsUnavailableException(val provider: LiveProvider, val issue: CredentialIssue) : IOException(
    "${provider.name} is supplying ${if (issue == CredentialIssue.EXPIRED) "expired" else "not-yet-valid"} stream credentials; waiting for the provider to renew them"
)

/** The provider's HTTPS Date/Age reference, never a possibly incorrect TV wall clock, can identify
 * definitely unusable DIRECT Jio credentials. Unknown formats stay with the CDN. Relay URLs are
 * exempt: their own query/redirect may generate a fresh signature on demand.
 */
internal object CredentialFreshness {
    fun issue(source: TgStream, clock: HttpReferenceClock): CredentialIssue? {
        if (!clock.hasReference()) return null
        val uri = try { URI(source.url) } catch (_: Exception) { return null }
        if (uri.scheme != "https" || uri.host?.lowercase() !in setOf("jiotvmblive.cdn.jio.com", "jiotvpllive.cdn.jio.com") ||
            uri.port !in setOf(-1, 443) || !uri.path.orEmpty().startsWith("/bpk-tv/")) return null
        val now = clock.now()
        val expires = StreamRequestPolicy.expiry(source.url, source.cookie)
        val starts = StreamRequestPolicy.notBefore(source.url, source.cookie)
        return when {
            expires != null && expires < now - 30_000 -> CredentialIssue.EXPIRED
            starts != null && starts > now + 10_000 -> CredentialIssue.NOT_YET_VALID
            else -> null
        }
    }

    fun usable(provider: LiveProvider, sources: List<TgStream>, clock: HttpReferenceClock): List<TgStream> {
        val checked = sources.map { it to issue(it, clock) }
        val usable = checked.filter { it.second == null }.map { it.first }
        if (sources.isNotEmpty() && usable.isEmpty()) {
            val reason = if (checked.any { it.second == CredentialIssue.NOT_YET_VALID }) CredentialIssue.NOT_YET_VALID else CredentialIssue.EXPIRED
            throw SourceCredentialsUnavailableException(provider, reason)
        }
        return usable
    }
}
