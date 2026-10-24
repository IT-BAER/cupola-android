package com.baer.cupola.data

import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One blocking GET; the tests substitute a fake so they never touch the network. */
fun interface HttpGet {
    fun get(url: String): Response

    /** [body] is null for any status other than 200 and for a body over the size cap. */
    data class Response(val status: Int, val body: String?)
}

/** Plain [HttpURLConnection]: no redirects, no cache, [TIMEOUT_MS] connect and read timeouts, bodies up to [MAX_BYTES]. */
class UrlConnectionGet : HttpGet {
    override fun get(url: String): HttpGet.Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return HttpGet.Response(status, null)
            val bytes = connection.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
            return HttpGet.Response(status, if (bytes.size > MAX_BYTES) null else bytes.decodeToString())
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 4096
    }
}

/**
 * Refreshes the orbit from the GitHub Pages mirror at most once per [INTERVAL] plus [jitter], only when a wallpaper
 * becomes visible and [enabled] (the "Update orbit data online" setting) is on. The attempt time is stored before the
 * request, so a failure of any kind (non-200, I/O, validation) waits for the next window instead of retrying.
 */
class OrbitUpdater(
    private val http: HttpGet,
    private val repository: OrbitRepository,
    private val store: DownloadedOrbitStore,
    private val enabled: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
    private val jitter: () -> Duration,
) {
    // Home and lock engines turn visible together; the second waits and then sees the stored window.
    private val mutex = Mutex()

    /** Starts an update check on [scope] when [visible]; null when nothing is started. */
    fun onVisibilityChanged(visible: Boolean): Job? = if (visible) scope.launch { update() } else null

    private suspend fun update() = mutex.withLock {
        if (!enabled()) return@withLock
        val t = now()
        val next = store.nextAttempt()
        // A next attempt further away than one full window means the clock was set back; do not wait for it.
        if (next != null && t < next && Duration.between(t, next) <= INTERVAL.plus(MAX_JITTER)) return@withLock
        try {
            store.setNextAttempt(t.plus(INTERVAL).plus(jitter()))
            val tle = http.get(TLE_URL).body ?: return@withLock
            val json = http.get(JSON_URL).body ?: return@withLock
            repository.offer(tle, json)
        } catch (e: Exception) {
            // I/O, timeout or a platform refusal: the data in use stays and the next window retries.
        }
    }

    companion object {
        const val TLE_URL = "https://it-baer.github.io/cupola-android/orbit/iss.tle"
        const val JSON_URL = "https://it-baer.github.io/cupola-android/orbit/iss.json"
        val INTERVAL: Duration = Duration.ofHours(24)
        val MAX_JITTER: Duration = Duration.ofHours(4)
    }
}
