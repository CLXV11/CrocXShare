package com.crocxshare.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

/**
 * Where received data lands. Implementations write to hidden temp storage and
 * finalize atomically (rename) only after the engine verifies integrity.
 */
interface FileSink {
    val finalName: String
    fun writeAt(position: Long, buf: ByteArray, off: Int, len: Int)
    /** Random-access read of previously written temp data (resume digest seeding). */
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int
    /** Opaque reference to temp storage, persisted in resume state. */
    fun tempRef(): String?
    /** Move temp storage to the final destination. Returns a human-readable location. */
    fun commit(): String
    /** Delete temp storage without producing a final file. */
    fun discard()
}

interface SinkFactory {
    fun createSink(relPath: String, index: Int, size: Long): FileSink

    /** Recreate a sink over temp storage from a previous session. Default: start fresh. */
    fun resumeSink(relPath: String, index: Int, size: Long, tempRef: String?): FileSink =
        if (tempRef == null) createSink(relPath, index, size)
        else throw java.io.IOException("sink does not support temp resume")
}

class FileExistsException(name: String) : java.io.IOException("file already exists: $name")

/** Received files land inside a user-picked SAF tree — no broad storage permission. */
class SafSinkFactory(
    private val context: Context,
    private val treeUri: Uri,
    private val autoRename: Boolean = true
) : SinkFactory {
    override fun createSink(relPath: String, index: Int, size: Long): FileSink =
        SafFileSink(context, treeUri, relPath, null, autoRename)

    override fun resumeSink(relPath: String, index: Int, size: Long, tempRef: String?): FileSink =
        if (tempRef == null) createSink(relPath, index, size)
        else SafFileSink(context, treeUri, relPath, Uri.parse(tempRef), autoRename)
}

class SafFileSink : FileSink {

    private lateinit var context: Context
    private lateinit var dir: DocumentFile
    private lateinit var tempDoc: DocumentFile
    private lateinit var pfd: android.os.ParcelFileDescriptor
    private lateinit var channel: FileChannel
    private var autoRename: Boolean = false
    private var closed = false

    override lateinit var finalName: String

    /** Fresh temp storage. */
    constructor(context: Context, treeUri: Uri, relPath: String, autoRename: Boolean) :
        this(context, treeUri, relPath, null, autoRename)

    /** [tempUri] != null resumes over an existing temp document. */
    constructor(context: Context, treeUri: Uri, relPath: String, tempUri: Uri?, autoRename: Boolean) {
        this.context = context
        this.autoRename = autoRename
        val safe = com.crocxshare.app.core.protocol.PathsGuard.sanitize(relPath)
            ?: throw java.io.IOException("unsafe path rejected: $relPath")
        var d = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw java.io.IOException("destination folder unavailable")
        val parts = safe.split('/')
        for (comp in parts.dropLast(1)) {
            d = d.findFile(comp)?.takeIf { it.isDirectory } ?: d.createDirectory(comp)
                ?: throw java.io.IOException("cannot create folder: $comp")
        }
        dir = d
        finalName = safe.substringAfterLast('/')
        tempDoc = if (tempUri != null) {
            val doc = DocumentFile.fromSingleUri(context, tempUri)
            if (doc == null || !doc.exists()) throw java.io.IOException("temp file lost; cannot resume")
            doc
        } else {
            d.createFile("application/octet-stream", ".cxstmp-" + Random.nextInt(1 shl 30))
                ?: throw java.io.IOException("cannot create temp file in destination")
        }
        pfd = context.contentResolver.openFileDescriptor(tempDoc.uri, "rw")
            ?: throw java.io.IOException("destination not writable")
        channel = FileOutputStream(pfd.fileDescriptor).channel
    }

    @Synchronized
    override fun writeAt(position: Long, buf: ByteArray, off: Int, len: Int) {
        check(!closed)
        var p = position; var o = off; var remaining = len
        while (remaining > 0) {
            val n = channel.write(ByteBuffer.wrap(buf, o, remaining), p)
            if (n < 0) throw java.io.IOException("short write at $position")
            p += n; o += n; remaining -= n
        }
    }

    @Synchronized
    override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        check(!closed)
        return channel.read(ByteBuffer.wrap(buf, off, len), position)
    }

    override fun tempRef(): String = tempDoc.uri.toString()

    @Synchronized
    override fun commit(): String {
        if (closed) return finalName
        closed = true
        try { channel.close() } catch (ignored: Exception) {}
        try { pfd.close() } catch (ignored: Exception) {}
        var target = finalName
        if (dir.findFile(target) != null) {
            if (!autoRename) { discardQuietly(); throw FileExistsException(target) }
            val dot = finalName.lastIndexOf('.')
            val base = if (dot > 0) finalName.substring(0, dot) else finalName
            val ext = if (dot > 0) finalName.substring(dot) else ""
            var n = 2
            while (dir.findFile("$base ($n)$ext") != null) n++
            target = "$base ($n)$ext"
        }
        return try {
            val newUri = DocumentsContract.renameDocument(context.contentResolver, tempDoc.uri, target)
            if (newUri != null) target else copyFallback(target)
        } catch (e: Exception) {
            copyFallback(target)
        }
    }

    private fun copyFallback(target: String): String {
        val finalDoc = dir.createFile("application/octet-stream", target)
            ?: throw java.io.IOException("cannot finalize $target")
        context.contentResolver.openOutputStream(finalDoc.uri, "wt").use { outStream ->
            context.contentResolver.openInputStream(tempDoc.uri).use { inStream ->
                inStream ?: throw java.io.IOException("temp file lost")
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = inStream.read(buf)
                    if (n < 0) break
                    outStream!!.write(buf, 0, n)
                }
            }
        }
        discardQuietly()
        return target
    }

    private fun discardQuietly() {
        try { DocumentsContract.deleteDocument(context.contentResolver, tempDoc.uri) } catch (ignored: Exception) {}
    }

    @Synchronized
    override fun discard() {
        if (closed) return
        closed = true
        try { channel.close() } catch (ignored: Exception) {}
        try { pfd.close() } catch (ignored: Exception) {}
        discardQuietly()
    }
}

/** Plain filesystem sink (benchmark, tests, app-private transfers). */
class FsSinkFactory(private val baseDir: File, private val autoRename: Boolean = true) : SinkFactory {
    override fun createSink(relPath: String, index: Int, size: Long): FileSink =
        FsFileSink(baseDir, relPath, null, autoRename)

    override fun resumeSink(relPath: String, index: Int, size: Long, tempRef: String?): FileSink =
        if (tempRef == null) createSink(relPath, index, size)
        else FsFileSink(baseDir, relPath, File(tempRef), autoRename)
}

class FsFileSink : FileSink {

    private lateinit var finalFile: File
    private lateinit var tempFile: File
    private lateinit var raf: RandomAccessFile
    private var autoRename: Boolean = false

    override val finalName: String get() = finalFile.name

    constructor(baseDir: File, relPath: String, autoRename: Boolean) :
        this(baseDir, relPath, null, autoRename)

    constructor(baseDir: File, relPath: String, existingTemp: File?, autoRename: Boolean) {
        this.autoRename = autoRename
        val safe = com.crocxshare.app.core.protocol.PathsGuard.sanitize(relPath)
            ?: throw java.io.IOException("unsafe path: $relPath")
        finalFile = File(baseDir, safe)
        val parent = finalFile.parentFile ?: throw java.io.IOException("bad path")
        if (!parent.exists() && !parent.mkdirs()) throw java.io.IOException("cannot create folders")
        tempFile = if (existingTemp != null) {
            if (!existingTemp.isFile) throw java.io.IOException("temp file lost; cannot resume")
            existingTemp
        } else {
            File(parent, ".cxstmp-" + Random.nextInt(1 shl 30))
        }
        raf = RandomAccessFile(tempFile, "rw")
    }

    @Synchronized
    override fun writeAt(position: Long, buf: ByteArray, off: Int, len: Int) {
        raf.seek(position)
        raf.write(buf, off, len)
    }

    @Synchronized
    override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        raf.seek(position)
        return raf.read(buf, off, len)
    }

    override fun tempRef(): String = tempFile.absolutePath

    @Synchronized
    override fun commit(): String {
        raf.close()
        var target = finalFile
        if (target.exists()) {
            if (!autoRename) { tempFile.delete(); throw FileExistsException(target.name) }
            val name = target.name
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var n = 2
            target = File(target.parentFile, "$base ($n)$ext")
            while (target.exists()) { n++; target = File(target.parentFile, "$base ($n)$ext") }
        }
        try {
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tempFile.toPath(), target.toPath())
        }
        return target.absolutePath
    }

    @Synchronized
    override fun discard() {
        try { raf.close() } catch (ignored: Exception) {}
        tempFile.delete()
    }
}
