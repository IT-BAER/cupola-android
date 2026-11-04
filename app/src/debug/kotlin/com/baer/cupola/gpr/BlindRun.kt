package com.baer.cupola.gpr

import java.util.Locale
import kotlin.random.Random

enum class Side {
    LEFT,
    RIGHT,
    ;

    fun other() = if (this == LEFT) RIGHT else LEFT
}

/** Opaque pair id plus the photo and render file names, relative to the gpr folder. */
data class GprPair(val id: String, val photo: String, val render: String)

class Trial(val pair: GprPair, val renderSide: Side) {
    var tapped: Side? = null
    var responseMs: Long = 0
    val correct get() = tapped == renderSide
}

/** G-PR blind run: every pair once with the render left and once right, in a seed-determined order. */
class BlindRun(pairs: List<GprPair>, val seed: Long, threshold: Int? = null) {
    val trials: List<Trial> = order(pairs, seed)
    val threshold: Int = threshold ?: defaultThreshold(pairs.size)
    val correct get() = trials.count { it.correct }
    val pass get() = correct <= threshold

    fun answer(index: Int, tapped: Side, responseMs: Long) {
        trials[index].tapped = tapped
        trials[index].responseMs = responseMs
    }

    fun csv(): String = buildString {
        append("trial,pair,render_side,tapped_side,correct,response_ms\n")
        trials.forEachIndexed { i, t ->
            append("${i + 1},${t.pair.id},${t.renderSide.name.lowercase()},${t.tapped?.name?.lowercase().orEmpty()},${t.correct},${t.responseMs}\n")
        }
    }

    fun summary() = String.format(Locale.ROOT, "gpr result correct=%d/%d pass=%b threshold=%d seed=%d", correct, trials.size, pass, threshold, seed)

    companion object {
        private const val ALPHA = 0.025

        /** Largest k with P(X >= k + 1) <= 0.025 for X ~ Binomial(2N, 0.5); N = 10 gives 14. */
        fun defaultThreshold(pairCount: Int): Int {
            val n = 2 * pairCount
            val pmf = DoubleArray(n + 1)
            pmf[0] = Math.pow(0.5, n.toDouble())
            for (i in 1..n) pmf[i] = pmf[i - 1] * (n - i + 1) / i
            var tail = 0.0
            var k = n
            while (k > 0) {
                tail += pmf[k]
                if (tail > ALPHA) break
                k--
            }
            return k
        }

        private const val ATTEMPTS = 1000

        private fun order(pairs: List<GprPair>, seed: Long): List<Trial> {
            val all = pairs.flatMap { listOf(Trial(it, Side.LEFT), Trial(it, Side.RIGHT)) }
            val random = Random(seed)
            var shuffled = all.shuffled(random)
            repeat(ATTEMPTS) {
                if (shuffled.zipWithNext().none { (a, b) -> a.pair.id == b.pair.id }) return shuffled
                shuffled = all.shuffled(random)
            }
            return shuffled
        }
    }
}
