package com.geniex.demo

import org.junit.Assert.*
import org.junit.Test

class EnergyWindowTest {
    private fun sample(ms: Long, watts: Double?) = EnergySample(ms, watts, 30.0, 0, false, 80)

    @Test fun constantPowerIntegratesCorrectly() {
        val w = EnergyWindow()
        for (i in 0..8) w.add(sample(i * 500L, 2.5))
        assertTrue(w.usable)
        assertEquals(10.0, w.estimatedJoules!!, 1e-9)
        assertEquals(2.5, w.averageWatts!!, 1e-9)
        assertTrue(w.report(20).contains("gross J/token: 0.500"))
    }

    @Test fun missingSamplesAreNotZeroPower() {
        val w = EnergyWindow()
        for (i in 0..8) w.add(sample(i * 500L, if (i % 2 == 0) 3.0 else null))
        assertFalse(w.usable)
        assertNull(w.estimatedJoules)
    }

    @Test fun longSamplingGapInvalidatesEstimate() {
        val w = EnergyWindow()
        for (i in 0..5) w.add(sample(i * 500L, 2.0))
        w.add(sample(9000, 2.0))
        assertFalse(w.usable)
    }
}
