package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateVoteTest {
    private val sent = mutableListOf<Pair<Any, Int>>()
    private val vote = FrameRateVote<Any> { surface, fps -> sent += surface to fps }

    @Test fun unchangedRateOnSameSurfaceIsSentOnce() {
        val surface = Any()
        vote.surface = surface
        vote.sync(15)
        vote.sync(15)
        assertEquals(listOf(surface to 15), sent)
    }

    @Test fun voteIsResentAfterTheSameSurfaceObjectIsSetAgain() {
        val surface = Any()
        vote.surface = surface
        vote.sync(15)
        vote.surface = null
        vote.surface = surface
        vote.sync(15)
        assertEquals(listOf(surface to 15, surface to 15), sent)
    }
}
