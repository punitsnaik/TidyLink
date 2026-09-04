package dev.punit.tidylink.shared.sync

import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LAN peer discovery over mDNS (JmDNS). Both functions degrade to a silent
 * no-op where multicast is unavailable (sandboxes, containers, some VPNs) -
 * per the PRD, a peer not being discoverable is the NORMAL case, never an
 * error. Discovery failing just means syncing waits for a manual address or
 * the next launch.
 */
object Discovery {

    const val SERVICE_TYPE = "_tidylink._tcp.local."

    private val NOOP = AutoCloseable { }

    /** Advertise this device's sync server. Close the result to withdraw. */
    fun advertise(deviceId: String, port: Int): AutoCloseable = try {
        val jmdns = create()
        jmdns.registerService(ServiceInfo.create(SERVICE_TYPE, deviceId, port, ""))
        AutoCloseable {
            runCatching { jmdns.unregisterAllServices() }
            runCatching { jmdns.close() }
        }
    } catch (_: Exception) {
        NOOP
    }

    /**
     * Watch for TidyLink peers; [onPeerSeen] is dispatched into [scope] with
     * (deviceId, host, port) each time a service resolves. Close to stop.
     */
    fun watch(
        scope: CoroutineScope,
        onPeerSeen: (deviceId: String, host: String, port: Int) -> Unit,
    ): AutoCloseable = try {
        val jmdns = create()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val name = info.name
                val port = info.port
                scope.launch { onPeerSeen(name, host, port) }
            }
        }
        jmdns.addServiceListener(SERVICE_TYPE, listener)
        AutoCloseable {
            runCatching { jmdns.removeServiceListener(SERVICE_TYPE, listener) }
            runCatching { jmdns.close() }
        }
    } catch (_: Exception) {
        NOOP
    }

    private fun create(): JmDNS =
        bestLocalAddress()?.let { JmDNS.create(it) } ?: JmDNS.create()

    /**
     * The best LAN IPv4 for this machine: up, non-loopback, preferring
     * site-local (192.168.x / 10.x / 172.16-31.x) - what goes in the QR and
     * what JmDNS binds to. Null when there is no usable interface.
     */
    internal fun bestLocalAddress(): Inet4Address? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .toList()
        candidates.firstOrNull { it.isSiteLocalAddress } ?: candidates.firstOrNull()
    }.getOrNull()
}
