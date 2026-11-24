
package com.flash.netstabilizer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs

data class EndpointResult(
    val name: String,
    val latencyMs: Long?,
    val successful: Boolean
)

data class NetworkStatus(
    val connected: Boolean,
    val transport: String,
    val internetValidated: Boolean,
    val latencyMs: Long?,
    val averageLatencyMs: Long?,
    val jitterMs: Double?,
    val failedProbes: Int,
    val totalProbes: Int,
    val message: String,
    val bestEndpoint: String? = null,
    val bestEndpointLatencyMs: Long? = null,
    val endpointResults: List<EndpointResult> = emptyList()
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

    private val manager =
        appContext.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager

    private val executor = Executors.newSingleThreadExecutor()

    private val history = ArrayDeque<Long>()

    private var failedProbes = 0
    private var totalProbes = 0

    @Volatile
    private var closed = false

    private val endpoints = listOf(
        "Google" to "https://www.google.com/generate_204",
        "Google Connectivity" to
            "https://connectivitycheck.gstatic.com/generate_204",
        "Cloudflare" to "https://cp.cloudflare.com/generate_204"
    )

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The next periodic check uses the active network.
            }

            override fun onLost(network: Network) {
                // The next periodic check reports connectivity.
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Capabilities are checked during each monitoring cycle.
            }
        }

    init {
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                networkCallback
            )
        } catch (_: Exception) {
            // Periodic checks can still work.
        }
    }

    fun checkConnection(callback: (NetworkStatus) -> Unit) {
        if (closed) return

        executor.execute {
            if (closed) return@execute

            val network = manager.activeNetwork
            val caps = network?.let {
                manager.getNetworkCapabilities(it)
            }

            val connected = caps != null

            val transport = when {
                caps?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true -> "Wi-Fi"

                caps?.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) == true -> "Mobile data"

                caps?.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                ) == true -> "Ethernet"

                connected -> "Other"
                else -> "Disconnected"
            }

            val validated =
                caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true

            val results = if (network != null && validated) {
                endpoints.map { (name, address) ->
                    if (closed) {
                        EndpointResult(name, null, false)
                    } else {
                        val time = measureEndpoint(network, address)

                        EndpointResult(
                            name = name,
                            latencyMs = time,
                            successful = time != null
                        )
                    }
                }
            } else {
                emptyList()
            }

            val successful = results.filter {
                it.successful && it.latencyMs != null
            }

            val best = successful.minByOrNull {
                it.latencyMs!!
            }

            val currentLatency = best?.latencyMs

            totalProbes += results.size
            failedProbes += results.count { !it.successful }

            if (currentLatency != null) {
                history.addLast(currentLatency)

                while (history.size > 12) {
                    history.removeFirst()
                }
            }

            val average = if (history.isNotEmpty()) {
                history.average().toLong()
            } else {
                null
            }

            val jitter = calculateJitter()

            val message = when {
                !connected -> "No active network"
                !validated -> "Internet is not validated"
                successful.isEmpty() -> "All endpoint tests failed"
                currentLatency!! < 100 -> "Best test response is low"
                currentLatency < 200 -> "Best test response is moderate"
                else -> "Best test response is high"
            }

            if (!closed) {
                callback(
                    NetworkStatus(
                        connected = connected,
                        transport = transport,
                        internetValidated = validated,
                        latencyMs = currentLatency,
                        averageLatencyMs = average,
                        jitterMs = jitter,
                        failedProbes = failedProbes,
                        totalProbes = totalProbes,
                        message = message,
                        bestEndpoint = best?.name,
                        bestEndpointLatencyMs = best?.latencyMs,
                        endpointResults = results
                    )
                )
            }
        }
    }

    private fun measureEndpoint(
        network: Network,
        address: String
    ): Long? {
        var connection: HttpURLConnection? = null

        return try {
            connection = (network.openConnection(URL(address))
                as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1200
                instanceFollowRedirects = false
                useCaches = false
            }

            val start = System.nanoTime()
            connection.connect()

            val responseCode = connection.responseCode
            val elapsed =
                (System.nanoTime() - start) / 1_000_000

            if (responseCode == 204) elapsed else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun calculateJitter(): Double? {
        if (history.size < 2) return null

        val values = history.toList()

        return values.zipWithNext { first, second ->
            abs(second - first).toDouble()
        }.average()
    }

    fun close() {
        closed = true

        try {
            manager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }

        executor.shutdownNow()
    }
}
