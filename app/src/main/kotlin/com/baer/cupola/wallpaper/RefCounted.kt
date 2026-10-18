package com.baer.cupola.wallpaper

/** Creates the resource on the first [acquire] and destroys it on the last [release]. */
class RefCounted<T : Any>(private val create: () -> T, private val destroy: (T) -> Unit) {
    private var value: T? = null
    private var count = 0

    @Synchronized
    fun acquire(): T {
        val current = value ?: create().also { value = it }
        count++
        return current
    }

    @Synchronized
    fun release() {
        check(count > 0) { "release without acquire" }
        if (--count == 0) {
            val gone = checkNotNull(value)
            value = null
            destroy(gone)
        }
    }
}
