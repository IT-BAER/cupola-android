package com.baer.cupola.core.render

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BlockingCallTest {
    @Test
    fun returnsValueComputedOnTargetThread() {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "target") }
        try {
            val name = BlockingCall.run(post = { executor.execute(it); true }) { Thread.currentThread().name }
            assertEquals("target", name)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun rethrowsTargetException() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val failure = IllegalStateException("boom")
            val thrown = assertThrows(IllegalStateException::class.java) {
                BlockingCall.run(post = { executor.execute(it); true }) { throw failure }
            }
            assertSame(failure, thrown)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun failsWhenPostIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            BlockingCall.run(post = { false }) { 1 }
        }
    }
}
