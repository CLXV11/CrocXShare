package com.crocxshare.app.core.protocol

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persisted receiver-side resume state, rewritten after every completed block so
 * an interrupted transfer never re-downloads blocks it already has. [tempRef]
 * points at the sink's temporary storage (SAF uri or filesystem path) so a
 * receiver app restart can keep writing to the same temp file.
 */
class ResumeState(
    val sessionId: String,
    val fileIndex: Int,
    val relPath: String,
    val fileSize: Long,
    val blockSize: Long,
    val tempRef: String?,
    val bitmap: BlockBitmap
)

object ResumeStore {
    private const val MAGIC = 0x43585352 // "CXSR"
    private const val VERSION = 1

    fun stateFile(dir: File, sessionId: String, fileIndex: Int): File =
        File(dir, "resume-" + sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + "-$fileIndex.bin")

    @Synchronized
    fun save(dir: File, state: ResumeState) {
        dir.mkdirs()
        val json = JSONObject().apply {
            put("sessionId", state.sessionId)
            put("fileIndex", state.fileIndex)
            put("relPath", state.relPath)
            put("fileSize", state.fileSize)
            put("blockSize", state.blockSize)
            put("tempRef", state.tempRef)
        }.toString().toByteArray(Charsets.UTF_8)
        val target = stateFile(dir, state.sessionId, state.fileIndex)
        val tmp = File(dir, ".tmp-" + System.nanoTime())
        DataOutputStream(FileOutputStream(tmp)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(json.size)
            out.write(json)
            out.write(state.bitmap.copyBytes())
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("cannot persist resume state") }
        }
    }

    fun load(dir: File, sessionId: String, fileIndex: Int): ResumeState? {
        val f = stateFile(dir, sessionId, fileIndex)
        if (!f.isFile) return null
        return try {
            DataInputStream(FileInputStream(f)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                val jsonLen = input.readInt()
                if (jsonLen < 0 || jsonLen > 1 shl 20) return null
                val jsonBytes = ByteArray(jsonLen)
                input.readFully(jsonBytes)
                val j = JSONObject(String(jsonBytes, Charsets.UTF_8))
                val fileSize = j.getLong("fileSize")
                val blockSize = j.getLong("blockSize")
                val blockCount = Chunker.blockCountChecked(fileSize, blockSize)
                val bitmapBytes = ByteArray((blockCount + 7) / 8)
                input.readFully(bitmapBytes)
                val tempRef = if (j.isNull("tempRef")) null else j.optString("tempRef", null)
                ResumeState(
                    sessionId = j.getString("sessionId"),
                    fileIndex = j.getInt("fileIndex"),
                    relPath = j.getString("relPath"),
                    fileSize = fileSize,
                    blockSize = blockSize,
                    tempRef = tempRef,
                    bitmap = BlockBitmap.fromBytes(blockCount, bitmapBytes)
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun delete(dir: File, sessionId: String, fileIndex: Int) {
        stateFile(dir, sessionId, fileIndex).delete()
    }
}
