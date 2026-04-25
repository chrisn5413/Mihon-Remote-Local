package eu.kanade.tachiyomi.extension.remotelibrary.util

import android.util.Log
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder

private const val TAG = "LocalPageServer"

/**
 * Minimal HTTP/1.0 server that serves cached page images over loopback.
 *
 * Why this exists:
 * Mihon's HttpPageLoader uses OkHttp to fetch page images from URLs returned by
 * fetchPageList(). OkHttp only handles http:// and https:// schemes — returning
 * file:// URIs would fail before the request even starts. By serving files over a
 * real TCP socket on 127.0.0.1, Mihon's own network client can fetch them normally
 * with zero actual network traffic (loopback never leaves the device).
 *
 * URL format: http://127.0.0.1:{port}/cache/{relative-path-from-baseDir}
 *
 * Uses only java.net.ServerSocket — no OkHttp, no third-party dependencies,
 * no classloader interop concerns.
 */
class LocalPageServer(private val baseDir: File) {

    private var serverSocket: ServerSocket? = null

    /** The OS-assigned port. Valid only after [start] is called. */
    val port: Int get() = serverSocket?.localPort ?: 0

    fun start() {
        if (serverSocket?.isClosed == false) return
        // Use a fixed port so thumbnail_url values stored in Mihon's database remain valid
        // across app restarts. Port 45678 is in the ephemeral range and unlikely to conflict;
        // if it's already bound (e.g. two extension processes), fall back to OS-assigned port.
        val ss = try { ServerSocket(45678) } catch (_: Exception) { ServerSocket(0) }
        serverSocket = ss
        Thread {
            Log.d(TAG, "Listening on port ${ss.localPort} serving from ${baseDir.absolutePath}")
            while (!ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    Thread { serve(clientSocket) }.apply {
                        isDaemon = true
                        name = "LocalPageServer-worker"
                    }.start()
                } catch (_: Exception) {
                    // ServerSocket.close() throws here — that's the normal shutdown path.
                }
            }
            Log.d(TAG, "Server stopped")
        }.apply {
            isDaemon = true
            name = "LocalPageServer"
        }.start()
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun serve(socket: java.net.Socket) {
        try {
            val input = socket.getInputStream().bufferedReader()
            val output = socket.getOutputStream()

            // Read the request line, e.g. "GET /cache/mihon-remote/reading/s/c/001.jpg HTTP/1.1"
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                output.write("HTTP/1.0 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            // Drain remaining request headers so the client doesn't get a connection reset.
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }

            // Strip the "/cache/" prefix to get the relative path inside baseDir.
            // OkHttp percent-encodes filenames in the URL path — decode them so the
            // resulting path matches what's actually on disk.
            val rawPath = parts[1]
            val decodedPath = URLDecoder.decode(rawPath, "UTF-8")
            val relPath = decodedPath.removePrefix("/cache/")
            val file = File(baseDir, relPath)

            // Basic path-traversal guard.
            val fileCanon = file.canonicalPath
            val baseDirCanon = baseDir.canonicalPath
            val pathOk = fileCanon.startsWith(baseDirCanon + "/") || fileCanon == baseDirCanon

            Log.d(TAG, "GET $decodedPath → ${file.absolutePath} exists=${file.exists()} pathOk=$pathOk")

            if (!file.exists() || !file.isFile || !pathOk) {
                Log.w(TAG, "404: file=${file.absolutePath} exists=${file.exists()} isFile=${file.isFile} pathOk=$pathOk")
                output.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val bytes = file.readBytes()
            // Infer a rough MIME type from the extension so image decoders aren't confused.
            val mime = when (file.extension.lowercase()) {
                "png"  -> "image/png"
                "webp" -> "image/webp"
                "gif"  -> "image/gif"
                else   -> "image/jpeg"
            }
            output.write(
                "HTTP/1.0 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\n\r\n"
                    .toByteArray(),
            )
            output.write(bytes)
            output.flush()
            Log.d(TAG, "200 served ${bytes.size} bytes for ${file.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Error serving request: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }
}
