package com.aktv.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The TV side of "Play on TV": advertises `STREAMR-TV` on the LAN and takes one-line
 * [CastProtocol] commands on a plain socket. Best effort throughout — a dead network service must
 * never touch playback. Start in `onResume`, stop in `onPause`.
 */
internal class CastReceiver(
    private val context: Context,
    private val onCommand: (CastProtocol.Command, String) -> Unit
) {
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "aktv-cast").apply { isDaemon = true } }
    private var nsd: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var server: ServerSocket? = null
    private var accept: Future<*>? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        pool.submit {
            try {
                val socket = ServerSocket(CastProtocol.PORT)
                server = socket
                socket.soTimeout = 2_000
                val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                nsd = manager
                if (manager != null) {
                    val info = NsdServiceInfo().apply {
                        serviceName = "${CastProtocol.SERVICE_PREFIX}-${Build.MODEL.take(24).replace(Regex("[^A-Za-z0-9-]"), "")}"
                        serviceType = CastProtocol.SERVICE_TYPE
                        port = CastProtocol.PORT
                    }
                    val registration = object : NsdManager.RegistrationListener {
                        override fun onServiceRegistered(service: NsdServiceInfo) = Unit
                        override fun onRegistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceUnregistered(service: NsdServiceInfo) = Unit
                        override fun onUnregistrationFailed(service: NsdServiceInfo, errorCode: Int) = Unit
                    }
                    listener = registration
                    runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
                }
                while (running) {
                    val client = try { socket.accept() } catch (_: Exception) { continue }
                    try {
                        client.soTimeout = 5_000
                        val line = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8)).readLine()
                        val command = if (line != null) CastProtocol.decode(line) else null
                        try { client.getOutputStream().write(if (command != null) "ok\n".toByteArray() else "no\n".toByteArray()) } catch (_: Exception) {}
                        if (command != null) {
                            val from = try { client.inetAddress?.hostAddress.orEmpty() } catch (_: Exception) { "" }
                            onCommand(command, from)
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        accept?.cancel(true); accept = null
        runCatching { listener?.let { nsd?.unregisterService(it) } }
        listener = null; nsd = null
        runCatching { server?.close() }
        server = null
    }
}
