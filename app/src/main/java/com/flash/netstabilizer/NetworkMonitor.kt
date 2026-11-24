
package com.flash.netstabilizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.Executors

data class NetworkStatus(
    val connected: Boolean,
    val transport: String,
    val internetValidated: Boolean,
    val latencyMs: Long?,
    val message: String
)

class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val executor = Executors.newSingleThreadExecutor()

    fun checkConnection(
        callback: (NetworkStatus) -> Unit
    ) {
        executor.execute {
            val manager = appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager

            val network = manager.activeNetwork
            val capabilities = network?.let {
                manager.getNetworkCapabilities(it)
            }

            val connected = capabilities != null

            val transport = when {
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true -> "Wi-Fi"

                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) == true -> "Mobile data"

                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                ) == true -> "Ethernet"

                connected -> "Other"

                else -> "Disconnected"
            }

            val validated = if (Build.VERSION.SDK_INT >= 23) {
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true
            } else {
                connected
            }

            val latency = if (connected) {
                measureLatency()
            } else {
                null
            }

            val message = when {
                !connected -> "No active network"
                !validated -> "Network has no confirmed internet access"
                latency == null -> "Connected; latency test failed"
                latency < 100 -> "Latency test looks good"
                latency < 200 -> "Latency is moderate"
                else -> "High latency detected"
            }

            callback(
                NetworkStatus(
                    connected = connected,
                    transport = transport,
                    internetValidated = validated,
                    latencyMs = latency,
                    message = message
                )
            )
        }
    }

    private fun measureLatency(): Long? {
        return try {
            val start = System.nanoTime()

            val address = InetAddress.getByName("connectivitycheck.gstatic.com")

            val reachable = address.isReachable(1500)

            val elapsed =
                (System.nanoTime() - start) / 1_000_000

            if (reachable) elapsed else null
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}
