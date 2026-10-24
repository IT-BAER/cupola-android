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
import java.util.concurrent.Executors
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

        // Wallpaper colours from a small render of the last frame (F15); main thread except the render itself.
        private val paletteExecutor = Executors.newSingleThreadExecutor()
        private var palette: FramePalette? = null
        private val paletteTick = object : Runnable {
            override fun run() {
                val inputs = session.lastFrameInputs()
                if (inputs != null) {
                    paletteExecutor.execute {
                        val pixels = runCatching { renderer.renderOffscreen(PALETTE_W, PALETTE_H, inputs) }.getOrNull()
                            ?: return@execute
                        val next = FramePalette.of(pixels, PALETTE_W, PALETTE_H)
                        mainHandler.post {
                            if (next != palette) {
                                palette = next
                                Log.i(LOG_TAG, "colors #%06x #%06x #%06x".format(next.primary, next.secondary, next.tertiary))
                                notifyColorsChanged()
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, PALETTE_REFRESH_MS)
            }
        }

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
            mainHandler.removeCallbacks(paletteTick)
            if (visible) mainHandler.postDelayed(paletteTick, PALETTE_FIRST_MS)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int,
        ) {
            state = state.copy(xOffset = xOffset)
            pushState()
        }

        override fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            Gate.onCommand(action, x, y, z, extras)
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

        // The fixed palette stands in until the first frame has been sampled.
        override fun onComputeColors(): WallpaperColors = palette?.let {
            WallpaperColors(
                Color.valueOf(OPAQUE or it.primary),
                Color.valueOf(OPAQUE or it.secondary),
                Color.valueOf(OPAQUE or it.tertiary),
                (if (it.supportsDarkText) WallpaperColors.HINT_SUPPORTS_DARK_TEXT else 0) or
                    (if (it.supportsDarkTheme) WallpaperColors.HINT_SUPPORTS_DARK_THEME else 0),
            )
        } ?: WallpaperColors(
            Color.valueOf(DEEP_SPACE_BLUE),
            Color.valueOf(EARTH_BLUE),
            null,
            WallpaperColors.HINT_SUPPORTS_DARK_THEME,
        )

        override fun onDestroy() {
            mainHandler.removeCallbacks(tapTick)
            mainHandler.removeCallbacks(paletteTick)
            paletteExecutor.shutdownNow()
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
        const val OPAQUE = 0xFF000000.toInt()

        /** Palette render size (the screen's aspect, about 6300 pixels) and timing. */
        const val PALETTE_W = 54
        const val PALETTE_H = 117
        const val PALETTE_FIRST_MS = 1_000L
        const val PALETTE_REFRESH_MS = 15 * 60_000L
    }
}
