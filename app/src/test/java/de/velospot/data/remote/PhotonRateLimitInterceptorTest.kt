package de.velospot.data.remote

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [PhotonRateLimitInterceptor] using a mocked [Interceptor.Chain].
 * Verifies the request is forwarded untouched and that a second request arriving
 * within the configured minimum interval is throttled (the OkHttp thread sleeps for the
 * remaining gap), while the very first request passes through immediately.
 */
class PhotonRateLimitInterceptorTest {

    private val request: Request =
        Request.Builder().url("https://photon.komoot.io/reverse").build()

    private fun response(): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("".toResponseBody(null))
        .build()

    private fun chain(): Interceptor.Chain {
        val chain = mock<Interceptor.Chain>()
        whenever(chain.request()).thenReturn(request)
        whenever(chain.proceed(any())).thenReturn(response())
        return chain
    }

    @Test
    fun `intercept forwards the chain request and returns its response`() {
        val chain = chain()
        val expected = response()
        whenever(chain.proceed(any())).thenReturn(expected)

        val result = PhotonRateLimitInterceptor(minIntervalMs = 1).intercept(chain)

        assertSame(expected, result)
        verify(chain).proceed(request)
    }

    @Test
    fun `the first request is not delayed`() {
        val chain = chain()

        val start = System.nanoTime()
        PhotonRateLimitInterceptor(minIntervalMs = 1_000).intercept(chain)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("first request should pass through immediately, waited ${elapsedMs}ms", elapsedMs < 400)
    }

    @Test
    fun `a second immediate request is throttled by roughly the interval`() {
        val chain = chain()
        val interceptor = PhotonRateLimitInterceptor(minIntervalMs = 150)

        interceptor.intercept(chain) // primes lastRequestAt, no wait
        val start = System.nanoTime()
        interceptor.intercept(chain) // must wait ~150 ms
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("second request should be throttled, waited ${elapsedMs}ms", elapsedMs >= 120)
        verify(chain, times(2)).proceed(request)
    }
}

