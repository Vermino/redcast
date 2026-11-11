package com.oak404.redcast.cast

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Serves clips to the Cast receiver from this phone over the local network.
 *
 * The media host answers 403 to any request with the receiver's gstatic.com Referer, and a
 * receiver can't turn that header off. The phone sends none, so it fetches each clip and passes
 * the bytes along. Requests need the random per-session token and can only name a media file
 * on [mediaBase], so this isn't an open proxy for other devices on the network.
 */
class CastRelay(
    private val http: OkHttpClient,
    private val mediaBase: String = MEDIA_BASE,
) {
    private val token = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }

    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null
    private var baseUrl: String? = null

    /** Starts listening on [address], the phone's address on the network the receiver is on. */
    @Synchronized
    fun start(address: InetAddress) {
        if (server != null) return
        val socket = ServerSocket(0, BACKLOG, address)
        // A receiver holds a few connections at once; the cap keeps anything else on the network
        // from tying up threads without end. One more thread accepts.
        val pool = ThreadPoolExecutor(1, MAX_CONNECTIONS + 1, 30L, TimeUnit.SECONDS, SynchronousQueue())
        server = socket
        workers = pool
        baseUrl = "http://${address.hostAddress}:${socket.localPort}/$token"
        pool.execute {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    pool.execute { serve(client) }
                } catch (_: RejectedExecutionException) {
                    runCatching { client.close() }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        workers?.shutdownNow()
        server = null
        workers = null
        baseUrl = null
    }

    /** The address the receiver should use for [mediaUrl], or null if it can't be relayed. */
    @Synchronized
    fun relayUrl(mediaUrl: String): String? {
        val base = baseUrl ?: return null
        if (!mediaUrl.startsWith("$mediaBase/")) return null
        val name = mediaUrl.removePrefix("$mediaBase/")
        return if (MEDIA_NAME.matches(name)) "$base/$name" else null
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = READ_TIMEOUT_MS
                val request = readRequest(BufferedInputStream(it.getInputStream())) ?: return
                respond(request, it.getOutputStream())
            }
        } catch (_: IOException) {
            // The receiver drops connections as it seeks and moves between clips.
        } catch (_: IllegalArgumentException) {
            // A header OkHttp won't send on (odd characters in Range): drop the request, not the app.
        }
    }

    private fun respond(request: RelayRequest, out: OutputStream) {
        if (request.method == "OPTIONS") {
            out.writeHead("204 No Content", listOf("Access-Control-Allow-Headers" to "Range"))
            return
        }
        val name = mediaNameIn(request.path)
        if (name == null || request.method !in setOf("GET", "HEAD")) {
            out.writeHead("404 Not Found", listOf("Content-Length" to "0"))
            return
        }

        val upstream = Request.Builder()
            .url("$mediaBase/$name")
            .method(request.method, null)
            // Keeps byte ranges and Content-Length exact.
            .header("Accept-Encoding", "identity")
            .apply { request.range?.let { header("Range", it) } }
            .build()

        http.newCall(upstream).execute().use { response ->
            val headers = FORWARDED_HEADERS.mapNotNull { key -> response.header(key)?.let { key to it } }
            out.writeHead("${response.code} ${response.message.ifEmpty { "OK" }}", headers)
            if (request.method == "GET") response.body?.byteStream()?.copyTo(out)
        }
    }

    private fun mediaNameIn(path: String): String? {
        val prefix = "/$token/"
        if (!path.startsWith(prefix)) return null
        return path.removePrefix(prefix).takeIf(MEDIA_NAME::matches)
    }

    private fun readRequest(input: InputStream): RelayRequest? {
        val lines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }
            .take(MAX_HEADER_LINES).toList()
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val range = lines.drop(1).firstOrNull { it.startsWith("range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return RelayRequest(parts[0], parts[1], range)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE_LENGTH) {
            when (val byte = input.read()) {
                -1 -> return if (line.isEmpty()) null else line.toString()
                '\n'.code -> return line.toString().trimEnd('\r')
                else -> line.append(byte.toChar())
            }
        }
        return null
    }

    private fun OutputStream.writeHead(status: String, headers: List<Pair<String, String>>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
            append("Connection: close\r\n\r\n")
        }
        write(head.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    private class RelayRequest(val method: String, val path: String, val range: String?)

    private companion object {
        const val MEDIA_BASE = "https://media.redgifs.com"
        const val BACKLOG = 16
        const val MAX_CONNECTIONS = 16
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_HEADER_LINES = 64
        const val MAX_LINE_LENGTH = 8192
        val MEDIA_NAME = Regex("[A-Za-z0-9-]+\\.(mp4|jpg)")
        val FORWARDED_HEADERS =
            listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")
    }
}
