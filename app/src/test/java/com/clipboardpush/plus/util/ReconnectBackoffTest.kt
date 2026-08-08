package com.clipboardpush.plus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {

    // jitter = 0.0 removes the random component, making delays deterministic.

    @Test
    fun `first attempt uses base delay`() {
        assertEquals(ReconnectBackoff.BASE_DELAY_MS, ReconnectBackoff.delayForAttempt(0, jitter = 0.0))
    }

    @Test
    fun `delay doubles on each attempt`() {
        assertEquals(2_000L, ReconnectBackoff.delayForAttempt(0, jitter = 0.0))
        assertEquals(4_000L, ReconnectBackoff.delayForAttempt(1, jitter = 0.0))
        assertEquals(8_000L, ReconnectBackoff.delayForAttempt(2, jitter = 0.0))
        assertEquals(16_000L, ReconnectBackoff.delayForAttempt(3, jitter = 0.0))
        assertEquals(32_000L, ReconnectBackoff.delayForAttempt(4, jitter = 0.0))
    }

    @Test
    fun `delay is capped at max`() {
        // 2000 shl 5 == 64_000, which exceeds the 60s cap
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayForAttempt(5, jitter = 0.0))
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayForAttempt(6, jitter = 0.0))
    }

    @Test
    fun `very large attempt counts do not overflow`() {
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayForAttempt(1_000, jitter = 0.0))
        assertEquals(ReconnectBackoff.MAX_DELAY_MS, ReconnectBackoff.delayForAttempt(Int.MAX_VALUE, jitter = 0.0))
    }

    @Test
    fun `negative attempt is treated as first attempt`() {
        assertEquals(ReconnectBackoff.BASE_DELAY_MS, ReconnectBackoff.delayForAttempt(-5, jitter = 0.0))
    }

    @Test
    fun `jitter only shortens the delay so max is a real ceiling`() {
        // jitter = 1.0 subtracts the full jitter ratio
        val expected = (2_000L * (1.0 - ReconnectBackoff.JITTER_RATIO)).toLong()
        assertEquals(expected, ReconnectBackoff.delayForAttempt(0, jitter = 1.0))
    }

    @Test
    fun `delay never exceeds max nor drops below zero for any input`() {
        val jitters = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        for (attempt in -3..40) {
            for (jitter in jitters) {
                val delay = ReconnectBackoff.delayForAttempt(attempt, jitter)
                assertTrue(
                    "attempt=$attempt jitter=$jitter produced $delay",
                    delay in 0..ReconnectBackoff.MAX_DELAY_MS
                )
            }
        }
    }

    @Test
    fun `out of range jitter is clamped`() {
        assertEquals(2_000L, ReconnectBackoff.delayForAttempt(0, jitter = -1.0))
        val floor = (2_000L * (1.0 - ReconnectBackoff.JITTER_RATIO)).toLong()
        assertEquals(floor, ReconnectBackoff.delayForAttempt(0, jitter = 5.0))
    }
}
