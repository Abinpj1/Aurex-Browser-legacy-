package com.aurex.browser

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttp base client for lightweight browser networking (AdBlocker, AdultFilter,
 * Favicons, Stream Sniffer, Updater). Derived clients via [shared].newBuilder() reuse the exact
 * same ConnectionPool, Dispatcher thread pool, and TLS session cache — saving threads & RAM.
 */
object AurexHttp {
    private val pool = ConnectionPool(
        if (PerfTier.low) 4 else 8,
        3,
        TimeUnit.MINUTES
    )

    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(Dispatcher().apply {
                maxRequests = if (PerfTier.low) 20 else 48
                maxRequestsPerHost = if (PerfTier.low) 6 else 12
            })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Closes idle keep-alive sockets under OS memory pressure. */
    fun trim() {
        runCatching { pool.evictAll() }
    }
}
