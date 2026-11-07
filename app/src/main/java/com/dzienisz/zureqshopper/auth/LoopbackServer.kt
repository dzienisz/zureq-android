package com.dzienisz.zureqshopper.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Catches the OAuth redirect to http://localhost:<port>/callback, the loopback redirect ZUREQ accepts
 * for apps (RFC 8252). It only listens on the loopback interface and accepts a single callback.
 */
class LoopbackServer(private val port: Int = ZureqAuth.CALLBACK_PORT) {
    private var socket: ServerSocket? = null
    private val callback = CompletableDeferred<String>()

    /** Binds the port; throws if it is taken. */
    fun start() {
        // Android's /etc/hosts maps "localhost" to 127.0.0.1 only; getLoopbackAddress() may pick ::1, which the browser never reaches.
        val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        socket = server
        Thread({ serve(server) }, "zureq-oauth-callback").apply { isDaemon = true }.start()
    }

    /** The full callback URL once the browser hits /callback. */
    suspend fun awaitCallback(): String = callback.await()

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        callback.cancel()
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed && !callback.isCompleted) {
            val client = try { server.accept() } catch (e: Exception) { return }
            client.use { handle(it) }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val line = runCatching { BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1)).readLine() }.getOrNull()
        val target = line?.let(::requestTarget)
        val url = target?.takeIf { it.startsWith("/callback") }?.let { "http://localhost:$port$it" }
        val body = if (url == null) "Not found" else DONE_PAGE
        val head = if (url == null) "404 Not Found" else "200 OK"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $head\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            client.getOutputStream().apply { write(response.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        }
        if (url != null) callback.complete(url)
    }

    companion object {
        /** The path and query of an HTTP GET request line, e.g. "/callback?code=…". */
        fun requestTarget(line: String): String? {
            val parts = line.trim().split(' ')
            if (parts.size != 3 || parts[0] != "GET" || !parts[1].startsWith("/")) return null
            return parts[1]
        }

        private val DONE_PAGE = """
            <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>ZUREQ Shopper</title><style>body{font-family:system-ui,sans-serif;text-align:center;padding:48px 24px;color:#111}p{color:#555}</style></head>
            <body><h1>Signed in</h1><p>You can close this page and return to ZUREQ Shopper.</p></body></html>
        """.trimIndent()
    }
}
