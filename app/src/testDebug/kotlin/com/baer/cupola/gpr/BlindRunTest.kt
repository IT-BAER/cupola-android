package com.baer.cupola.gpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindRunTest {
    private fun pairs(n: Int) = (1..n).map { GprPair("p%02d".format(it), "p%02d-photo.png".format(it), "p%02d-render.png".format(it)) }

    private fun run(n: Int, seed: Long = 1L, threshold: Int? = null) = BlindRun(pairs(n), seed, threshold)

    @Test fun twoTrialsPerPair() {
        assertEquals(20, run(10).trials.size)
    }

    @Test fun everyPairAppearsOnceOnEachSide() {
        val trials = run(10).trials
        for (p in pairs(10)) {
            val sides = trials.filter { it.pair.id == p.id }.map { it.renderSide }
            assertEquals(listOf(Side.LEFT, Side.RIGHT), sides.sortedBy { it.ordinal })
        }
    }

    @Test fun sameSeedSameOrderOtherSeedOtherOrder() {
        fun order(seed: Long) = run(10, seed).trials.map { it.pair.id to it.renderSide }
        assertEquals(order(7), order(7))
        assertNotEquals(order(7), order(8))
    }

    @Test fun noPairTwiceInARow() {
        for (seed in 1L..50L) {
            val ids = run(10, seed).trials.map { it.pair.id }
            assertTrue("seed $seed", ids.zipWithNext().none { (a, b) -> a == b })
        }
    }

    @Test fun singlePairStillRuns() {
        assertEquals(2, run(1).trials.size)
    }

    @Test fun defaultThresholdFollowsBinomialRule() {
        assertEquals(14, BlindRun.defaultThreshold(10))
        assertEquals(12, BlindRun.defaultThreshold(8))
        assertEquals(14, run(10).threshold)
        assertEquals(9, run(10, threshold = 9).threshold)
    }

    @Test fun scoringAndPassRule() {
        val failing = run(10, threshold = 14)
        failing.trials.forEachIndexed { i, t -> failing.answer(i, if (i < 15) t.renderSide else t.renderSide.other(), 100L + i) }
        assertEquals(15, failing.correct)
        assertFalse(failing.pass)
        val passing = run(10, threshold = 14)
        passing.trials.forEachIndexed { i, t -> passing.answer(i, if (i < 14) t.renderSide else t.renderSide.other(), 100L) }
        assertEquals(14, passing.correct)
        assertTrue(passing.pass)
    }

    @Test fun csvAndSummaryFormat() {
        val run = run(1, seed = 3, threshold = 1)
        val first = run.trials[0].renderSide.name.lowercase()
        run.answer(0, run.trials[0].renderSide, 812)
        run.answer(1, run.trials[1].renderSide.other(), 640)
        val rows = run.csv().trim().lines()
        assertEquals("trial,pair,render_side,tapped_side,correct,response_ms", rows[0])
        assertEquals("1,p01,$first,$first,true,812", rows[1])
        assertTrue(rows[2].endsWith(",false,640"))
        assertEquals("gpr result correct=1/2 pass=true threshold=1 seed=3", run.summary())
    }
}
