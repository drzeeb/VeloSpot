package de.velospot.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Client-side rate limiter that enforces the **Nominatim usage policy** of at most
 * one request per second (https://operations.osmfoundation.org/policies/nominatim/).
 *
 * Installed only on the dedicated Nominatim [okhttp3.OkHttpClient] (see
 * `NetworkModule`), it serialises calls and, if two requests arrive closer than
 * [minIntervalMs] apart, sleeps the OkHttp dispatcher thread for the remaining
 * gap. Because Nominatim calls are user-triggered and already debounced, the brief
 * wait is invisible in practice but guarantees the app can never burst past the
 * policy limit (e.g. when several parking pins are tapped in quick succession) and
 * risk an IP ban.
 */
class NominatimRateLimitInterceptor(
    private val minIntervalMs: Long = 1_100L
) : Interceptor {

    private val lock = ReentrantLock(true)
    private var lastRequestAtMs = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        lock.withLock {
            val now = System.currentTimeMillis()
            val wait = lastRequestAtMs + minIntervalMs - now
            if (wait > 0) {
                try {
                    Thread.sleep(wait)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestAtMs = System.currentTimeMillis()
        }
        return chain.proceed(chain.request())
    }
}

