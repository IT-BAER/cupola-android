package com.baer.cupola.core.render

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Runs [block] on another thread via [post] and blocks the caller until it finished. */
object BlockingCall {
    fun <T> run(post: (Runnable) -> Boolean, block: () -> T): T {
        val task = FutureTask(block)
        check(post(task)) { "target thread is not running" }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
