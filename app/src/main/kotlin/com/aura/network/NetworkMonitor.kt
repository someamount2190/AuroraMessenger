package com.aura.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime view of whether the device has *any* network, so the [SyncEngine]
 * loop can stop hammering doomed calls while offline and resume the instant connectivity
 * returns — instead of spinning on a fixed interval. Backed by a single default-network
 * callback registered for the life of the process (never unregistered: the sync loop
 * outlives every Activity, so there is nothing to leak).
 *
 * "Online" deliberately means *a default network exists*, **not** that it is internet-
 * validated. That keeps Aurora's same-Wi-Fi Server Mode working on a LAN-only access
 * point (which never validates internet) — a network that's present but can't reach the
 * rendezvous server is left to the loop's exponential backoff, not gated out here.
 */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    // If there's no ConnectivityManager at all, degrade to "always online" so the loop
    // behaves exactly as it did before this monitor existed (attempt + let timeouts/backoff
    // handle failure) rather than parking forever and never doing any networking.
    private val _online = MutableStateFlow(cm == null || cm.activeNetwork != null)
    val online: StateFlow<Boolean> = _online

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = false }
            })
        }
    }

    /** Whether the device currently has a default network. */
    fun isOnline(): Boolean = _online.value

    /**
     * Suspend until the device is online, or [timeoutMs] elapses. Returns immediately
     * (true) if already online; false on timeout — the caller then re-checks and re-parks,
     * so even a missed callback can't strand the loop offline forever.
     */
    suspend fun awaitOnline(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { online.first { it } } != null
}
