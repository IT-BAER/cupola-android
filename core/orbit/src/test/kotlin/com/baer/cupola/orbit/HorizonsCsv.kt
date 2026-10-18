package com.baer.cupola.orbit

/** Reads a committed Horizons fixture: skips `#` comments and the header row. */
internal fun readHorizonsCsv(resource: String): List<DoubleArray> {
    val text = checkNotNull(object {}.javaClass.getResource(resource)) { "missing $resource" }.readText()
    return text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .drop(1)
        .map { line -> line.split(',').map { it.trim().toDouble() }.toDoubleArray() }
        .toList()
}
