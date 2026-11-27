package dev.naijun.dununlocker.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class LocalProxyServerTest {

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun servesPortalPacHealthAndDownloadScripts() {
        val port = findFreePort()
        val server = LocalProxyServer(port)
        server.start()
        try {
            val health = sendRawHttp(port, "GET /health HTTP/1.1\r\nHost: 192.168.43.1:$port\r\n\r\n")
            assertTrue(health.contains("200 OK"))
            assertTrue(health.contains("\"port\":$port"))

            val pac = sendRawHttp(port, "GET /proxy.pac HTTP/1.1\r\nHost: 192.168.43.1:$port\r\n\r\n")
            assertTrue(pac.contains("200 OK"))
            assertTrue(pac.contains("PROXY 192.168.43.1:$port; DIRECT"))

            val portal = sendRawHttp(port, "GET / HTTP/1.1\r\nHost: 192.168.43.1:$port\r\n\r\n")
            assertTrue(portal.contains("200 OK"))
            assertTrue(portal.contains("DunUnlocker"))
            assertTrue(portal.contains("/download/win-proxy-on.bat"))

            val bat = sendRawHttp(port, "GET /download/win-proxy-on.bat HTTP/1.1\r\nHost: 192.168.43.1:$port\r\n\r\n")
            assertTrue(bat.contains("200 OK"))
            assertTrue(bat.contains("http://192.168.43.1:$port/proxy.pac"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun tunnelsTcpViaHttpConnectAndSocks5() {
        val echoServer = ServerSocket(0)
        val echoPort = echoServer.localPort
        val echoThread = Thread {
            repeat(2) {
                echoServer.accept().use { s ->
                    val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine()
                    s.getOutputStream().write("ECHO:$line\n".toByteArray(StandardCharsets.UTF_8))
                    s.getOutputStream().flush()
                }
            }
        }.apply { start() }

        val proxyPort = findFreePort()
        val server = LocalProxyServer(proxyPort)
        server.start()
        try {
            // 1. HTTP CONNECT tunnel
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 5000
                val out = client.getOutputStream()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                out.write("CONNECT 127.0.0.1:$echoPort HTTP/1.1\r\nHost: 127.0.0.1:$echoPort\r\n\r\n".toByteArray())
                out.flush()
                val statusLine = reader.readLine()
                assertTrue("Expected 200 but got $statusLine", statusLine.contains("200"))
                while (reader.readLine().isNotEmpty()) { /* consume headers */ }

                out.write("hello-connect\n".toByteArray())
                out.flush()
                assertEquals("ECHO:hello-connect", reader.readLine())
            }

            // 2. SOCKS5 CONNECT tunnel
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 5000
                val out = client.getOutputStream()
                val input = client.getInputStream()
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())

                val req = byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    127, 0, 0, 1,
                    ((echoPort shr 8) and 0xFF).toByte(),
                    (echoPort and 0xFF).toByte()
                )
                out.write(req)
                out.flush()
                val reply = ByteArray(10)
                var read = 0
                while (read < 10) {
                    val n = input.read(reply, read, 10 - read)
                    if (n < 0) break
                    read += n
                }
                assertEquals(0x05, reply[0].toInt())
                assertEquals(0x00, reply[1].toInt())

                out.write("hello-socks5\n".toByteArray())
                out.flush()
                val line = BufferedReader(InputStreamReader(input)).readLine()
                assertEquals("ECHO:hello-socks5", line)
            }
        } finally {
            server.stop()
            echoServer.close()
            echoThread.join(3000)
        }
    }

    @Test
    fun forwardsPlainHttpRequests() {
        val httpTarget = ServerSocket(0)
        val targetPort = httpTarget.localPort
        val targetThread = Thread {
            httpTarget.accept().use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val reqLine = reader.readLine()
                while (reader.readLine().isNotEmpty()) { /* consume headers */ }
                val body = "TARGET:$reqLine"
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                s.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                s.getOutputStream().flush()
            }
        }.apply { start() }

        val proxyPort = findFreePort()
        val server = LocalProxyServer(proxyPort)
        server.start()
        try {
            val response = sendRawHttp(
                proxyPort,
                "GET http://127.0.0.1:$targetPort/ping?x=1 HTTP/1.1\r\nHost: 127.0.0.1:$targetPort\r\n\r\n"
            )
            assertTrue(response.contains("200 OK"))
            assertTrue(response.contains("TARGET:GET /ping?x=1 HTTP/1.1"))
        } finally {
            server.stop()
            httpTarget.close()
            targetThread.join(3000)
        }
    }

    private fun sendRawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
    }
}
