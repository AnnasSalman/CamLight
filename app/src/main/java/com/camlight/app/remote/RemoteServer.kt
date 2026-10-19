package com.camlight.app.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/** What the web page can ask the app for. Implemented by the camera view model. */
interface RemoteApi {
    /** Latest preview as JPEG (null if none yet). */
    fun frame(debug: Boolean): ByteArray?
    fun state(): String

    /** The pattern the TV page should show now (JSON). [info] carries what the TV page reports about itself. */
    fun pattern(info: Map<String, String>): String

    /** Applies one JSON command and returns a JSON reply. */
    fun command(json: String): String
}

/**
 * Tiny dependency-free HTTP server for the remote page. Every request must carry the secret key (`k=`).
 * One short-lived connection per request; no websockets or streaming.
 */
class RemoteServer(
    private val api: RemoteApi,
    private val key: String,
    private val page: ByteArray,
    /** Page shown full screen on the TV (served at /p/<code>). */
    private val patternsPage: ByteArray = ByteArray(0),
    /** Short code (digits) that only opens the TV page and its pattern feed. */
    private val code: String = "",
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "camlight-remote").apply { isDaemon = true } }
    @Volatile private var running = false
    var port: Int = 0
        private set

    /** Starts listening on the first free port from [firstPort]. Returns the port, or null on failure. */
    fun start(firstPort: Int = 8080): Int? {
        for (p in firstPort until firstPort + 10) {
            try {
                val s = ServerSocket(p)
                server = s
                port = p
                running = true
                Thread({ acceptLoop(s) }, "camlight-remote-accept").apply { isDaemon = true }.start()
                return p
            } catch (_: Exception) { }
        }
        return null
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            try { pool.execute { handle(c) } } catch (_: Exception) { runCatching { c.close() } }
        }
    }

    private fun handle(sock: Socket) {
        sock.use {
            try {
                sock.soTimeout = 5000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0]
                val rawPath = parts[1]
                var contentLength = 0
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("content-length:", ignoreCase = true)) contentLength = h.substring(15).trim().toIntOrNull() ?: 0
                }
                if (contentLength > MAX_BODY) { respond(sock, 413, "text/plain", "too large".toByteArray()); return }
                val body = if (contentLength > 0) String(readFully(input, contentLength), Charsets.UTF_8) else ""

                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "").split('&').filter { it.contains('=') }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val keyOk = query["k"] == key

                // The TV test page and its pattern feed accept the short code (nothing else does).
                if (method == "GET" && path.startsWith("/p/")) {
                    if (path.removePrefix("/p/").trimEnd('/') == code) respond(sock, 200, "text/html; charset=utf-8", patternsPage)
                    else respond(sock, 403, "text/plain", "forbidden".toByteArray())
                    return
                }
                if (method == "GET" && path == "/pattern") {
                    if (keyOk || query["c"] == code) respond(sock, 200, "application/json", api.pattern(query).toByteArray())
                    else respond(sock, 403, "text/plain", "forbidden".toByteArray())
                    return
                }
                if (!keyOk) { respond(sock, 403, "text/plain", "forbidden".toByteArray()); return }

                when {
                    method == "GET" && path == "/" -> respond(sock, 200, "text/html; charset=utf-8", page)
                    method == "GET" && path == "/frame.jpg" -> {
                        val jpg = api.frame(query["debug"] == "1")
                        if (jpg == null) respond(sock, 204, "text/plain", ByteArray(0)) else respond(sock, 200, "image/jpeg", jpg)
                    }
                    method == "GET" && path == "/state" -> respond(sock, 200, "application/json", api.state().toByteArray())
                    method == "POST" && path == "/cmd" -> respond(sock, 200, "application/json", api.command(body).toByteArray())
                    else -> respond(sock, 404, "text/plain", "not found".toByteArray())
                }
            } catch (e: Exception) {
                Log.d(TAG, "request failed: ${e.message}")
            }
        }
    }

    private fun readFully(input: BufferedInputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf.copyOf(read)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else String(out.toByteArray())
            if (b == '\n'.code) return String(out.toByteArray()).trimEnd('\r')
            if (out.size() > 8192) return null
            out.write(b)
        }
    }

    private fun respond(sock: Socket, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "CamLight"
        private const val MAX_BODY = 16 * 1024

        /** First private IPv4 address of this device (the Wi-Fi address on a home network). */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
