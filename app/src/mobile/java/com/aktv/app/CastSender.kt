package com.aktv.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The phone side of "Play on TV": finds [CastProtocol]-advertised TVs on the LAN and sends one
 * command line each. LAN only, no credentials on the wire — the TV resolves from its own accounts.
 */
internal object CastSender {
    data class Tv(val name: String, val host: String, val port: Int)

    fun send(tv: Tv, line: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(tv.host, tv.port), 4_000)
            socket.soTimeout = 4_000
            val out = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)
            out.println(line)
            out.flush()
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine() == "ok"
        }
    }.getOrDefault(false)

    class Discovery(context: Context, private val onFound: (List<Tv>) -> Unit) {
        private val manager = context.applicationContext
            .getSystemService(Context.NSD_SERVICE) as? NsdManager
        private val found = LinkedHashMap<String, Tv>()
        @Volatile private var browsing = false
        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceName.startsWith(CastProtocol.SERVICE_PREFIX)) return
                runCatching {
                    manager?.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            synchronized(found) {
                                found[info.serviceName] = Tv(info.serviceName.removePrefix("${CastProtocol.SERVICE_PREFIX}-").ifBlank { "TV" }, host, info.port)
                                onFound(found.values.toList())
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(service.serviceName) != null) onFound(found.values.toList())
                }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { browsing = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        fun start() {
            if (browsing || manager == null) return
            runCatching {
                manager.discoverServices(CastProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                browsing = true
            }
        }

        fun refresh() {
            stop()
            synchronized(found) { found.clear() }
            start()
        }

        fun stop() {
            if (!browsing) return
            browsing = false
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
    }
}
