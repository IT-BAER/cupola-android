package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RefCountedTest {
    private var created = 0
    private val destroyed = mutableListOf<Int>()
    private val shared = RefCounted(create = { ++created }, destroy = { destroyed += it })

    @Test fun firstAcquireCreatesLaterAcquiresShare() {
        val a = shared.acquire()
        val b = shared.acquire()
        assertSame(a, b)
        assertEquals(1, created)
    }

    @Test fun lastReleaseDestroysOnce() {
        shared.acquire()
        shared.acquire()
        shared.release()
        assertEquals(emptyList<Int>(), destroyed)
        shared.release()
        assertEquals(listOf(1), destroyed)
    }

    @Test fun acquireAfterFullReleaseCreatesNew() {
        shared.acquire()
        shared.release()
        assertEquals(2, shared.acquire())
        assertEquals(2, created)
    }

    @Test fun releaseWithoutAcquireFails() {
        assertThrows(IllegalStateException::class.java) { shared.release() }
    }
}
