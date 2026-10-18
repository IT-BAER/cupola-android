package com.baer.cupola.wallpaper

import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CupolaWallpaperService : WallpaperService() {
    @Inject lateinit var host: RendererHost

    override fun onCreateEngine(): Engine = CupolaEngine()

    private inner class CupolaEngine : Engine() {
        private val renderer = host.acquire()
        private val surfaceId: Int
        private val session: RenderSession
        private val sensor: OrientationSensor
        private val mainHandler = Handler(Looper.getMainLooper())
        private val powerManager = getSystemService(PowerManager::class.java)
        private val tapDetector = TapDetector(
            timeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
            slopPx = ViewConfiguration.get(this@CupolaWallpaperService).scaledDoubleTapSlop.toFloat(),
            listener = LogTapListener,
        )
        private val tapTick = Runnable { tapDetector.tick(SystemClock.uptimeMillis()) }
        private val powerSaveReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                state = state.copy(batterySaver = powerManager.isPowerSaveMode)
                pushState()
            }
        }

        private var state = EngineState()
        private var attached = false
        private var countedVisible = false

        init {
            val opened = host.openSession()
            surfaceId = opened.first
            session = opened.second
            sensor = OrientationSensor(getSystemService(SensorManager::class.java), session::onOrientation)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            state = state.copy(
                preview = isPreview,
                batterySaver = powerManager.isPowerSaveMode,
                reducedMotion = !ValueAnimator.areAnimatorsEnabled(),
            )
            registerReceiver(
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            session.surface = holder.surface
            val result = renderer.attachSurface(surfaceId, holder.surface)
            if (result < 0) {
                // No renderer for this surface; the engine stays inert until the next onSurfaceCreated.
                Log.w(LOG_TAG, "attach $surfaceId failed: VkResult $result")
                return
            }
            attached = true
            logWallpaperFlags()
            pushState()
            syncSensor()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (!attached) return
            renderer.resizeSurface(surfaceId, width, height)
            pushState()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (!attached) return
            // Re-presents with the last inputs; fresh inputs arrive with the next scheduled frame.
            renderer.renderFrame(surfaceId)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            attached = false
            sensor.stop()
            renderer.detachSurface(surfaceId)
            session.surface = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            state = state.copy(visible = visible, reducedMotion = !ValueAnimator.areAnimatorsEnabled())
            if (visible != countedVisible) {
                countedVisible = visible
                host.engineVisible(visible)
            }
            pushState()
            syncSensor()
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int,
        ) {
            state = state.copy(xOffset = xOffset)
            pushState()
        }

        override fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP) {
                tapDetector.onTap(x.toFloat(), y.toFloat(), SystemClock.uptimeMillis())
                mainHandler.removeCallbacks(tapTick)
                tapDetector.deadlineMs?.let { mainHandler.postDelayed(tapTick, it - SystemClock.uptimeMillis()) }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            logWallpaperFlags()
        }

        // Fixed palette until P3 can sample the real frame.
        override fun onComputeColors(): WallpaperColors = WallpaperColors(
            Color.valueOf(DEEP_SPACE_BLUE),
            Color.valueOf(EARTH_BLUE),
            null,
            WallpaperColors.HINT_SUPPORTS_DARK_THEME,
        )

        override fun onDestroy() {
            mainHandler.removeCallbacks(tapTick)
            unregisterReceiver(powerSaveReceiver)
            sensor.stop()
            if (attached) {
                attached = false
                renderer.detachSurface(surfaceId)
                session.surface = null
            }
            if (countedVisible) {
                countedVisible = false
                host.engineVisible(false)
            }
            host.closeSession(surfaceId)
            host.release()
            super.onDestroy()
        }

        private fun pushState() {
            if (!attached) return
            val snapshot = state
            renderer.updateScheduler(surfaceId) { session.apply(snapshot, this) }
        }

        private fun syncSensor() {
            if (attached && state.visible) sensor.start(renderer, surfaceId) else sensor.stop()
        }

        private fun logWallpaperFlags() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            val flags = wallpaperFlags
            val system = flags and WallpaperManager.FLAG_SYSTEM != 0
            val lock = flags and WallpaperManager.FLAG_LOCK != 0
            Log.i(LOG_TAG, "flags=$flags system=$system lock=$lock")
        }
    }

    private companion object {
        const val DEEP_SPACE_BLUE = 0xFF050B1F.toInt()
        const val EARTH_BLUE = 0xFF2E6FD8.toInt()
    }
}
