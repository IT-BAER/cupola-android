package com.baer.cupola.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import com.baer.cupola.feature.settings.ShareRenderer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UiIntentsTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun setWallpaperIntentCarriesTheServiceComponent() {
        val intent = WallpaperIntents.changeLiveWallpaper(context)
        assertEquals(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER, intent.action)
        val component = intent.getParcelableExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName::class.java)
        assertEquals(ComponentName(context.packageName, "com.baer.cupola.wallpaper.CupolaWallpaperService"), component)
    }

    @Test
    fun failedShareShowsAMessageInsteadOfTheChooser() = runBlocking {
        val controller = ShareController(context, ShareRenderer { _, _ -> error("render failed") }) { _, _ -> Uri.EMPTY }
        var started: Intent? = null
        controller.share { started = it }
        assertEquals(null, started)
        assertEquals("Could not create the image. Please try again.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun shareUriComesFromTheShareFileProvider() = runBlocking {
        val size = 4
        val rgba = ByteArray(size * size * 4) { 0x7F }
        // The JVM FileProvider cannot map Windows temp paths, so the URI mapping is faked here; the authority
        // and the manifest declaration are checked below.
        val controller = ShareController(context, ShareRenderer { _, _ -> rgba }) { c, f ->
            Uri.Builder().scheme("content").authority(ShareController.authority(c)).appendPath(f.name).build()
        }
        val intent = controller.createShareIntent(size, size)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.share", uri.authority)
        assertEquals(ShareController.authority(context), uri.authority)
        val provider = context.packageManager.resolveContentProvider(uri.authority!!, 0)!!
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertEquals(false, provider.exported)
        assertEquals(true, provider.grantUriPermissions)

        val png = File(File(context.cacheDir, "share"), "cupola.png").readBytes()
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
    }
}
