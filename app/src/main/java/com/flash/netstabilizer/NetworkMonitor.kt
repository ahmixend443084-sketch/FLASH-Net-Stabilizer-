
package com.flash.netstabilizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

data class NetworkStatus(
    val connected: Boolean,
    val transport: String,
    val internetValidated: Boolean,
    val latencyMs: Long?,
    val averageLatencyMs: Long?,
    val jitterMs: Double?,
    val failedProbes: Int,
    val totalProbes: Int,
    val message: String
) {
    val failurePercent: Double
        get() = if (totalProbes == 0) {
            0.0
        } else {
            failedProbes * 100.0 / totalProbes
        }
}

class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val executor = Executors.newSingleThreadExecutor()

    private val manager = appContext.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager

    private val samples = ArrayDeque<Long>()
    private var totalProbes = 0
    private var failedProbes = 0

    @Volatile
    private var closed = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                // A network became available.
                // The next scheduled probe checks connectivity.
            }

            override fun onLost(network: Network) {
                // A network was lost.
                // The next check reports the current connection.
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Network capabilities may have changed.
            }
        }

    init {
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback
            )
        } catch (_: Exception) {
            // Monitoring still works through periodic checks.
        }
    }

    fun checkConnection(callback: (NetworkStatus) -> Unit) {
        if (closed) return

        executor.execute {
            if (closed) return@execute

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

            val validated =
                capabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true

            val latency = if (connected && validated) {
                probeLatency(network)
            } else {
                null
            }

            totalProbes++

            if (latency == null) {
                failedProbes++
            } else {
                samples.addLast(latency)

                while (samples.size > 12) {
                    samples.removeFirst()
                }
            }

            val average = if (samples.isNotEmpty()) {
                samples.average().toLong()
            } else {
                null
            }

            val jitter = calculateJitter()

            val message = when {
                !connected -> "No active network"
                !validated -> "Internet is not validated"
                latency == null -> "Probe failed; retrying"
                latency < 100 -> "Probe response time is low"
                latency < 200 -> "Probe response time is moderate"
                else -> "Probe response time is high"
            }

            if (!closed) {
                callback(
                    NetworkStatus(
                        connected = connected,
                        transport = transport,
                        internetValidated = validated,
                        latencyMs = latency,
                        averageLatencyMs = average,
                        jitterMs = jitter,
                        failedProbes = failedProbes,
                        totalProbes = totalProbes,
                        message = message
                    )
                )
            }
        }
    }

    private fun probeLatency(network: Network): Long? {
        var connection: HttpURLConnection? = null

        return try {
            val url = URL("https://connectivitycheck.gstatic.com/generate_204")

            connection = (network.openConnection(url)
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                instanceFollowRedirects = false
                useCaches = false
            }

            val start = System.nanoTime()
            connection.connect()
            val code = connection.responseCode
            val elapsed =
                (System.nanoTime() - start) / 1_000_000

            if (code == 204) elapsed else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun calculateJitter(): Double? {
        if (samples.size < 2) return null

        val values = samples.toList()
        val differences = values.zipWithNext { a, b ->
            abs(b - a).toDouble()
        }

        return if (differences.isEmpty()) {
            null
        } else {
            differences.average()
        }
    }

    fun close() {
        closed = true

        try {
            manager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Callback may not have been registered.
        }

        executor.shutdownNow()
    }
}
