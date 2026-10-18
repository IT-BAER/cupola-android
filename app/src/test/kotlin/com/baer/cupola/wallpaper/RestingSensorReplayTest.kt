package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.scene.LookAroundController
import org.junit.Assert.assertEquals
import org.junit.Test

/** Replays real game-rotation-vector samples of a resting A35 through the look-around at 15 fps. */
class RestingSensorReplayTest {
    private class Sample(val ns: Long, val values: FloatArray)

    private fun load(name: String): List<Sample> =
        checkNotNull(javaClass.getResourceAsStream("/sensor/$name")).bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.trim().split(' ')
                Sample(f[0].toLong(), FloatArray(4) { f[it + 1].toFloat() })
            }

    @Test fun phoneFlatOnTableSettlesToStill() {
        val samples = load("resting-flat-a35.txt")
        var now = samples.first().ns
        val look = LookAroundController { now }
        look.onVisible()
        val frameNs = 66_666_667L
        var nextFrame = now
        var movingFrames = 0
        var framesAfterSettle = 0
        for (s in samples) {
            while (nextFrame <= s.ns) {
                now = nextFrame
                val out = look.update()
                if (now - samples.first().ns >= SETTLE_NS) {
                    framesAfterSettle++
                    if (out.isMoving) movingFrames++
                }
                nextFrame += frameNs
            }
            now = s.ns
            val o = RotationVector.fromSensorValues(s.values)
            look.setOrientation(o.yawRad, o.pitchRad)
        }
        assertEquals("moving frames of $framesAfterSettle after the first 3 s", 0, movingFrames)
    }

    private companion object {
        // 1 s still detection plus spring settling.
        const val SETTLE_NS = 3_000_000_000L
    }
}
