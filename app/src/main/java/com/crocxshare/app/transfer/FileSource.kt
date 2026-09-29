package com.crocxshare.app.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Streaming source for one outgoing file. Never loads the file into memory. */
interface FileSource {
    val displayName: String
    val sizeBytes: Long
    fun openStream(): InputStream
    /** Seekable stream if the backing store supports it (needed for block resume). */
    fun openSeekable(): InputStream? = null
}

class SafFileSource(private val context: Context, val uri: Uri, override val displayName: String, override val sizeBytes: Long) : FileSource {
    override fun openStream(): InputStream = context.contentResolver.openInputStream(uri)
        ?: throw java.io.IOException("cannot open " + uri)

    override fun openSeekable(): InputStream? {
        return try {
            val pfd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: return null
            object : InputStream() {
                private val stream = pfd.createInputStream()
                override fun read(): Int = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
                override fun available(): Int = stream.available()
                override fun close() { try { stream.close() } finally { pfd.close() } }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun query(context: Context, uri: Uri): SafFileSource {
            var name = "file"
            var size = -1L
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0) size = c.getLong(si)
                }
            }
            if (size <= 0) {
                // Chain of fallbacks: asset fd length, then parcel fd statSize.
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
                } catch (ignored: Exception) {}
            }
            if (size <= 0) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
                } catch (ignored: Exception) {}
            }
            if (size <= 0) size = -1 // unknown; UI shows "unknown" instead of 0 B
            return SafFileSource(context, uri, name, size)
        }
    }
}

class FsFileSource(private val file: File) : FileSource {
    override val displayName: String get() = file.name
    override val sizeBytes: Long get() = file.length()
    override fun openStream(): InputStream = FileInputStream(file)
    override fun openSeekable(): InputStream = FileInputStream(file)
}

/** Walks a folder picked through SAF and yields every file with its relative path. */
object FolderCrawler {
    data class Picked(val source: FileSource, val relPath: String)

    fun fromUris(context: Context, uris: List<Uri>): List<Picked> {
        val out = ArrayList<Picked>()
        for (uri in uris) {
            val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
            if (doc != null && doc.isDirectory) {
                walk(context, doc, "", out)
            } else {
                val src = SafFileSource.query(context, uri)
                out.add(Picked(src, src.displayName))
            }
        }
        return out
    }

    private fun walk(context: Context, dir: androidx.documentfile.provider.DocumentFile, prefix: String, out: MutableList<Picked>) {
        val children = dir.listFiles()
        for (child in children) {
            val rel = if (prefix.isEmpty()) child.name ?: continue else prefix + "/" + (child.name ?: continue)
            if (child.isDirectory) {
                walk(context, child, rel, out)
            } else if (child.isFile) {
                val src = SafFileSource(context, child.uri, child.name ?: "file", child.length())
                out.add(Picked(src, rel))
            }
        }
    }
}
