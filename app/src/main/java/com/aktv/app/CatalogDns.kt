package com.aktv.app

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves the TMDB API through Android's normal DNS first. If that specifically fails, retries
 * the hostname lookup over DNS-over-HTTPS using Google's public resolver. Only the hostname is sent;
 * API keys, paths, and catalogue queries are never part of a DNS request.
 */
internal class CatalogDns(
    private val systemDns: Dns = Dns.SYSTEM,
    private val fallbackDns: Dns
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.equals(TMDB_API_HOST, ignoreCase = true)) return systemDns.lookup(hostname)
        return try {
            systemDns.lookup(hostname)
        } catch (systemError: UnknownHostException) {
            try {
                fallbackDns.lookup(hostname).ifEmpty { throw UnknownHostException(hostname) }
            } catch (fallbackError: Exception) {
                systemError.addSuppressed(fallbackError)
                throw systemError
            }
        }
    }

    companion object {
        const val TMDB_API_HOST = "api.themoviedb.org"
    }
}

/** DNS-over-HTTPS resolver with a fixed IP bootstrap, so it can work when Android DNS cannot. */
internal class GoogleDohDns(
    private val client: OkHttpClient = bootstrapClient(),
    private val endpoint: HttpUrl = "https://dns.google/resolve".toHttpUrl(),
    private val nowMs: () -> Long = System::currentTimeMillis
) : Dns {
    private data class Entry(val expiresAt: Long, val addresses: List<InetAddress>)
    private val cache = ConcurrentHashMap<String, Entry>()

    @Synchronized override fun lookup(hostname: String): List<InetAddress> {
        require(hostname.equals(CatalogDns.TMDB_API_HOST, ignoreCase = true)) { "Unsupported fallback DNS host" }
        val host = hostname.lowercase(Locale.US)
        cache[host]?.takeIf { it.expiresAt > nowMs() }?.let { return it.addresses }

        val url = endpoint.newBuilder().addQueryParameter("name", host).addQueryParameter("type", "A").build()
        val request = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        val result = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UnknownHostException("DoH HTTP ${response.code}")
                val body = response.body?.string() ?: throw UnknownHostException("Empty DoH response")
                val root = JSONObject(body)
                if (root.optInt("Status", -1) != 0) throw UnknownHostException("DoH lookup failed")
                val answers = root.optJSONArray("Answer") ?: throw UnknownHostException("No DNS answer")
                val parsed = ArrayList<InetAddress>()
                var ttl = 300L
                for (i in 0 until answers.length()) {
                    val answer = answers.optJSONObject(i) ?: continue
                    if (answer.optInt("type") != 1) continue
                    val value = answer.optString("data")
                    if (!value.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) continue
                    try { parsed.add(InetAddress.getByName(value)) } catch (_: Exception) { }
                    ttl = minOf(ttl, answer.optLong("TTL", 300L).coerceIn(30L, 300L))
                }
                parsed.distinct() to ttl
            }
        } catch (error: UnknownHostException) {
            throw error
        } catch (error: Exception) {
            throw UnknownHostException("DoH lookup failed").apply { initCause(error) }
        }
        if (result.first.isEmpty()) throw UnknownHostException(host)
        cache[host] = Entry(nowMs() + result.second * 1_000L, result.first)
        return result.first
    }

    companion object {
        private fun bootstrapClient(): OkHttpClient {
            val bootstrap = listOf(
                InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                InetAddress.getByAddress(byteArrayOf(8, 8, 4, 4))
            )
            val dns = Dns { hostname ->
                if (hostname.equals("dns.google", ignoreCase = true)) bootstrap
                else Dns.SYSTEM.lookup(hostname)
            }
            return OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
