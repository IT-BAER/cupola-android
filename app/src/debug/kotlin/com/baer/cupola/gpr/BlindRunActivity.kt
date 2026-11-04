package com.baer.cupola.gpr

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONObject

/**
 * G-PR blind run, debug build only. Reads `<externalFilesDir>/gpr/trials.json`
 * (`{"seed": optional, "threshold": optional, "pairs": [{"id","photo","render"}]}`, files relative to the gpr folder),
 * writes `results-<UTC yyyyMMdd-HHmmss>.csv` next to it. Start with
 * `adb shell am start -n com.baer.cupola.debug/com.baer.cupola.gpr.BlindRunActivity`.
 */
class BlindRunActivity : Activity() {
    private enum class Phase { LOADING, READY, SHOWING, ASKING, DONE }

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val loads = HashMap<Int, Future<Array<Bitmap>>>()
    private lateinit var dir: File
    private lateinit var message: TextView
    private lateinit var images: LinearLayout
    private lateinit var leftView: ImageView
    private lateinit var rightView: ImageView
    private var run: BlindRun? = null
    private var phase = Phase.LOADING
    private var index = 0
    private var askedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        fun half() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        leftView = half()
        rightView = half()
        images = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            addView(leftView)
            addView(rightView)
        }
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(images, FrameLayout.LayoutParams(-1, -1))
                addView(message, FrameLayout.LayoutParams(-1, -1))
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onTap(if (event.x < width / 2f) Side.LEFT else Side.RIGHT)
                    true
                }
            },
        )
        dir = File(getExternalFilesDir(null), "gpr")
        try {
            run = parse(File(dir, "trials.json"))
            Log.i(TAG, "gpr start seed=${run!!.seed} trials=${run!!.trials.size} threshold=${run!!.threshold}")
            phase = Phase.READY
            message.text = "Tap to start\n${run!!.trials.size} trials"
            preload(0)
        } catch (e: Exception) {
            Log.w(TAG, "gpr cannot read trials.json", e)
            message.text = "Cannot read trials.json"
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        super.onDestroy()
    }

    private fun parse(file: File): BlindRun {
        val json = JSONObject(file.readText())
        val array = json.getJSONArray("pairs")
        val pairs = (0 until array.length()).map {
            array.getJSONObject(it).let { p -> GprPair(p.getString("id"), p.getString("photo"), p.getString("render")) }
        }
        require(pairs.isNotEmpty()) { "no pairs" }
        return BlindRun(
            pairs,
            if (json.has("seed")) json.getLong("seed") else System.currentTimeMillis(),
            if (json.has("threshold")) json.getInt("threshold") else null,
        )
    }

    private fun onTap(side: Side) {
        when (phase) {
            Phase.READY -> show(0)
            Phase.ASKING -> {
                val run = run ?: return
                run.answer(index, side, SystemClock.elapsedRealtime() - askedAt)
                if (index + 1 < run.trials.size) show(index + 1) else finish(run)
            }
            else -> Unit
        }
    }

    private fun preload(i: Int) {
        val run = run ?: return
        if (i >= run.trials.size || loads.containsKey(i)) return
        val trial = run.trials[i]
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels) / 2
        val h = minOf(dm.widthPixels, dm.heightPixels)
        val sides = if (trial.renderSide == Side.LEFT) trial.pair.render to trial.pair.photo else trial.pair.photo to trial.pair.render
        loads[i] = loader.submit(Callable { arrayOf(decode(File(dir, sides.first), w, h), decode(File(dir, sides.second), w, h)) })
    }

    private fun decode(file: File, w: Int, h: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return checkNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "cannot decode" }
    }

    private fun show(i: Int) {
        index = i
        phase = Phase.LOADING
        message.text = ""
        images.visibility = View.GONE
        preload(i)
        val pending = loads.remove(i)!!
        loader.submit {
            try {
                val bitmaps = pending.get()
                handler.post {
                    leftView.setImageBitmap(bitmaps[0])
                    rightView.setImageBitmap(bitmaps[1])
                    images.visibility = View.VISIBLE
                    phase = Phase.SHOWING
                    preload(i + 1)
                    handler.postDelayed({ ask() }, SHOW_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "gpr image load failed", e)
                handler.post { message.text = "Image load failed" }
            }
        }
    }

    private fun ask() {
        images.visibility = View.GONE
        leftView.setImageDrawable(null)
        rightView.setImageDrawable(null)
        message.text = "Which side was the render?\nTap left or right"
        askedAt = SystemClock.elapsedRealtime()
        phase = Phase.ASKING
    }

    private fun finish(run: BlindRun) {
        phase = Phase.DONE
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        File(dir, "results-$stamp.csv").writeText(run.csv() + "# " + run.summary() + "\n")
        Log.i(TAG, run.summary())
        message.text = "${run.correct} / ${run.trials.size} correct\n" + if (run.pass) "PASS" else "FAIL"
    }

    private companion object {
        const val TAG = "CupolaGpr"
        const val SHOW_MS = 3000L
    }
}
