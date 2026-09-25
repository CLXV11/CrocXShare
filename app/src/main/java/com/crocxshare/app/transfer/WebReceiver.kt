package com.crocxshare.app.transfer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.crocxshare.app.core.protocol.Checksums
import com.crocxshare.app.core.protocol.PathsGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional local Web Receiver: a phone exposes a temporary, token-protected
 * HTTP endpoint so any device with a browser can upload files. The session is
 * bound to a random port, requires the session token in every URL, accepts at
 * most a few connections, and is closed permanently when [close] is called.
 * This is deliberately NOT a permanent network service.
 */
class WebReceiver(
    private val context: Context,
    private val treeUri: Uri,
    private val token: String,
    private val deviceName: String,
    private val scope: CoroutineScope
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(3)
    private var job: Job? = null
    @Volatile var lastError: String? = null
        private set

    val port: Int get() = server?.localPort ?: -1

    fun start(): Int {
        if (server != null) return port
        val s = ServerSocket(0)
        s.reuseAddress = true
        server = s
        job = scope.launch(Dispatchers.IO) {
            while (true) {
                val client = try { s.accept() } catch (e: Exception) { break }
                pool.submit { handle(client) }
            }
        }
        return s.localPort
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15_000
            c.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(c.getInputStream()))
            val requestLine = reader.readLine() ?: return send(c, 400, "text/plain", "bad request")
            val parts = requestLine.split(" ")
            if (parts.size < 2) return send(c, 400, "text/plain", "bad request")
            val method = parts[0]
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val params = query.split('&').filter { it.contains('=') }.associate {
                val k = it.substringBefore('=')
                val v = URLDecoder.decode(it.substringAfter('='), "UTF-8")
                k to v
            }

            // Every route requires the session token.
            val expectedPrefix = "/$token"
            if (!path.startsWith(expectedPrefix)) return send(c, 403, "text/plain", "forbidden")

            when {
                method == "GET" && path == expectedPrefix + "/" -> send(c, 200, "text/html; charset=utf-8", page())
                method == "POST" && path == expectedPrefix + "/u" -> upload(c, params["name"] ?: "upload.bin")
                else -> send(c, 404, "text/plain", "not found")
            }
        } catch (e: SocketException) {
            // client vanished mid-request
        } catch (e: Exception) {
            lastError = e.message
            try { send(c, 500, "text/plain", "internal error") } catch (ignored: Exception) {}
        } finally {
            try { c.close() } catch (ignored: Exception) {}
        }
    }

    private fun upload(c: Socket, rawName: String) {
        val clean = PathsGuard.sanitize(rawName.replace('\\', '/').substringAfterLast('/'))
            ?: return send(c, 400, "application/json", "{\"ok\":false,\"error\":\"unsafe filename\"}")
        val headers = HashMap<String, String>()
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
            ?: return send(c, 411, "application/json", "{\"ok\":false,\"error\":\"length required\"}")
        if (contentLength < 0 || contentLength > (1L shl 40)) {
            return send(c, 413, "application/json", "{\"ok\":false,\"error\":\"too large\"}")
        }

        val sink = try {
            SafSinkFactory(context, treeUri, autoRename = true).createSink(clean, 0, contentLength)
        } catch (e: Exception) {
            return send(c, 507, "application/json", "{\"ok\":false,\"error\":\"cannot write destination\"}")
        }

        return try {
            val input = c.getInputStream()
            val buf = ByteArray(256 * 1024)
            val sha = com.crocxshare.app.core.protocol.StreamingSha256()
            var pos = 0L
            var remaining = contentLength
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) throw java.io.EOFException("truncated upload")
                sink.writeAt(pos, buf, 0, n)
                sha.update(buf, 0, n)
                pos += n
                remaining -= n
            }
            sink.commit()
            send(c, 200, "application/json",
                "{\"ok\":true,\"name\":\"" + clean.replace("\"", "") + "\",\"sha256\":\"" +
                    Checksums.hex(sha.value()) + "\"}")
        } catch (e: Exception) {
            sink.discard()
            send(c, 500, "application/json", "{\"ok\":false,\"error\":\"upload failed\"}")
        }
    }

    private fun page(): String {
        return "<!doctype html><html><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<title>CrocXShare</title>" +
            "<style>body{font-family:system-ui;margin:2rem;max-width:34rem}" +
            "h1{font-size:1.4rem}input,button{font-size:1rem;padding:.6rem;margin:.4rem 0}" +
            "button{background:#2e7d32;color:#fff;border:0;border-radius:.5rem;padding:.7rem 1.4rem}" +
            "#st{white-space:pre-wrap}</style></head><body>" +
            "<h1>CrocXShare — " + deviceName.replace("<", "") + "</h1>" +
            "<p>Local transfer session. Files go straight to this device; nothing is uploaded to the internet.</p>" +
            "<input type=file id=f multiple><br>" +
            "<button onclick=up()>Send</button><div id=st></div>" +
            "<script>async function up(){const fs=document.getElementById('f').files;" +
            "const st=document.getElementById('st');" +
            "for(const f of fs){st.textContent='Sending '+f.name+'…';" +
            "const r=await fetch(location.pathname+'u?name='+encodeURIComponent(f.name)," +
            "{method:'POST',headers:{'Content-Length':f.size},body:f});" +
            "const j=await r.json().catch(()=>({ok:false}));" +
            "st.textContent+=(r.ok&&j.ok)?('\nDone: '+f.name):('\nFailed: '+f.name);}}" +
            "</script></body></html>"
    }

    private fun send(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = ("HTTP/1.1 " + code + " " + statusText(code) + "\r\n" +
            "Content-Type: " + type + "\r\n" +
            "Content-Length: " + bytes.size + "\r\n" +
            "Connection: close\r\nCache-Control: no-store\r\n\r\n").toByteArray(Charsets.US_ASCII)
        c.getOutputStream().apply { write(head); write(bytes); flush() }
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
        411 -> "Length Required"; 413 -> "Payload Too Large"; 507 -> "Insufficient Storage"
        else -> "Error"
    }

    fun close() {
        try { server?.close() } catch (ignored: Exception) {}
        server = null
        job?.cancel()
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)
    }
}
