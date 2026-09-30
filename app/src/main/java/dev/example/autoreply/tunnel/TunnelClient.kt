package dev.example.autoreply.tunnel

import de.robv.android.xposed.XposedBridge
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TunnelClient(
    private val serverAddr: String,
    private val serverPort: Int,
    private val authToken: String,
    private val remotePort: Int,
    private val localPort: Int,
) {
    private var running = false

    @Volatile
    private var connected = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "tunnel").apply { isDaemon = true }
        thread!!.start()
    }

    fun stop() {
        running = false
        connected = false
        thread?.interrupt()
    }

    fun isConnected(): Boolean = connected

    private fun runLoop() {
        while (running) {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(serverAddr, serverPort), 10_000)
                sock.soTimeout = 35_000

                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // AUTH
                writeLine(output, "AUTH $authToken")
                val authResp = readLine(input)
                if (authResp != "AUTH_OK") { XposedBridge.log("[Tunnel] auth failed: $authResp"); sock.close(); Thread.sleep(5000L); continue }

                // REGISTER
                writeLine(output, "REGISTER $remotePort")
                val reg = readLine(input)
                if (reg == null || !reg.startsWith("REGISTER_OK")) { XposedBridge.log("[Tunnel] register failed: $reg"); sock.close(); Thread.sleep(5000L); continue }

                connected = true
                XposedBridge.log("[Tunnel] connected remote=$remotePort")

                // Main loop
                var buf = ByteArray(0)
                while (running) {
                    val chunk = ByteArray(4096)
                    val n = try { input.read(chunk) } catch (_: Exception) { -1 }
                    if (n <= 0) break
                    buf += chunk.copyOf(n)
                    while (true) {
                        val nl = buf.indexOf('\n'.code.toByte())
                        if (nl < 0) break
                        val line = String(buf, 0, nl, Charsets.UTF_8).trim()
                        buf = buf.copyOfRange(nl + 1, buf.size)
                        when {
                            line.startsWith("NEWCONN ") -> {
                                val connId = line.removePrefix("NEWCONN ").trim()
                                Thread({ openDataConnection(connId) }, "tunnel-data").apply { isDaemon = true }.start()
                            }
                            line == "PING" -> try { writeLine(output, "PONG") } catch (_: Exception) {}
                        }
                    }
                }
                connected = false
                try { sock.close() } catch (_: Exception) {}
                XposedBridge.log("[Tunnel] control connection closed")
            } catch (e: Exception) {
                connected = false
                XposedBridge.log("[Tunnel] control error: ${e.message}")
            }
            if (running) Thread.sleep(5000L)
        }
    }

    private fun openDataConnection(connId: String) {
        try {
            val data = Socket(); data.tcpNoDelay = true
            data.connect(InetSocketAddress(serverAddr, serverPort), 10_000)
            writeLine(data.getOutputStream(), "CONN $connId")
            val local = Socket(); local.tcpNoDelay = true
            local.connect(InetSocketAddress("127.0.0.1", localPort), 5_000)
            bridge(data, local)
        } catch (_: Exception) {}
    }

    private fun bridge(a: Socket, b: Socket) {
        val t1 = Thread({ pipe(a.getInputStream(), b.getOutputStream()) }, "pipe-a").apply { isDaemon = true }
        val t2 = Thread({ pipe(b.getInputStream(), a.getOutputStream()) }, "pipe-b").apply { isDaemon = true }
        t1.start(); t2.start()
        try { t1.join(); t2.join() } catch (_: Exception) {}
        try { a.close() } catch (_: Exception) {}
        try { b.close() } catch (_: Exception) {}
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n <= 0) break; output.write(buf, 0, n); output.flush() }
        } catch (_: Exception) {}
    }

    private fun writeLine(out: OutputStream, line: String) {
        out.write((line + "\n").toByteArray(Charsets.UTF_8)); out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var ch = input.read()
        while (ch >= 0) { if (ch == '\n'.code) return sb.toString(); sb.append(ch.toChar()); ch = input.read() }
        return if (sb.isEmpty()) null else sb.toString()
    }
}