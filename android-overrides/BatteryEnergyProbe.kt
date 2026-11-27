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
    val thermal: Int, val plugged: Boolean, val percent: Int)

internal class BatteryEnergyProbe(context: Context) {
    private val app = context.applicationContext
    private val battery = app.getSystemService(BatteryManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    fun sample(): EnergySample {
        val status = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val uv = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val mv = status?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val temp = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val watts = if (!plugged && uv in -15_000_000..-1 && mv in 2500..10000)
            -uv.toDouble() * mv / 1e9 else null
        return EnergySample(SystemClock.elapsedRealtime(), watts,
            temp.takeIf { it in 0..800 }?.div(10.0), power.currentThermalStatus, plugged, percent)
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
    fun add(s: EnergySample) {
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
    val usable get() = validCount >= 5 && validMs >= 2000 && coverage >= 0.9
    val averageWatts: Double? get() = if (usable) joules / (validMs / 1000.0) else null
    // Scale the observed mean to the window; at most 10% of time can be missing.
    val estimatedJoules: Double? get() = averageWatts?.times(seconds)
    fun report(tokens: Int = 0): String {
        fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: "unavailable"
        return "Duration: " + fmt(seconds) + " s | valid coverage: " + fmt(coverage * 100) +
            "% | samples: " + validCount + "\nGross device W: " + fmt(averageWatts) +
            " | estimated J: " + fmt(estimatedJoules) +
            (if (tokens > 0) " | gross J/token: " + fmt(estimatedJoules?.div(tokens)) else "") +
            "\nBattery peak C: " + fmt(peakTemp) + " | max thermal status: " + thermal
    }
}
