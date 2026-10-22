package com.saworks.pblock.core

/** Box breathing: in 4 s, hold 4 s, out 4 s, hold 4 s; 6 rounds (~1.5 minutes). */
object BoxBreathing {
    enum class Phase { IN, HOLD_FULL, OUT, HOLD_EMPTY }

    const val PHASE_MS = 4_000L
    const val ROUNDS = 6
    const val TOTAL_MS = PHASE_MS * 4 * ROUNDS

    /** @param index phase counter since the start; round is 1-based; secondsLeft counts 4..1 */
    data class State(val index: Int, val phase: Phase, val round: Int, val secondsLeft: Int)

    /** State at [elapsedMs] since the start, null when the exercise is over. */
    fun at(elapsedMs: Long): State? {
        if (elapsedMs < 0 || elapsedMs >= TOTAL_MS) return null
        val index = (elapsedMs / PHASE_MS).toInt()
        val inPhase = elapsedMs % PHASE_MS
        val secondsLeft = ((PHASE_MS - inPhase + 999) / 1000).toInt()
        return State(index, Phase.entries[index % 4], index / 4 + 1, secondsLeft)
    }
}
