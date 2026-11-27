package com.geniex.demo

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal class BenchmarkHistory(context: Context) {
    private val root = File(context.filesDir, "benchmark-history-v1")
    fun save(record: JSONObject) {
        check(root.isDirectory || root.mkdirs())
        val id = record.getLong("timestamp").toString() + "-" + java.util.UUID.randomUUID()
        val file = AtomicFile(File(root, id + ".json"))
        val output = file.startWrite()
        try {
            output.write(record.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
    fun records(): List<JSONObject> = root.listFiles()?.filter { it.extension == "json" }
        ?.sortedByDescending { it.name }?.mapNotNull { file ->
            runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
        }.orEmpty()
    fun comparison(records: List<JSONObject>): String {
        // Only compare the latest session per exact configuration; no mixing unlike runs.
        val seen = mutableSetOf<String>()
        val out = StringBuilder("Latest result per configuration (requested devices; actual placement unverified)\n\n")
        for (record in records) {
            val runs = record.optJSONArray("runs") ?: continue
            val groups = (0 until runs.length()).mapNotNull { runs.optJSONObject(it) }
                .groupBy { it.optString("device") }
            for ((device, rows) in groups) {
                val model = record.optString("model")
                val runtime = record.optString("runtime")
                val context = record.optString("context")
                val key = listOf(model, runtime, context, record.optString("quant"),
                    record.optString("sdk"), record.optString("protocol"), record.optString("soc"), device).joinToString("|")
                if (!seen.add(key)) continue
                val speeds = rows.map { it.optDouble("decode") }.filter { it.isFinite() && it > 0 }
                val tokens = rows.sumOf { it.optInt("tokens") }
                val seconds = rows.sumOf { it.optDouble("seconds", 0.0) }
                val rss = rows.map { it.optLong("pss", -1) }.filter { it >= 0 }
                val avg = if (speeds.isEmpty()) "n/a" else String.format(Locale.US, "%.2f", speeds.average())
                val sustained = if (seconds > 0) String.format(Locale.US, "%.2f", tokens / seconds) else "n/a"
                val mem = if (rss.isEmpty()) "n/a" else String.format(Locale.US, "%.0f", rss.average())
                out.append(model).append(" | ").append(runtime).append(" | ").append(context)
                    .append(" | ").append(device).append("\nSDK decode ").append(avg)
                    .append(" tok/s | end-to-end ").append(sustained).append(" tok/s | PSS ").append(mem)
                    .append(" MiB | runs ").append(rows.size).append("\n\n")
            }
        }
        return out.toString()
    }
}
