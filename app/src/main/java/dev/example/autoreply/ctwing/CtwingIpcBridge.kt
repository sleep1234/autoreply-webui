package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/**
 * Cross-process IPC bridge between WeChat's main process (message handler)
 * and tools process (X5 WebView where CTWing H5 runs).
 *
 * Protocol: shared files under WeChat's data dir (accessible by all
 * processes of the same app).
 *
 *   REQUEST:  /data/data/com.tencent.mm/files/ctwing_req.json
 *   RESPONSE: /data/data/com.tencent.mm/files/ctwing_resp.json
 *
 * Main process writes a REQUEST → tools process reads it, executes JS,
 * writes RESPONSE → main process reads it.
 *
 * Locking: FileLock on REQUEST to serialize writes (only main process
 * writes requests; tools process only reads and writes response).
 */
object CtwingIpcBridge {

    private const val TAG = "[CTWing-IPC]"

    /** Must be set before any IPC calls (from handleLoadPackage dataDir). */
    @Volatile
    var wechatDataDir: String? = null

    private fun reqFile(): File = File(wechatDataDir, "files/ctwing_req.json")
    private fun respFile(): File = File(wechatDataDir, "files/ctwing_resp.json")

    // ----------------------------------------------------------------
    //  Main-process side: WRITE request, READ response
    // ----------------------------------------------------------------

    /**
     * Post a request from the main process. Returns the request id so
     * the caller can poll [readResponse].
     */
    fun postRequest(json: String): Boolean {
        return runCatching {
            val f = reqFile()
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    raf.setLength(0)
                    raf.writeBytes(json)
                } finally {
                    lock.release()
                }
            }
            XposedBridge.log("$TAG [req-write] len=${json.length}")
            true
        }.getOrDefault(false)
    }

    /**
     * Poll for a response from the tools process. Returns null if no
     * response yet, the response JSON string otherwise.
     * After reading, the response file is *not* deleted — the caller
     * should call [clearResponse] when done.
     */
    fun readResponse(): String? {
        return runCatching {
            val f = respFile()
            if (!f.exists() || f.length() == 0L) return null
            RandomAccessFile(f, "r").use { raf ->
                val lock = raf.channel.lock(0L, Long.MAX_VALUE, true)
                try {
                    val bytes = ByteArray(f.length().toInt())
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                } finally {
                    lock.release()
                }
            }
        }.getOrNull()
    }

    fun clearResponse() {
        runCatching { respFile().delete() }
    }

    // ----------------------------------------------------------------
    //  Tools-process side: READ request, WRITE response
    // ----------------------------------------------------------------

    /**
     * Poll for a request in the tools process. Returns null if no
     * request pending, the request JSON string otherwise.
     */
    fun readRequest(): String? {
        return runCatching {
            val f = reqFile()
            if (!f.exists() || f.length() == 0L) return null
            RandomAccessFile(f, "r").use { raf ->
                val lock = raf.channel.lock(0L, Long.MAX_VALUE, true)
                try {
                    val bytes = ByteArray(f.length().toInt())
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                } finally {
                    lock.release()
                }
            }
        }.getOrNull()
    }

    fun clearRequest() {
        runCatching { reqFile().delete() }
    }

    /**
     * Write a response from the tools process.
     */
    fun postResponse(json: String): Boolean {
        return runCatching {
            val f = respFile()
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    raf.setLength(0)
                    raf.writeBytes(json)
                } finally {
                    lock.release()
                }
            }
            XposedBridge.log("$TAG [resp-write] len=${json.length}")
            true
        }.getOrDefault(false)
    }
}