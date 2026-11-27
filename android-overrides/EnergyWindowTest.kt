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
    @Test fun signedCurrentAndUnsupportedValues() {
        assertEquals(4.0, batteryPower(-1_000_000, 4000, true)!!, 1e-9)
        assertEquals(4.0, batteryPower(1_000_000, 4000, true)!!, 1e-9)
        assertNull(batteryPower(Int.MIN_VALUE, 4000, true))
        assertNull(batteryPower(0, 4000, true))
        assertNull(batteryPower(1_000_000, 4000, false))
        assertNull(batteryPower(1_000_000, -1, true))
    }

    @Test fun mixedSignsRejectIntegration() {
        val w = EnergyWindow()
        for (i in 0..8) w.add(sample(i * 500L, 4.0).copy(
            currentUa = if (i % 2 == 0) -1_000_000 else 1_000_000, discharging = true))
        assertNull(w.estimatedJoules)
    }

    @Test fun countersRemainSeparateFromInstantaneousPower() {
        val w = EnergyWindow()
        w.add(sample(0, null).copy(chargeUah = 10000, energyNwh = 40000000, voltageMv = 4000, discharging = true))
        w.add(sample(8000, null).copy(chargeUah = 9000, energyNwh = 36000000, voltageMv = 4000, discharging = true))
        assertNull(w.estimatedJoules)
        assertTrue(w.report().contains("energy J 14.400 | charge x voltage J 14.400"))
    }
}
