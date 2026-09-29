package com.crocxshare.app.core.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class FileEntry(val relPath: String, val size: Long, val lastModified: Long)

data class TransferManifest(val rootName: String, val files: List<FileEntry>) {
    val totalSize: Long get() = files.fold(0L) { acc, f -> acc + f.size }
    val fileCount: Int get() = files.size
}

/**
 * Untrusted-path validation. Rejects traversal, absolute paths, control
 * characters, over-long components and names unsafe on common filesystems.
 * Returns the normalized path (forward slashes) or null if unsafe.
 */
object PathsGuard {
    private val ILLEGAL = Regex("[\\x00-\\x1f]")

    fun sanitize(rel: String): String? {
        if (rel.isEmpty() || rel.length > 4096) return null
        val unified = rel.replace('\\', '/')
        if (unified.startsWith("/")) return null
        val parts = unified.split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return null
            if (part.length > 255) return null
            if (part.endsWith(".") || part.endsWith(" ")) return null
            if (ILLEGAL.containsMatchIn(part)) return null
            // Windows-reserved device names protect cross-platform SD cards.
            val upper = part.substringBefore('.').uppercase()
            if (upper in setOf("CON", "PRN", "AUX", "NUL") ||
                Regex("^(COM|LPT)[1-9]$").matches(upper)) return null
            out.add(part)
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }
}

object ManifestCodec {
    fun encode(m: TransferManifest): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(FrameCode.MAGIC)
            out.writeInt(FrameCode.PROTOCOL_VERSION)
            out.writeUTF(m.rootName)
            out.writeInt(m.files.size)
            for (f in m.files) {
                out.writeUTF(f.relPath)
                out.writeLong(f.size)
                out.writeLong(f.lastModified)
            }
        }
        return bos.toByteArray()
    }

    /** Decodes and validates. Throws ProtocolException on malformed data. */
    fun decode(data: ByteArray): TransferManifest {
        val input = DataInputStream(ByteArrayInputStream(data))
        if (input.readInt() != FrameCode.MAGIC) throw ProtocolException("bad manifest magic")
        if (input.readInt() != FrameCode.PROTOCOL_VERSION) throw ProtocolException("unsupported protocol version")
        val root = input.readUTF()
        val count = input.readInt()
        if (count < 0 || count > 100_000) throw ProtocolException("bad file count $count")
        val files = ArrayList<FileEntry>(count)
        var total = 0L
        repeat(count) {
            val rawPath = input.readUTF()
            val size = input.readLong()
            val mtime = input.readLong()
            if (size < 0) throw ProtocolException("negative file size")
            total += size
            if (total < 0) throw ProtocolException("manifest size overflow")
            val clean = PathsGuard.sanitize(rawPath)
                ?: throw ProtocolException("unsafe path in manifest: $rawPath")
            files.add(FileEntry(clean, size, mtime))
        }
        return TransferManifest(root, files)
    }
}
