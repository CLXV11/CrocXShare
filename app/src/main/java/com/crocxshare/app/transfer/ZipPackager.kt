package com.crocxshare.app.transfer

import android.content.Context
import android.net.Uri
import com.crocxshare.app.core.protocol.PathsGuard
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packages selected files (or a whole picked folder) into a single ZIP before sending. */
object ZipPackager {

    /** Zip plain file sources. Returns the output file. */
    fun zipSources(sources: List<FileSource>, out: File): File {
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out).buffered(256 * 1024)).use { zip ->
            val used = HashSet<String>()
            for (src in sources) {
                val name = unique(sanitize(src.displayName) ?: "file", used)
                zip.putNextEntry(ZipEntry(name))
                src.openStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        zip.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        return out
    }

    /** Zip a folder picked through SAF, preserving its inner structure. */
    fun zipFolder(context: Context, treeUri: Uri, out: File): Pair<Int, Long> {
        val picked = FolderCrawler.fromUris(context, listOf(treeUri))
        out.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(out).buffered(256 * 1024)).use { zip ->
            val used = HashSet<String>()
            for (p in picked) {
                val safe = PathsGuard.sanitize(p.relPath) ?: continue
                runCatching {
                    val name = unique(safe, used)
                    zip.putNextEntry(ZipEntry(name))
                    p.source.openStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zip.write(buf, 0, n)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return picked.size to out.length()
    }

    private fun sanitize(name: String): String? {
        val n = name.replace('\\', '/').substringAfterLast('/')
        return PathsGuard.sanitize(n) ?: if (n.isNotBlank() && n.length <= 255) n else null
    }

    private fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (!used.add("$base ($n)$ext")) n++
        return "$base ($n)$ext"
    }
}
