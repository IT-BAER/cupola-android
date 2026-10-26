package com.baer.cupola.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.baer.cupola.feature.settings.Quality
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataStoreSettingsRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File.createTempFile("cupola-ds", "").apply { delete(); mkdirs() }

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun repo() = DataStoreSettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") },
    )

    @Test
    fun corruptFileResetsToDefaultsAndStaysWritable() = runBlocking {
        val file = File(dir, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x0a, 0x7f, 0x01, 0x02)) }
        val r = DataStoreSettingsRepository(settingsDataStore(scope) { file })
        assertEquals(Settings(), r.settings.first())
        r.setDust(false)
        assertEquals(false, r.settings.first().dust)
    }

    @Test
    fun emptyStoreYieldsDefaults() = runBlocking {
        assertEquals(Settings(), repo().settings.first())
    }

    @Test
    fun updatesPersistAcrossInstances() = runBlocking {
        val r = repo()
        r.setRestDirection(RestDirection.NADIR)
        r.setLookAroundStrength(0.25f)
        r.setIdleFloat(false)
        r.setBloom(false)
        r.setWindowGlare(false)
        r.setReflections(false)
        r.setDust(false)
        r.setHomeCity("Vienna|AT|48.208|16.372")
        val expected = Settings(RestDirection.NADIR, 0.25f, false, true, false, false, false, false, "Vienna|AT|48.208|16.372")
        assertEquals(expected, r.settings.first())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals(expected, DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(dir, "settings.preferences_pb") },
        ).settings.first())
    }

    @Test
    fun interiorDefaultsOnAndRoundTrips() = runBlocking {
        val r = repo()
        assertEquals(true, r.settings.first().interior)
        r.setInterior(false)
        assertEquals(false, r.settings.first().interior)
        r.setInterior(true)
        assertEquals(true, r.settings.first().interior)
    }

    @Test
    fun failedWriteKeepsTheOldValueInsteadOfThrowing() = runBlocking {
        val r = repo()
        r.setDust(false)
        // A directory at DataStore's scratch path makes the next write fail with an IOException.
        File(dir, "settings.preferences_pb.tmp").mkdir()
        r.setDust(true)
        assertEquals(false, r.settings.first().dust)
    }

    @Test
    fun onlineOrbitUpdatesDefaultOnAndPersistOff() = runBlocking {
        val r = repo()
        assertEquals(true, r.settings.first().onlineOrbitUpdates)
        r.setOnlineOrbitUpdates(false)
        assertEquals(false, r.settings.first().onlineOrbitUpdates)
    }

    @Test
    fun strengthIsClampedAndHomeCityCanBeCleared() = runBlocking {
        val r = repo()
        r.setLookAroundStrength(3f)
        assertEquals(1f, r.settings.first().lookAroundStrength)
        r.setLookAroundStrength(-1f)
        assertEquals(0f, r.settings.first().lookAroundStrength)
        r.setHomeCity("x")
        r.setHomeCity(null)
        assertEquals(null, r.settings.first().homeCityId)
    }

    @Test
    fun stillFpsDefaultsTo15AndOnlyTakesAChoice() = runBlocking {
        val r = repo()
        assertEquals(15, r.settings.first().stillFps)
        r.setStillFps(30)
        assertEquals(30, r.settings.first().stillFps)
        r.setStillFps(7)
        assertEquals(30, r.settings.first().stillFps)
    }

    @Test
    fun qualityAndCloudsDefaultHighAndOnAndRoundTrip() = runBlocking {
        val r = repo()
        assertEquals(Quality.HIGH, r.settings.first().quality)
        assertEquals(true, r.settings.first().clouds)
        r.setQuality(Quality.LOW)
        r.setClouds(false)
        assertEquals(Quality.LOW, r.settings.first().quality)
        assertEquals(false, r.settings.first().clouds)
    }

    @Test
    fun fieldOfViewDefaultsToTheRestLensAndIsClamped() = runBlocking {
        val r = repo()
        assertEquals(Settings.FOV_DEFAULT_DEG, r.settings.first().fieldOfViewDeg)
        r.setFieldOfView(80f)
        assertEquals(80f, r.settings.first().fieldOfViewDeg)
        r.setFieldOfView(500f)
        assertEquals(Settings.FOV_MAX_DEG, r.settings.first().fieldOfViewDeg)
        r.setFieldOfView(1f)
        assertEquals(Settings.FOV_MIN_DEG, r.settings.first().fieldOfViewDeg)
    }
}
