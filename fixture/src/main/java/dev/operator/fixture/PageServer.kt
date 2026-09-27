package dev.operator.fixture

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The fixture localhost page server (FOUNDATION §12 C12; ADR-0015; research 07 §R2).
 *
 * Binds `127.0.0.1:<port>` only — never `0.0.0.0` — so it is reachable by browsers on the phone and by
 * nothing else. This is the reason `:fixture` is the one module that declares
 * `android.permission.INTERNET`: operator's own manifests stay INTERNET-free (C12), and the `checks`
 * CI job asserts it.
 *
 * One thread, one request at a time, `Connection: close`; the pages are in [Pages].
 */
class PageServer(private val port: Int = Pages.DEFAULT_PORT) {

    private val stopped = AtomicBoolean(false)
    @Volatile
    private var socket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        stopped.set(false)
        thread = Thread({ serve() }, "fixture-pages").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopped.set(true)
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve() {
        val server = try {
            ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
        } catch (failure: IOException) {
            Log.e(TAG, "cannot bind 127.0.0.1:$port", failure)
            return
        }
        socket = server
        Log.i(TAG, "page server on http://127.0.0.1:$port")
        while (!stopped.get()) {
            val client = try {
                server.accept()
            } catch (failure: IOException) {
                if (!stopped.get()) Log.w(TAG, "accept failed", failure)
                break
            }
            runCatching { handle(client) }.onFailure { Log.w(TAG, "request failed", it) }
        }
        runCatching { server.close() }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = READ_TIMEOUT_MS
            val requestLine = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
                .readLine().orEmpty()
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: Pages.PATH_INDEX
            val body = Pages.body(path).toByteArray(StandardCharsets.UTF_8)
            val headers = ("HTTP/1.1 " + Pages.statusCode(path) + "\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8)
            connection.getOutputStream().apply {
                write(headers)
                write(body)
                flush()
            }
        }
    }

    private companion object {
        const val TAG = "operator.fixture"
        const val BACKLOG = 4
        const val READ_TIMEOUT_MS = 5_000
    }
}
