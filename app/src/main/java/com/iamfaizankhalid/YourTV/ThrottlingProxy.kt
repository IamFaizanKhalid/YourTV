package com.iamfaizankhalid.YourTV

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Tiny loopback HTTP proxy that throttles downloaded bytes. WebView is pointed
 * at it with ProxyController so only the player's traffic is limited.
 */
class ThrottlingProxy(initialMbps: Int) {
    companion object {
        private const val TAG = "YourTVProxy"
        private const val BUFFER_SIZE = 16 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val limiter = BandwidthLimiter(mbpsToBytesPerSecond(initialMbps))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = true

    val port: Int get() = serverSocket.localPort

    fun start() {
        Thread({ acceptLoop() }, "ThrottlingProxy").apply {
            isDaemon = true
            start()
        }
    }

    fun setBandwidthMbps(mbps: Int) {
        limiter.bytesPerSecond = mbpsToBytesPerSecond(mbps)
    }

    fun stop() {
        running = false
        closeQuietly(serverSocket)
        sockets.forEach(::closeQuietly)
        sockets.clear()
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                serverSocket.accept()
            } catch (error: IOException) {
                if (running) Log.w(TAG, "Accept failed", error)
                break
            }
            sockets.add(client)
            Thread({ handle(client) }, "ThrottlingProxyClient").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
            }

            val parts = requestLine.trim().split(Regex("\\s+"))
            if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeAll(
                    client.getOutputStream(),
                    "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n"
                )
                return
            }

            val target = parts[1]
            val separator = target.lastIndexOf(':')
            if (separator <= 0) return
            val host = target.substring(0, separator).trim('[', ']')
            val port = target.substring(separator + 1).toIntOrNull() ?: 443

            upstream = Socket()
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sockets.add(upstream)

            writeAll(
                client.getOutputStream(),
                "HTTP/1.1 200 Connection Established\r\n\r\n"
            )

            val remote = upstream
            val upload = Thread({
                try {
                    copy(client.getInputStream(), remote.getOutputStream(), null)
                } catch (_: IOException) {
                } finally {
                    closeQuietly(client)
                    closeQuietly(remote)
                }
            }, "ThrottlingProxyUpload").apply {
                isDaemon = true
                start()
            }

            val download = Thread({
                try {
                    copy(remote.getInputStream(), client.getOutputStream(), limiter)
                } catch (_: IOException) {
                } finally {
                    closeQuietly(remote)
                    closeQuietly(client)
                }
            }, "ThrottlingProxyDownload").apply {
                isDaemon = true
                start()
            }

            upload.join()
            download.join()
        } catch (error: IOException) {
            Log.d(TAG, "Connection failed: ${error.message}")
        } finally {
            closeQuietly(client)
            upstream?.let(::closeQuietly)
            sockets.remove(client)
            upstream?.let(sockets::remove)
        }
    }

    private fun copy(
        input: InputStream,
        output: OutputStream,
        limiter: BandwidthLimiter?
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            limiter?.acquire(read)
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (builder.length < 16 * 1024) builder.append(value.toChar())
        }
    }

    private fun writeAll(output: OutputStream, text: String) {
        output.write(text.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
        }
    }

    private class BandwidthLimiter(initialBytesPerSecond: Long) {
        @Volatile
        var bytesPerSecond: Long = initialBytesPerSecond
            set(value) {
                field = value
                synchronized(this) { nextAvailableNanos = System.nanoTime() }
            }

        private var nextAvailableNanos = System.nanoTime()

        fun acquire(bytes: Int) {
            val rate = bytesPerSecond
            if (rate <= 0 || bytes <= 0) return

            val now = System.nanoTime()
            val waitUntil: Long
            synchronized(this) {
                val start = maxOf(now, nextAvailableNanos)
                waitUntil = start + bytes * 1_000_000_000L / rate
                nextAvailableNanos = waitUntil
            }

            val sleepNanos = waitUntil - System.nanoTime()
            if (sleepNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(sleepNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}

private fun mbpsToBytesPerSecond(mbps: Int): Long =
    if (mbps <= 0) 0L else mbps * 1_000_000L / 8L
