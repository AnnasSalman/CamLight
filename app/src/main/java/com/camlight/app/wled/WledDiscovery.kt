package com.camlight.app.wled

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredDevice(val name: String, val host: String)

/** Finds WLED controllers advertising _wled._tcp via mDNS. */
class WledDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun discover(): Flow<DiscoveredDevice> = callbackFlow {
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        // NsdManager allows only one resolve at a time, so queue them.
        fun next() {
            if (resolving) return
            val svc = pending.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(svc, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    synchronized(pending) { resolving = false; next() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    info.host?.hostAddress?.let { trySend(DiscoveredDevice(info.serviceName, it)) }
                    synchronized(pending) { resolving = false; next() }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) { close() }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(pending) { pending.addLast(info); next() }
            }
        }
        nsd.discoverServices("_wled._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
}
