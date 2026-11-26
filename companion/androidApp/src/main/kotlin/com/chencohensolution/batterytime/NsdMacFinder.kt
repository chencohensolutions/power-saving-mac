package com.chencohensolution.batterytime

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A BatteryTime Mac found on the Wi-Fi */
data class FoundMac(val name: String, val host: String, val port: Int)

/** Finds BatteryTime Macs on the local network over Bonjour (the Mac advertises _batterytime._tcp) */
class NsdMacFinder(context: Context) : MacFinder {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)

    override suspend fun find(name: String): String? = withTimeoutOrNull(6_000) {
        macs().first { list -> list.any { it.name == name } }.first { it.name == name }.host
    }

    /** The Macs found so far, growing while collected */
    fun macs(): Flow<List<FoundMac>> = callbackFlow {
        // Some phones drop multicast packets unless an app holds this lock
        val lock = app.getSystemService(WifiManager::class.java)?.createMulticastLock("batterytime-nsd")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = linkedMapOf<String, FoundMac>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                if (found.remove(info.serviceName) != null) trySend(found.values.toList())
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                launchResolve(info) { mac ->
                    found[mac.name] = mac
                    trySend(found.values.toList())
                }
            }
        }
        nsd.discoverServices(Pairing.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        trySend(emptyList())
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock?.release()
        }
    }

    @Suppress("DEPRECATION")   // resolveService: its replacement (registerServiceInfoCallback) needs Android 14
    private fun launchResolve(info: NsdServiceInfo, onResolved: (FoundMac) -> Unit) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                onResolved(FoundMac(serviceInfo.serviceName, host.substringBefore('%'), serviceInfo.port))
            }
        })
    }
}
