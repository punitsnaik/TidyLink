package dev.punit.tidylink.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * LAN peer discovery over mDNS/NSD, the Android counterpart to desktop/shared's
 * `sync/Discovery.kt` (JmDNS). Same service type, so a phone and a Mac find
 * each other on the same network; NsdManager appends the mDNS ".local."
 * domain itself, so [SERVICE_TYPE] here deliberately omits it (JmDNS on the
 * desktop side does not - that difference is a platform-API quirk, not a
 * protocol mismatch, and both resolve to the same wire type).
 *
 * Both functions degrade to a silent no-op on failure - per the sync PRD, a
 * peer not being discoverable is the NORMAL case (Wi-Fi off, hotspot
 * isolation, etc.), never a surfaced error.
 *
 * ponytail: no retry/backoff on registration failure - NSD failures here are
 * rare and the user always has "Sync now" as a manual fallback. Add retry if
 * discovery flakiness turns out to be common in the field.
 */
object NsdDiscovery {

    const val SERVICE_TYPE = "_tidylink._tcp."
    private const val TAG = "NsdDiscovery"

    private val NOOP = AutoCloseable { }

    /** Advertise this device's sync server. Close the result to withdraw. */
    fun advertise(context: Context, deviceId: String, port: Int): AutoCloseable = try {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = deviceId
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        AutoCloseable { runCatching { manager.unregisterService(listener) } }
    } catch (e: Exception) {
        Log.w(TAG, "advertise unavailable", e)
        NOOP
    }

    /**
     * Watch for TidyLink peers; [onPeerSeen] is dispatched into [scope] with
     * (deviceId, host, port) each time a service resolves. Close to stop.
     */
    fun watch(
        context: Context,
        scope: CoroutineScope,
        onPeerSeen: (deviceId: String, host: String, port: Int) -> Unit,
    ): AutoCloseable = try {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val resolveListener = { info: NsdServiceInfo ->
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    scope.launch { onPeerSeen(info.serviceName, host, info.port) }
                }
            })
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType == SERVICE_TYPE) resolveListener(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        AutoCloseable { runCatching { manager.stopServiceDiscovery(discoveryListener) } }
    } catch (e: Exception) {
        Log.w(TAG, "watch unavailable", e)
        NOOP
    }
}
