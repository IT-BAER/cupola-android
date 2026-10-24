package com.baer.cupola.data

import com.baer.cupola.data.OrbitFixtures.BUNDLED_JSON
import com.baer.cupola.data.OrbitFixtures.BUNDLED_TLE
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitUpdaterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File.createTempFile("cupola-orbit", "").apply { delete(); mkdirs() }
    private var now = Instant.parse("2026-10-24T13:00:00Z")
    private var enabled = true
    private val requests = mutableListOf<String>()
    private val fresh = OrbitFixtures.tle()
    private var responses: Map<String, HttpGet.Response> = mapOf(
        OrbitUpdater.TLE_URL to HttpGet.Response(200, fresh),
        OrbitUpdater.JSON_URL to HttpGet.Response(200, OrbitFixtures.jsonFor(fresh)),
    )
    private val http = HttpGet { url -> requests += url; responses.getValue(url) }
    private val store = DownloadedOrbitStore(dir)
    private val repository = OrbitRepository({ BUNDLED_TLE to BUNDLED_JSON }, store) { now }
    private val bundledEpoch = repository.current().tle.epoch

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun updater(s: DownloadedOrbitStore = store, r: OrbitRepository = repository) =
        OrbitUpdater(http, r, s, enabled = { enabled }, scope = scope, now = { now }, jitter = { JITTER })

    private fun OrbitUpdater.visible() = runBlocking { onVisibilityChanged(true)?.join() }

    private fun serve(tle: String, json: String = OrbitFixtures.jsonFor(tle)) {
        responses = mapOf(OrbitUpdater.TLE_URL to HttpGet.Response(200, tle), OrbitUpdater.JSON_URL to HttpGet.Response(200, json))
    }

    @Test
    fun visibleWallpaperFetchesBothFilesOnceAndSwapsTheOrbit() {
        updater().visible()
        assertEquals(listOf(OrbitUpdater.TLE_URL, OrbitUpdater.JSON_URL), requests)
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), repository.current().tle.epoch)
        assertEquals(Instant.parse("2026-10-24T12:00:00Z"), repository.current().model.epoch)
        assertEquals(fresh, store.read()?.first)
    }

    @Test
    fun nextRequestWaitsTwentyFourHoursPlusJitter() {
        val u = updater()
        u.visible()
        requests.clear()
        now = now.plus(Duration.ofHours(24))
        u.visible()
        now = now.plus(JITTER).minusSeconds(1)
        u.visible()
        assertEquals(emptyList<String>(), requests)
        now = now.plusSeconds(1)
        u.visible()
        assertEquals(OrbitUpdater.TLE_URL, requests.first())
    }

    @Test
    fun theGateSurvivesARestart() {
        updater().visible()
        requests.clear()
        now = now.plus(Duration.ofHours(1))
        val restarted = DownloadedOrbitStore(dir)
        updater(restarted, OrbitRepository({ BUNDLED_TLE to BUNDLED_JSON }, restarted) { now }).visible()
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun aClockSetFarBackDoesNotBlockUpdatesForDays() {
        updater().visible()
        requests.clear()
        now = now.minus(Duration.ofDays(30))
        updater().visible()
        assertEquals(OrbitUpdater.TLE_URL, requests.first())
    }

    @Test
    fun invisibleMakesNoRequest() {
        assertNull(updater().onVisibilityChanged(false))
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun toggleOffMakesNoRequestAndKeepsTheWindowOpen() {
        enabled = false
        val u = updater()
        u.visible()
        assertEquals(emptyList<String>(), requests)
        enabled = true
        u.visible()
        assertEquals(OrbitUpdater.TLE_URL, requests.first())
    }

    @Test
    fun non200KeepsTheDataAndDoesNotRetryInTheWindow() {
        responses = mapOf(OrbitUpdater.TLE_URL to HttpGet.Response(403, null))
        val u = updater()
        u.visible()
        assertEquals(listOf(OrbitUpdater.TLE_URL), requests)
        assertEquals(bundledEpoch, repository.current().tle.epoch)
        now = now.plus(Duration.ofHours(1))
        u.visible()
        assertEquals(listOf(OrbitUpdater.TLE_URL), requests)
        assertNull(store.read())
    }

    @Test
    fun aNetworkErrorCountsAsAnAttempt() {
        var calls = 0
        val failing = HttpGet { calls++; throw IOException("timeout") }
        val u = OrbitUpdater(failing, repository, store, enabled = { true }, scope = scope, now = { now }, jitter = { JITTER })
        runBlocking { u.onVisibilityChanged(true)?.join() }
        runBlocking { u.onVisibilityChanged(true)?.join() }
        assertEquals(1, calls)
        assertEquals(bundledEpoch, repository.current().tle.epoch)
    }

    @Test
    fun anOversizedBodyIsRejected() {
        responses = mapOf(OrbitUpdater.TLE_URL to HttpGet.Response(200, null))
        updater().visible()
        assertEquals(listOf(OrbitUpdater.TLE_URL), requests)
        assertEquals(bundledEpoch, repository.current().tle.epoch)
    }

    private fun assertRejected(tle: String, json: String = OrbitFixtures.jsonFor(tle)) {
        serve(tle, json)
        updater().visible()
        assertEquals(2, requests.size)
        assertEquals(bundledEpoch, repository.current().tle.epoch)
        assertNull(store.read())
        assertFalse(File(dir, "iss.tle").exists())
    }

    @Test fun rejectsAnOlderTle() = assertRejected(OrbitFixtures.tle(epoch = "26290.50000000"))

    @Test fun rejectsTheSameEpoch() = assertRejected(BUNDLED_TLE)

    @Test fun rejectsAWrongInclination() = assertRejected(OrbitFixtures.tle(inclination = " 51.9000"))

    @Test fun rejectsABadChecksum() = assertRejected(OrbitFixtures.badChecksum(fresh), OrbitFixtures.jsonFor(fresh))

    @Test fun rejectsAnEpochMoreThanADayAhead() = assertRejected(OrbitFixtures.tle(epoch = "26298.60000000"))

    @Test fun rejectsAJsonForAnotherEpoch() = assertRejected(fresh, BUNDLED_JSON)

    @Test fun rejectsAnotherSatellite() = assertRejected(fresh.replace(" 25544", " 25545").let(::fixChecksums))

    @Test
    fun acceptsAnEpochJustUnderADayAhead() {
        serve(OrbitFixtures.tle(epoch = "26298.50000000"))
        updater().visible()
        assertTrue(repository.current().tle.epoch > now)
    }

    private fun fixChecksums(text: String) = text.lines().joinToString("\n") {
        if (it.length == 69) it.take(68) + Tle.checksum(it) else it
    }

    private companion object {
        val JITTER: Duration = Duration.ofMinutes(30)
    }
}
