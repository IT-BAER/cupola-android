package com.baer.cupola.orbit.sgp4

/**
 * Plain SGP4 up to [freezeAfterMinutes] after the TLE epoch. Later the drag terms (tempa, tempe) keep their
 * value at the freeze age and mean anomaly, perigee and node advance linearly with their full rates there,
 * so an old TLE holds the altitude it had at the freeze age instead of decaying without bound.
 */
class DragGuardedPropagator(
    private val sgp4: Sgp4,
    val freezeAfterMinutes: Double = DEFAULT_FREEZE_AFTER_MINUTES,
) {
    init {
        require(freezeAfterMinutes.isFinite() && freezeAfterMinutes >= 0.0) {
            "freezeAfterMinutes must be finite and >= 0, was $freezeAfterMinutes"
        }
    }

    private val frozen = sgp4.secular(freezeAfterMinutes)
    private val rates = sgp4.secularRates(freezeAfterMinutes)

    fun propagate(minutesSinceEpoch: Double): PropagationResult {
        if (minutesSinceEpoch <= freezeAfterMinutes) return sgp4.propagate(minutesSinceEpoch)
        val dt = minutesSinceEpoch - freezeAfterMinutes
        return sgp4.periodics(
            frozen.copy(
                mm = frozen.mm + rates.mm * dt,
                argpm = frozen.argpm + rates.argpm * dt,
                nodem = frozen.nodem + rates.nodem * dt,
            ),
        )
    }

    companion object {
        const val DEFAULT_FREEZE_AFTER_MINUTES = 14.0 * 1440.0
    }
}
