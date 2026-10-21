package com.baer.cupola

import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.data.DataStoreSettingsRepository
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.wallpaper.RendererHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Plan P6: a settings change reaches the render session within 1 s (DataStore write -> host flow -> frame inputs). */
@RunWith(AndroidJUnit4::class)
class SettingsReachRendererTest {
    @Test
    fun restDirectionChangeReachesTheSessionWithinOneSecond() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.preferencesDataStoreFile("settings_reach_test").apply { delete() }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
        val repository = DataStoreSettingsRepository(store)
        repository.setRestDirection(RestDirection.DIAGONAL)
        val host = RendererHost(context, repository)
        val (id, session) = host.openSession()
        try {
            val back = { session.nextFrameInputs()[FrameInputs.CAMERA_BACK + 2] }
            waitUntil(5_000) { host.settings.rest.name == "DIAGONAL" }
            val before = back()
            val start = System.nanoTime()
            repository.setRestDirection(RestDirection.NADIR)
            val reached = waitUntil(1_000) { kotlin.math.abs(back() - before) > 0.05f }
            val ms = (System.nanoTime() - start) / 1e6
            Log.i("SettingsReachRendererTest", "rest direction reached the session after %.1f ms".format(ms))
            assertTrue("no change within 1 s (camera back z stayed %.3f)".format(before), reached)
        } finally {
            host.closeSession(id)
            file.delete()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}
