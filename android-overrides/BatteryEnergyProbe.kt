package com.geniex.demo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import java.util.Locale

// Gross device power, not accelerator rail power. Invalid readings never become zero watts.
internal data class EnergySample(val ms: Long, val watts: Double?, val celsius: Double?,
    val thermal: Int, val plugged: Boolean, val percent: Int,
    val currentUa: Int? = null, val averageUa: Int? = null, val voltageMv: Int? = null,
    val chargeUah: Int? = null, val energyNwh: Long? = null, val discharging: Boolean = false)

// Some vendors expose positive current during discharge. Keep the magnitude estimate
// separate from spec-compliant negative current and require a discharging status.
internal fun batteryPower(currentUa: Int, voltageMv: Int, discharging: Boolean): Double? {
    if (!discharging || voltageMv !in 2500..10000) return null
    val magnitude = kotlin.math.abs(currentUa.toLong())
    if (magnitude !in 1L..15_000_000L) return null
    return magnitude.toDouble() * voltageMv / 1e9
}

internal class BatteryEnergyProbe(context: Context) {
    private val app = context.applicationContext
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    fun sample(): EnergySample {
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val uv = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val avg = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val charge = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energy = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        val mv = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val discharging = !plugged && status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_DISCHARGING
        val watts = batteryPower(uv, mv, discharging)
        return EnergySample(SystemClock.elapsedRealtime(), watts,
            temp.takeIf { it in 0..800 }?.div(10.0), power.currentThermalStatus, plugged, percent,
            uv, avg, mv, charge, energy, discharging)
    }
    fun stopReason(s: EnergySample): String? = when {
        s.plugged -> "Disconnect charging before testing"
        s.percent !in 20..100 -> "Battery level below 20% or unavailable"
        s.celsius == null -> "Battery temperature unavailable"
        s.celsius >= 40.0 -> "Battery temperature reached the conservative 40 C stop limit"
        s.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> "Android reports moderate or higher thermal stress"
        else -> null
    }
}

internal class EnergyWindow {
    private var last: EnergySample? = null
    private var joules = 0.0
    private var validMs = 0L
    private var totalMs = 0L
    private var validCount = 0
    private var peakTemp: Double? = null
    private var thermal = 0
    private var first: EnergySample? = null
    private var minUa: Int? = null
    private var maxUa: Int? = null
    private var minMv: Int? = null
    private var maxMv: Int? = null
    private var positive = 0
    private var negative = 0
    private var invalidCurrent = 0
    private var dischargeCount = 0
    private var count = 0
    fun add(s: EnergySample) {
        if (first == null) first = s
        count++
        if (s.discharging) dischargeCount++
        s.currentUa?.let {
            minUa = minOf(minUa ?: it, it)
            maxUa = maxOf(maxUa ?: it, it)
            when {
                it == Int.MIN_VALUE || it == 0 -> invalidCurrent++
                it > 0 -> positive++
                else -> negative++
            }
        }
        s.voltageMv?.let {
            minMv = minOf(minMv ?: it, it)
            maxMv = maxOf(maxMv ?: it, it)
        }
        s.celsius?.let { peakTemp = maxOf(peakTemp ?: it, it) }
        thermal = maxOf(thermal, s.thermal)
        if (s.watts != null) validCount++
        last?.let { p ->
            val dt = (s.ms - p.ms).coerceAtLeast(0)
            totalMs += dt
            if (dt in 1..2000 && p.watts != null && s.watts != null) {
                joules += (p.watts + s.watts) * 0.5 * dt / 1000.0
                validMs += dt
            }
        }
        last = s
    }
    val seconds get() = totalMs / 1000.0
    val coverage get() = if (totalMs > 0) validMs.toDouble() / totalMs else 0.0
    val usable get() = validCount >= 5 && validMs >= 2000 && coverage >= 0.9 && !(positive > 0 && negative > 0)
    val averageWatts: Double? get() = if (usable) joules / (validMs / 1000.0) else null
    // Scale the observed mean to the window; at most 10% of time can be missing.
    val estimatedJoules: Double? get() = averageWatts?.times(seconds)
    fun report(tokens: Int = 0): String {
        fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: "unavailable"
        val start = first
        val end = last
        val energyDelta = if (start?.energyNwh != null && end?.energyNwh != null &&
            start.energyNwh > 0 && end.energyNwh > 0 && start.energyNwh > end.energyNwh &&
            dischargeCount == count) (start.energyNwh - end.energyNwh) * 0.0000036 else null
        val chargeDelta = if (start?.chargeUah != null && end?.chargeUah != null &&
            start.chargeUah > 0 && end.chargeUah > 0 && start.chargeUah > end.chargeUah &&
            start.voltageMv != null && end.voltageMv != null &&
            start.voltageMv in 2500..10000 && end.voltageMv in 2500..10000 &&
            dischargeCount == count) (start.chargeUah - end.chargeUah).toDouble() *
            (start.voltageMv + end.voltageMv) * 1.8e-6 else null
        val sign = when {
            positive > 0 && negative > 0 -> "mixed; integration rejected"
            positive > 0 -> "positive discharge (vendor sign, unverified magnitude)"
            negative > 0 -> "negative discharge (Android convention)"
            else -> "no nonzero current"
        }
        return "Raw current uA: " + minUa + ".." + maxUa + " | average endpoint uA: " + end?.averageUa +
            " | voltage mV: " + minMv + ".." + maxMv + " | sign: " + sign +
            " | zero/unsupported: " + invalidCurrent + " | discharging status: " + dischargeCount + "/" + count +
            "\nCharge counter uAh: " + start?.chargeUah + " -> " + end?.chargeUah +
            " | energy counter nWh: " + start?.energyNwh + " -> " + end?.energyNwh +
            "\nCounter estimates (coarse; not substitutes for integration): energy J " + fmt(energyDelta) +
            " | charge x voltage J " + fmt(chargeDelta) + "\n" +
            "Duration: " + fmt(seconds) + " s | valid coverage: " + fmt(coverage * 100) +
            "% | samples: " + validCount + "\nGross device W: " + fmt(averageWatts) +
            " | estimated J: " + fmt(estimatedJoules) +
            (if (tokens > 0) " | gross J/token: " + fmt(estimatedJoules?.div(tokens)) else "") +
            "\nBattery peak C: " + fmt(peakTemp) + " | max thermal status: " + thermal
    }
}
