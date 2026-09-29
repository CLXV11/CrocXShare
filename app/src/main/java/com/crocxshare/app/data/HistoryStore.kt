package com.crocxshare.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Local JSON-lines history. Capped at 500 entries, clearable, never leaves the device. */
class HistoryStore(context: Context) {

    data class TransferRecord(
        val id: Long,
        val fileName: String,
        val sizeBytes: Long,
        val direction: String, // SEND | RECEIVE
        val peerName: String,
        val timestamp: Long,
        val durationMs: Long,
        val avgSpeedBps: Double,
        val result: String // SUCCESS | FAILED | CANCELLED
    )

    private val file = File(context.filesDir, "history.jsonl")
    private val maxEntries = 500

    @Synchronized
    fun add(record: TransferRecord) {
        file.appendText(toJson(record).toString() + "\n")
        trim()
    }

    fun all(): List<TransferRecord> {
        if (!file.isFile) return emptyList()
        val out = ArrayList<TransferRecord>()
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                out.add(fromJson(JSONObject(line)))
            } catch (ignored: Exception) {}
        }
        return out.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun remove(id: Long) {
        if (!file.isFile) return
        file.writeText(file.readLines().filter { line ->
            runCatching { JSONObject(line).getLong("id") != id }.getOrDefault(true)
        }.joinToString("\n") + "\n")
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun trim() {
        val lines = file.readLines()
        if (lines.size > maxEntries) {
            file.writeText(lines.takeLast(maxEntries).joinToString("\n") + "\n")
        }
    }

    private fun toJson(r: TransferRecord) = JSONObject().apply {
        put("id", r.id)
        put("name", r.fileName)
        put("size", r.sizeBytes)
        put("dir", r.direction)
        put("peer", r.peerName)
        put("ts", r.timestamp)
        put("dur", r.durationMs)
        put("speed", r.avgSpeedBps)
        put("result", r.result)
    }

    private fun fromJson(j: JSONObject) = TransferRecord(
        id = j.getLong("id"),
        fileName = j.getString("name"),
        sizeBytes = j.getLong("size"),
        direction = j.getString("dir"),
        peerName = j.optString("peer", "?"),
        timestamp = j.getLong("ts"),
        durationMs = j.getLong("dur"),
        avgSpeedBps = j.optDouble("speed", 0.0),
        result = j.optString("result", "SUCCESS")
    )

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        fun formatSpeed(bps: Double): String = when {
            bps >= 1048576 -> String.format(Locale.US, "%.1f MB/s", bps / 1048576)
            bps >= 1024 -> String.format(Locale.US, "%.0f KB/s", bps / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bps)
        }

        fun formatTime(ms: Long): String {
            val s = ms / 1000
            return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        }
    }
}
