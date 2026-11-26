package dev.naijun.dununlocker.proxy

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

internal class LocalProxyServer(
    val port: Int,
    private val onActiveConnectionsChanged: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "LocalProxyServer"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val BUFFER_SIZE = 16 * 1024
        private val HOP_BY_HOP_HEADERS = setOf(
            "proxy-connection",
            "proxy-authorization",
            "proxy-authenticate",
            "keep-alive",
            "connection",
            "te",
            "trailers",
            "upgrade"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConnections = AtomicInteger(0)
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start() {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        }
        serverSocket = server

        scope.launch {
            while (isActive && !server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    if (!server.isClosed) {
                        Log.w(TAG, "Accept failed: ${e.message}")
                    }
                    break
                }

                if (!NetworkAddressResolver.isPrivateOrLoopback(client.inetAddress)) {
                    runCatching { client.close() }
                    continue
                }

                scope.launch {
                    val count = activeConnections.incrementAndGet()
                    onActiveConnectionsChanged(count)
                    try {
                        handleClient(client)
                    } catch (_: CancellationException) {
                        // Shutting down
                    } catch (e: Exception) {
                        Log.d(TAG, "Client closed: ${e.message}")
                    } finally {
                        runCatching { client.close() }
                        val remaining = activeConnections.decrementAndGet()
                        onActiveConnectionsChanged(remaining)
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
        activeConnections.set(0)
        onActiveConnectionsChanged(0)
    }

    private suspend fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = HANDSHAKE_TIMEOUT_MS

        val input = PushbackInputStream(BufferedInputStream(client.getInputStream(), BUFFER_SIZE), 8)
        val output = client.getOutputStream()

        val firstByte = input.read()
        if (firstByte == -1) return
        input.unread(firstByte)

        if (firstByte == 0x05) {
            handleSocks5(client, input, output)
        } else {
            handleHttp(client, input, output)
        }
    }

    private suspend fun handleSocks5(
        client: Socket,
        input: InputStream,
        output: OutputStream
    ) {
        val version = input.read()
        val nMethods = input.read()
        if (version != 0x05 || nMethods < 0) return

        val methods = readExact(input, nMethods)
        if (!methods.any { it.toInt() == 0x00 }) {
            output.write(byteArrayOf(0x05, 0xFF.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val reqHeader = readExact(input, 4)
        if (reqHeader[0].toInt() != 0x05 || reqHeader[1].toInt() != 0x01) {
            // Only CONNECT supported
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        val targetHost: String = when (reqHeader[3].toInt() and 0xFF) {
            0x01 -> {
                val addrBytes = readExact(input, 4)
                InetAddress.getByAddress(addrBytes).hostAddress ?: return
            }
            0x03 -> {
                val len = input.read()
                if (len <= 0) return
                String(readExact(input, len), StandardCharsets.US_ASCII)
            }
            0x04 -> {
                val addrBytes = readExact(input, 16)
                InetAddress.getByAddress(addrBytes).hostAddress ?: return
            }
            else -> {
                output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                return
            }
        }
        val portBytes = readExact(input, 2)
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

        val target = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        target.use { remote ->
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            client.soTimeout = 0
            relayBidirectional(client, input, output, remote)
        }
    }

    private suspend fun handleHttp(
        client: Socket,
        input: InputStream,
        output: OutputStream
    ) {
        val headerBlock = readHttpHeaderBlock(input) ?: return
        val lines = headerBlock.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Bad Request")
            return
        }

        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]
        val headers = lines.drop(1)
            .filter { it.contains(":") }
            .map { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }

        when {
            method == "CONNECT" -> handleHttpConnect(client, input, output, target)
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) -> {
                handleHttpForward(client, input, output, method, target, version, headers)
            }
            target.startsWith("/") -> {
                handlePortalRequest(client, output, target, headers)
            }
            else -> {
                writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Unsupported request")
            }
        }
    }

    private suspend fun handleHttpConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        authority: String
    ) {
        val (host, targetPort) = parseHostPort(authority, defaultPort = 443) ?: run {
            writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Invalid CONNECT target")
            return
        }

        val remote = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            writeSimpleResponse(output, "502 Bad Gateway", "text/plain; charset=utf-8", "Connect failed: ${e.message}")
            return
        }

        remote.use { targetSocket ->
            output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: DunUnlocker\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            client.soTimeout = 0
            relayBidirectional(client, input, output, targetSocket)
        }
    }

    private suspend fun handleHttpForward(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        method: String,
        targetUrl: String,
        version: String,
        headers: List<Pair<String, String>>
    ) {
        val uri = try {
            URI(targetUrl)
        } catch (_: Exception) {
            writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Invalid URL")
            return
        }
        val host = uri.host ?: run {
            writeSimpleResponse(output, "400 Bad Request", "text/plain; charset=utf-8", "Missing host")
            return
        }
        val targetPort = if (uri.port > 0) uri.port else 80
        val path = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            if (!uri.rawQuery.isNullOrEmpty()) {
                append('?').append(uri.rawQuery)
            }
        }

        val remote = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            writeSimpleResponse(output, "502 Bad Gateway", "text/plain; charset=utf-8", "Connect failed: ${e.message}")
            return
        }

        remote.use { targetSocket ->
            val remoteOut = targetSocket.getOutputStream()
            val headerBuilder = StringBuilder()
            headerBuilder.append("$method $path $version\r\n")
            var hasHost = false
            for ((name, value) in headers) {
                if (name.lowercase() in HOP_BY_HOP_HEADERS) continue
                if (name.equals("Host", ignoreCase = true)) hasHost = true
                headerBuilder.append("$name: $value\r\n")
            }
            if (!hasHost) {
                val hostHeader = if (targetPort == 80) host else "$host:$targetPort"
                headerBuilder.append("Host: $hostHeader\r\n")
            }
            headerBuilder.append("Connection: close\r\n\r\n")
            remoteOut.write(headerBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1))
            remoteOut.flush()

            val contentLength = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second?.toLongOrNull() ?: 0L
            if (contentLength > 0L) {
                copyBounded(input, remoteOut, contentLength)
                remoteOut.flush()
            }

            client.soTimeout = 0
            copyStream(targetSocket.getInputStream(), output)
        }
    }

    private fun handlePortalRequest(
        client: Socket,
        output: OutputStream,
        rawPath: String,
        headers: List<Pair<String, String>>
    ) {
        val path = rawPath.substringBefore('?')
        val hostHeader = headers.firstOrNull { it.first.equals("Host", ignoreCase = true) }?.second
        val fallbackIp = client.localAddress?.hostAddress ?: "127.0.0.1"
        val host = SetupPortalHtml.sanitizeHost(hostHeader, fallbackIp)
        val clientIp = client.inetAddress?.hostAddress ?: "unknown"

        when (path) {
            "/", "/setup" -> {
                writeSimpleResponse(
                    output,
                    "200 OK",
                    "text/html; charset=utf-8",
                    SetupPortalHtml.portalHtml(host, port, clientIp)
                )
            }
            "/proxy.pac" -> {
                writeSimpleResponse(
                    output,
                    "200 OK",
                    "application/x-ns-proxy-autoconfig; charset=utf-8",
                    SetupPortalHtml.pacScript(host, port)
                )
            }
            "/health" -> {
                writeSimpleResponse(
                    output,
                    "200 OK",
                    "application/json; charset=utf-8",
                    """{"status":"ok","host":"$host","port":$port}"""
                )
            }
            "/download/win-proxy-on.bat" -> {
                writeDownloadResponse(output, "dununlocker-proxy-on.bat", SetupPortalHtml.windowsProxyOnBat(host, port))
            }
            "/download/win-proxy-off.bat" -> {
                writeDownloadResponse(output, "dununlocker-proxy-off.bat", SetupPortalHtml.windowsProxyOffBat())
            }
            "/download/win-ttl-65.bat" -> {
                writeDownloadResponse(output, "dununlocker-ttl-65.bat", SetupPortalHtml.windowsTtlBat(65))
            }
            "/download/win-ttl-reset.bat" -> {
                writeDownloadResponse(output, "dununlocker-ttl-reset.bat", SetupPortalHtml.windowsTtlBat(128))
            }
            "/download/mac-proxy-on.sh" -> {
                writeDownloadResponse(output, "dununlocker-mac-proxy-on.sh", SetupPortalHtml.macProxyOnSh(host, port))
            }
            "/download/mac-proxy-off.sh" -> {
                writeDownloadResponse(output, "dununlocker-mac-proxy-off.sh", SetupPortalHtml.macProxyOffSh())
            }
            "/download/linux-ttl.sh" -> {
                writeDownloadResponse(output, "dununlocker-linux-ttl.sh", SetupPortalHtml.linuxTtlSh(host, port, 65))
            }
            else -> {
                writeSimpleResponse(output, "404 Not Found", "text/plain; charset=utf-8", "Not Found")
            }
        }
    }

    private suspend fun relayBidirectional(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket
    ) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val upstream = scope.async {
            try {
                copyStream(clientIn, remoteOut)
                runCatching { remote.shutdownOutput() }
            } catch (_: Exception) {
                runCatching { remote.close() }
            }
        }
        val downstream = scope.async {
            try {
                copyStream(remoteIn, clientOut)
                runCatching { client.shutdownOutput() }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
        try {
            upstream.await()
            downstream.await()
        } finally {
            upstream.cancel()
            downstream.cancel()
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun copyBounded(input: InputStream, output: OutputStream, totalBytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = totalBytes
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun readHttpHeaderBlock(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        var matched = 0
        while (baos.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b == -1) return null
            baos.write(b)
            matched = when (matched) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (matched == 4) {
                val raw = baos.toByteArray()
                return String(raw, 0, raw.size - 4, StandardCharsets.ISO_8859_1)
            }
        }
        return null
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) throw EOFException("Unexpected EOF")
            offset += read
        }
        return buf
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("[")) {
            val closeIdx = trimmed.indexOf(']')
            if (closeIdx < 0) return null
            val host = trimmed.substring(1, closeIdx)
            val portPart = trimmed.substring(closeIdx + 1)
            val port = if (portPart.startsWith(":")) portPart.substring(1).toIntOrNull() else defaultPort
            if (host.isEmpty() || port == null || port !in 1..65535) null else host to port
        } else {
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx > 0) {
                val host = trimmed.substring(0, colonIdx)
                val port = trimmed.substring(colonIdx + 1).toIntOrNull()
                if (host.isEmpty() || port == null || port !in 1..65535) null else host to port
            } else {
                trimmed to defaultPort
            }
        }
    }

    private fun writeSimpleResponse(
        output: OutputStream,
        status: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun writeDownloadResponse(
        output: OutputStream,
        filename: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Disposition: attachment; filename=\"$filename\"\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }
}
