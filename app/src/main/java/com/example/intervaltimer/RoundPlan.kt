package com.example.intervaltimer

/**
 * The rounds of one run, each with its own length (plain Kotlin, unit-tested).
 * Everything is measured from the start of the run, so an alarm never shifts the clock.
 */
class RoundPlan(lengths: List<Long>) {

    /** Length of each round, ms (at least one round). */
    val lengths: List<Long> = lengths.ifEmpty { listOf(3 * 60_000L) }

    val rounds get() = lengths.size

    /** ends[i] = time from the start at which round i+1 ends. */
    private val ends: LongArray = LongArray(this.lengths.size).also { arr ->
        var sum = 0L
        this.lengths.forEachIndexed { i, l -> sum += l; arr[i] = sum }
    }

    val totalMs get() = ends.last()

    /** True when every round has the same length. */
    val uniform get() = lengths.all { it == lengths[0] }

    /** How many rounds have finished at [elapsed]. */
    fun done(elapsed: Long): Int {
        // binary search: number of ends <= elapsed
        var lo = 0; var hi = ends.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (ends[mid] <= elapsed) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 1-based round counting down at [elapsed]. */
    fun current(elapsed: Long): Int = (done(elapsed) + 1).coerceAtMost(rounds)

    /** Time from the start at which round [k] (1-based) ends. */
    fun endOf(k: Int): Long = ends[(k - 1).coerceIn(0, ends.size - 1)]

    /** Length of round [k] (1-based). */
    fun lengthOf(k: Int): Long = lengths[(k - 1).coerceIn(0, lengths.size - 1)]

    /** Time left in the current round. */
    fun remaining(elapsed: Long): Long =
        if (elapsed >= totalMs) 0L else endOf(current(elapsed)) - elapsed

    /** Progress in "rounds" units, e.g. 2.5 = halfway through round 3 (for the progress bar). */
    fun progress(elapsed: Long): Float {
        if (elapsed >= totalMs) return rounds.toFloat()
        val k = current(elapsed)
        val startOfK = endOf(k) - lengthOf(k)
        return (k - 1) + (elapsed - startOfK).toFloat() / lengthOf(k)
    }

    /** Stored as "180000,120000,60000". */
    fun encode(): String = lengths.joinToString(",")

    companion object {
        fun decode(s: String?): List<Long> =
            s.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
    }
}
