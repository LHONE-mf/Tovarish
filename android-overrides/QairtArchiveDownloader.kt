package com.geniex.demo

import com.geniex.demo.bean.ModelData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Downloads the exact chipset archive before importing it into the native cache. */
internal object QairtArchiveDownloader {
    private const val BASE = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/releases/v0.60.0"
    private const val BUFFER = 256 * 1024
    private const val MAX_INDEX = 8 * 1024 * 1024
    internal data class Asset(val url: String, val expandedBytes: Long)
    private fun connect(address: String): HttpURLConnection {
        val url = URL(address)
        require(url.protocol == "https") { "HTTPS required" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept-Encoding", "identity")
        }
    }
    private fun json(address: String): JSONObject {
        val c = connect(address)
        try {
            check(c.responseCode == 200) { "Catalog HTTP " + c.responseCode }
            val output = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(output.size() + n <= MAX_INDEX) { "Catalog too large" }
                    output.write(buffer, 0, n)
                }
            }
            return JSONObject(output.toString("UTF-8"))
        } finally { c.disconnect() }
    }
    private fun resolve(model: ModelData): Asset {
        val chip = requireNotNull(model.chipset)
        val manifest = json(BASE + "/manifest.json")
        val platform = json(BASE + "/platform.json")
        val chips = platform.getJSONArray("chipsets")
        var canonical: String? = null
        for (i in 0 until chips.length()) {
            val c = chips.getJSONObject(i)
            val aliases = c.optJSONArray("aliases")
            val names = mutableListOf(c.optString("name"), c.optString("reference_device"))
            if (aliases != null) for (j in 0 until aliases.length()) names.add(aliases.getString(j))
            if (names.any { it.equals(chip, true) }) {
                canonical = c.getString("name")
                break
            }
        }
        check(canonical != null) { "No catalog chipset: " + chip }
        val models = manifest.getJSONArray("models")
        val name = model.aiHubDisplayName ?: model.id
        var entry: JSONObject? = null
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            if (m.optString("display_name") == name || m.optString("id") == name) { entry = m; break }
        }
        val selected = requireNotNull(entry) { "Model not published in pinned AI Hub release" }
        val release = json(selected.getJSONObject("manifest_urls").getString("release_assets"))
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("chipset") == canonical &&
                it.optString("runtime") in setOf("RUNTIME_GENIEX_QAIRT", "RUNTIME_GENIE")
        }.sortedWith(compareBy<JSONObject> { if (it.optString("runtime") == "RUNTIME_GENIEX_QAIRT") 0 else 1 }
            .thenBy { it.optString("precision") })
        val asset = matches.firstOrNull() ?: error("No QAIRT archive published for " + chip)
        return Asset(asset.getString("download_url"), asset.optLong("uncompressed_size", 0L))
    }

    internal fun rangeTotal(header: String?, offset: Long): Long {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(header.orEmpty())
            ?: throw IOException("Invalid Content-Range")
        val (start, end, total) = match.destructured
        val n = total.toLong()
        check(start.toLong() == offset && end.toLong() == n - 1 && n > offset) { "Unexpected byte range" }
        return n
    }
    internal fun strongTag(tag: String?): String? =
        tag?.takeIf { it.startsWith("\"") && it.endsWith("\"") && it.length > 2 }

    private fun checkpoint(file: File, data: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(data.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(tmp.renameTo(file)) { "Cannot save download checkpoint" }
    }

    /** Separate staging directory per release/model/chip. Never credits preallocated bytes. */
    suspend fun prepare(root: File, model: ModelData, report: suspend (Long, Long?, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val directory = File(root, "v060-" + model.id + "-" + model.chipset)
            check(directory.exists() || directory.mkdirs())
            val archive = File(directory, "model.zip")
            val meta = File(directory, "download.json")
            val extracted = File(directory, "extracted")
            val identity = model.id + ":" + model.chipset + ":v0.60.0"
            val saved = if (meta.isFile) JSONObject(meta.readText()) else null
            val asset = if (saved != null) {
                check(saved.getString("identity") == identity) { "Archive identity mismatch" }
                Asset(saved.getString("url"), saved.optLong("expanded", 0))
            } else resolve(model)
            var offset = archive.length()
            var total = saved?.optLong("total", -1L) ?: -1L
            val oldTag = strongTag(saved?.optString("etag"))
            check(offset == 0L || (saved != null && oldTag != null && total >= offset)) {
                "Partial archive lacks a valid checkpoint; remove the partial archive before restarting."
            }
            if (offset == 0L || offset != total) {
                report(offset, total.takeIf { it > 0 }, "Downloading archive")
                val c = connect(asset.url)
                try {
                    c.setRequestProperty("Range", "bytes=" + offset + "-")
                    if (offset > 0L) c.setRequestProperty("If-Range", oldTag!!)
                    val status = c.responseCode
                    val tag = strongTag(c.getHeaderField("ETag"))
                    if (offset > 0L) {
                        check(status == 206 && tag == oldTag) { "Server refused safe resume (HTTP " + status + "); partial archive retained." }
                        check(rangeTotal(c.getHeaderField("Content-Range"), offset) == total) { "Archive size changed" }
                    } else {
                        total = when (status) {
                            206 -> rangeTotal(c.getHeaderField("Content-Range"), 0L)
                            200 -> c.contentLengthLong
                            else -> throw IOException("Archive HTTP " + status)
                        }
                        check(total > 0 && tag != null) { "Server did not provide a verifiable archive length and ETag" }
                        checkpoint(meta, JSONObject().put("identity", identity).put("url", asset.url)
                            .put("expanded", asset.expandedBytes).put("total", total).put("etag", tag))
                    }
                    check(directory.usableSpace >= total - offset) { "Not enough storage for remaining archive" }
                    FileOutputStream(archive, true).use { output ->
                        try {
                            c.inputStream.use { input ->
                                val buffer = ByteArray(BUFFER)
                                var nextReport = 0L
                                while (offset < total) {
                                    ensureActive()
                                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), total - offset).toInt())
                                    if (n < 0) throw IOException("Connection ended early; partial archive saved")
                                    output.write(buffer, 0, n)
                                    offset += n
                                    val now = System.nanoTime()
                                    if (now >= nextReport) {
                                        report(offset, total, "Downloading archive")
                                        nextReport = now + 500_000_000L
                                    }
                                }
                            }
                        } finally { output.fd.sync() }
                    }
                } finally { c.disconnect() }
            }
            check(archive.length() == total && total > 0) { "Incomplete archive" }
            report(total, total, "Verifying archive")
            // Stream DEFLATE to disk, validate each entry's CRC and length, and reject duplicate names.
            // Keep the archive until native import is confirmed, so interrupted unpacking needs no network.
            if (extracted.exists()) check(extracted.deleteRecursively())
            check(extracted.mkdirs())
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toList().filter { !it.isDirectory &&
                    !it.name.replace('\\', '/').split('/').contains("__MACOSX") &&
                    !it.name.replace('\\', '/').substringAfterLast('/').startsWith("._") }
                val names = entries.map { it.name.replace('\\', '/').substringAfterLast('/') }
                check(names.all { it.isNotBlank() && it != "." && it != ".." } && names.toSet().size == names.size) { "Unsafe or duplicate archive entry" }
                check("metadata.json" in names && names.any { it.endsWith(".bin") }) { "Not a QAIRT bundle" }
                check(entries.all { it.size >= 0 && it.crc >= 0 }) { "Archive entry missing integrity metadata" }
                val expanded = entries.fold(0L) { acc, e -> Math.addExact(acc, e.size) }
                check(expanded > 0 && directory.usableSpace / 2 >= expanded) { "Need free space for extraction and SDK import" }
                var written = 0L
                for ((index, entry) in entries.withIndex()) {
                    ensureActive()
                    val crc = CRC32()
                    var length = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(File(extracted, names[index])).use { output ->
                            val buffer = ByteArray(BUFFER)
                            var nextReport = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                length += n
                                check(length <= entry.size) { "Archive entry exceeds declared length" }
                                output.write(buffer, 0, n)
                                crc.update(buffer, 0, n)
                                written += n
                                val now = System.nanoTime()
                                if (now >= nextReport) {
                                    report(written, expanded, "Verifying and extracting archive")
                                    nextReport = now + 500_000_000L
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    check(length == entry.size && crc.value == entry.crc) { "ZIP integrity check failed: " + names[index] }
                }
            }
            extracted
        }

    fun cleanup(root: File, model: ModelData) {
        File(root, "v060-" + model.id + "-" + model.chipset).deleteRecursively()
    }
}
