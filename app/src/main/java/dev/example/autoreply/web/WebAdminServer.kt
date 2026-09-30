package dev.example.autoreply.web

import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.ctwing.CtwingFacade
import dev.example.autoreply.ctwing.CtwingKeywordRouter
import dev.example.autoreply.ctwing.CtwingWebViewHook
import dev.example.autoreply.ctwing.NativeHttp
import dev.example.autoreply.hook.TinkerGuard
import dev.example.autoreply.ui.WhitelistStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 嵌入式 Web 管理后台（ServerSocket，端口 60080）。
 *
 * -- 认证 --
 * 账号系统：JSON 文件存储（AccountStore），admin 可创建/删除用户，普通用户只能改自己密码。
 * Session：token 5 分钟超时，会话绑定用户名和角色。
 *
 * -- API --
 * POST /api/login {username,password}           -- 登录
 * POST /api/status                              -- 服务状态（需登录）
 * POST /api/query {iccid}                       -- 查询
 * POST /api/diagnose {iccid}                    -- 诊断
 * POST /api/rebind {iccid}                      -- 重绑
 * POST /api/renew                               -- 续期
 * POST /api/ocr     (multipart: image)          -- AI 识图 ICCID
 * POST /api/accounts/list                       -- 列出所有账户（admin only）
 * POST /api/accounts/add {username,password,role} -- 添加账户（admin only）
 * POST /api/accounts/delete {username}          -- 删除账户（admin only）
 * POST /api/accounts/changepw {oldPw,newPw}     -- 改自己密码
 * POST /api/accounts/resetpw {username,newPw}   -- 管理员重置密码
 */
object WebAdminServer {

    private const val TAG = "[WebAdmin]"

    private var dataDir: File? = null
    private var port: Int = 60080

    // session token → SessionInfo
    private data class SessionInfo(val username: String, val role: String, val createdAt: Long)
    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private const val SESSION_TTL_MS = 5 * 60 * 1000L

    private var serverJob: Job? = null

    fun start(appDataDir: String, userPort: Int) {
        if (serverJob?.isActive == true) return
        dataDir = File(appDataDir, "files/autoreply").also { it.mkdirs() }
        port = userPort
        AccountStore.init(dataDir!!)

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            var server: ServerSocket? = null
            for (attempt in 0 until 5) {
                try { server = ServerSocket(port + attempt).apply { reuseAddress = true }; port += attempt; break }
                catch (_: Exception) { if (attempt == 4) return@launch }
            }
            val srv = server ?: return@launch
            XposedBridge.log("$TAG HTTP 服务已启动，端口 $port（账号系统已初始化）")
            try { while (isActive) { runCatching { srv.accept() }.getOrNull()?.let { launch { handle(it) } } } }
            catch (e: Exception) { XposedBridge.log("$TAG 服务异常: ${e.message}") }
        }
    }

    fun stop() { serverJob?.cancel(); serverJob = null }

    // ---- 客户端 ----

    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val rawInput = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            // 手动按字节读请求行（避免 BufferedReader 缓冲预读吞掉 body）
            val reqLineBytes = readLineBytes(rawInput) ?: return
            val reqLine = String(reqLineBytes, Charsets.ISO_8859_1)
            XposedBridge.log("$TAG 请求: $reqLine")
            val parts = reqLine.split(" "); if (parts.size < 2) return
            val method = parts[0]; val rawPath = parts[1]

            // 解析 headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            var contentType = ""
            while (true) {
                val lb = readLineBytes(rawInput) ?: break
                if (lb.isEmpty()) break
                val l = String(lb, Charsets.ISO_8859_1)
                val c = l.indexOf(": "); if (c <= 0) continue
                val k = l.substring(0, c).lowercase()
                headers[k] = l.substring(c + 2)
                if (k == "content-length") contentLength = l.substring(c + 2).toIntOrNull() ?: 0
                if (k == "content-type") contentType = l.substring(c + 2)
            }

            // 读原始 body 字节（同一 BufferedInputStream，无缓冲冲突）
            val bodyBytes = if (contentLength > 0) {
                val b = ByteArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val r = rawInput.read(b, total, contentLength - total)
                    if (r <= 0) break
                    total += r
                }
                b.copyOf(total)
            } else ByteArray(0)

            val qi = rawPath.indexOf("?"); val path = if (qi >= 0) rawPath.substring(0, qi) else rawPath
            val qs = if (qi >= 0) rawPath.substring(qi + 1) else ""

            // 解析 multipart/form-data 或 URL-encoded body
            val bodyStr = if (bodyBytes.isNotEmpty()) String(bodyBytes, Charsets.UTF_8) else ""
            val params = parseQuery(qs)
            val bodyParams = if (contentType.contains("multipart/form-data")) emptyMap()
                             else if (bodyStr.isNotBlank()) parseQuery(bodyStr)
                             else emptyMap()

            val uploadedFile = extractMultipartFile(bodyBytes, contentType) ?: ByteArray(0)

            val (code, ct, resp) = route(method, path, params, bodyParams, headers, uploadedFile)
            val respBytes = resp.toByteArray(Charsets.UTF_8)
            val st = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "OK" }
            val head = buildString {
                append("HTTP/1.1 $code $st\r\n"); append("Content-Type: $ct\r\n")
                append("Content-Length: ${respBytes.size}\r\n"); append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            output.write(head); output.write(respBytes); output.flush()
            runCatching { socket.shutdownOutput() }
            XposedBridge.log("$TAG 响应: $code (${respBytes.size}B)")
        } catch (e: Exception) {
            XposedBridge.log("$TAG handle error: ${e.message}")
        } finally {
            try { Thread.sleep(200) } catch (_: Exception) {}
            runCatching { socket.close() }
        }
    }

    /** 从 InputStream 逐字节读取一行（以 \n 结尾，去掉尾部 \r），不预读多余字节。 */
    private fun readLineBytes(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toByteArray()
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                // 去掉尾部 \r
                return if (arr.isNotEmpty() && arr.last() == '\r'.code.toByte()) arr.copyOf(arr.size - 1) else arr
            }
            buf.write(b)
        }
    }

    // ---- 路由 ----

    private suspend fun route(
        m: String, p: String,
        q: Map<String, String>, b: Map<String, String>,
        h: Map<String, String>,
        uploaded: ByteArray
    ): Triple<Int, String, String> = when {
        p == "/" || p == "/index.html" -> Triple(200, "text/html; charset=utf-8", HTML)
        p == "/api/login" && m == "POST" -> login(b)
        // 以下全需登录
        p == "/api/status" && m == "POST" -> auth(h) { _ -> status() }
        p == "/api/query" && m == "POST" -> auth(h) { _ -> query(b) }
        p == "/api/diagnose" && m == "POST" -> auth(h) { _ -> diagnose(b) }
        p == "/api/rebind" && m == "POST" -> auth(h) { _ -> rebind(b) }
        p == "/api/renew" && m == "POST" -> auth(h) { _ -> renew() }
        p == "/api/ocr" && m == "POST" -> auth(h) { _ -> ocr(uploaded) }
        p == "/api/accounts/list" && m == "POST" -> auth(h) { sess -> accountsList(sess) }
        p == "/api/accounts/add" && m == "POST" -> auth(h) { sess -> accountsAdd(sess, b) }
        p == "/api/accounts/delete" && m == "POST" -> auth(h) { sess -> accountsDelete(sess, b) }
        p == "/api/accounts/changepw" && m == "POST" -> auth(h) { sess -> accountsChangePw(sess, b) }
        p == "/api/accounts/resetpw" && m == "POST" -> auth(h) { sess -> accountsResetPw(sess, b) }
        p == "/api/selfcheck" && m == "POST" -> auth(h) { _ -> selfCheck() }
        else -> Triple(404, "text/plain", "Not Found")
    }

    // session → auth block
    private fun verifySession(h: Map<String, String>): SessionInfo? {
        val tok = (h["cookie"] ?: "").split("; ")
            .firstOrNull { it.startsWith("session=") }?.removePrefix("session=") ?: return null
        val si = sessions[tok] ?: return null
        if (System.currentTimeMillis() - si.createdAt > SESSION_TTL_MS) { sessions.remove(tok); return null }
        return si
    }

    private suspend fun auth(
        h: Map<String, String>,
        act: suspend (SessionInfo) -> Triple<Int, String, String>
    ): Triple<Int, String, String> {
        val si = verifySession(h) ?: return Triple(401, "application/json", """{"ok":false,"msg":"未登录"}""")
        return act(si)
    }

    // ---- Auth API ----

    private fun login(p: Map<String, String>): Triple<Int, String, String> {
        val username = p["username"] ?: ""
        val password = p["password"] ?: ""
        val acc = AccountStore.authenticate(username, password)
            ?: return Triple(401, "application/json", """{"ok":false,"msg":"用户名或密码错误"}""")
        val tok = rnd()
        sessions[tok] = SessionInfo(acc.username, acc.role, System.currentTimeMillis())
        val json = org.json.JSONObject().apply {
            put("ok", true)
            put("token", tok)
            put("username", acc.username)
            put("role", acc.role)
        }
        return Triple(200, "application/json; charset=utf-8", json.toString())
    }

    // ---- CTWing API（复用窗口端函数） ----

    private fun status(): Triple<Int, String, String> {
        val j = org.json.JSONObject().apply {
            put("ok", true); put("webViewAlive", CtwingWebViewHook.currentWebView() != null)
            put("token", NativeHttp.cachedToken ?: ""); put("tokenLen", (NativeHttp.cachedToken ?: "").length)
            put("whitelistCount", WhitelistStore.list().size); put("tinkerSummary", TinkerGuard.statusSummary())
        }
        return Triple(200, "application/json; charset=utf-8", j.toString())
    }

    private suspend fun query(p: Map<String, String>): Triple<Int, String, String> {
        val iccid = p["iccid"] ?: return err("请提供卡号")
        return try {
            val result = CtwingFacade.webViewMutex.withLock {
                val idType = CtwingKeywordRouter.inferType(iccid)
                val raw = CtwingKeywordRouter.nativeGetWithRetry("web-query") { NativeHttp.queryCard(it, idType, iccid) }
                val em = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
                if (em != null) "⚠️ 查询失败：$em"
                else {
                    val best = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
                    best?.let { CtwingKeywordRouter.formatCardInfo(it) } ?: "⚠️ 查询未完成，请稍后重试或发送「续期」刷新登录态"
                }
            }
            ok(result)
        } catch (e: Exception) { err("查询失败: ${e.message}") }
    }

    private suspend fun diagnose(p: Map<String, String>): Triple<Int, String, String> {
        val iccid = p["iccid"] ?: return err("请提供卡号")
        return try {
            val result = CtwingFacade.webViewMutex.withLock {
                val idType = CtwingKeywordRouter.inferType(iccid)
                val raw = CtwingKeywordRouter.nativeGetWithRetry("web-diagnose") { NativeHttp.diagnose(it, idType, iccid) }
                val em = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
                if (em != null) "⚠️ 诊断失败：$em"
                else {
                    val best = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
                    best?.let { CtwingKeywordRouter.formatDiagnosis(it) } ?: "⚠️ 诊断未完成，请稍后重试或发送「续期」刷新登录态"
                }
            }
            ok(result)
        } catch (e: Exception) { err("诊断失败: ${e.message}") }
    }

    private suspend fun rebind(p: Map<String, String>): Triple<Int, String, String> {
        val iccid = p["iccid"] ?: return err("请提供卡号")
        return try {
            val result = CtwingFacade.webViewMutex.withLock {
                CtwingKeywordRouter.executeRebind(iccid)
            }
            ok("$result\n${CtwingKeywordRouter.idTypeLabel(iccid)}：$iccid")
        } catch (e: Exception) { err("重绑失败: ${e.message}") }
    }

    private suspend fun renew(): Triple<Int, String, String> {
        return try {
            val old = NativeHttp.cachedToken ?: ""
            val nt = CtwingFacade.webViewMutex.withLock {
                CtwingFacade.forceRebuild(40_000L)
                CtwingFacade.pullToken()
                NativeHttp.cachedToken ?: ""
            }
            val json = org.json.JSONObject().apply {
                put("ok", true); put("oldToken", old); put("newToken", nt); put("changed", old != nt)
            }
            Triple(200, "application/json; charset=utf-8", json.toString())
        } catch (e: Exception) { err("续期失败: ${e.message}") }
    }

    // ---- AI 识图 ----

    private suspend fun ocr(uploaded: ByteArray): Triple<Int, String, String> {
        if (uploaded.isEmpty()) return err("请上传图片")
        return try {
            val iccid = withContext(Dispatchers.IO) { AgnesAiClient.extractIccid(uploaded) }
            if (iccid != null) ok(iccid)
            else err("未识别到 ICCID，请确认图片清晰且包含完整的 19-20 位卡号")
        } catch (e: Exception) { err("AI 识别失败: ${e.message}") }
    }

    // ---- 自检：用真实 token 发查询 + 诊断，验证 token 实际可用 ----

    private suspend fun selfCheck(): Triple<Int, String, String> {
        val testIccid = "8986032548200686692"
        return try {
            CtwingFacade.webViewMutex.withLock {
                CtwingFacade.pullTokenOrRebuild()
                val token = NativeHttp.cachedToken ?: ""
                if (token.isBlank()) return@withLock Triple(200, "application/json; charset=utf-8",
                    """{"ok":true,"status":"error","token":"","summary":"Token 为空，请先续期","queryResult":"未执行","diagnoseResult":"未执行"}""")

                val idType = CtwingKeywordRouter.inferType(testIccid)

                // 查询
                val queryRaw = runCatching {
                    withContext(Dispatchers.IO) { NativeHttp.queryCard(token, idType, testIccid) }
                }.getOrElse { e -> "FAILED: ${e.message}" }

                // 诊断
                val diagRaw = runCatching {
                    withContext(Dispatchers.IO) { NativeHttp.diagnose(token, idType, testIccid) }
                }.getOrElse { e -> "FAILED: ${e.message}" }

                val queryOk = queryRaw.contains("\"code\":0")
                val diagOk = diagRaw.contains("\"code\":0")
                val allOk = queryOk && diagOk

                val summary = when {
                    allOk -> "✅ Token 正常，查询和诊断均通过"
                    queryOk -> "⚠️ 查询通过，诊断异常"
                    diagOk -> "⚠️ 查询异常，诊断通过"
                    else -> "❌ Token 可能已失效，请续期"
                }

                val sb = StringBuilder()
                sb.appendLine(summary)
                sb.appendLine("· Token：${token.take(12)}…（${token.length} 字符）")
                sb.appendLine("· 查询：${if (queryOk) "✅ 通过" else "❌ 失败"}")
                if (!queryOk) sb.appendLine("  ${queryRaw.take(120)}")
                sb.appendLine("· 诊断：${if (diagOk) "✅ 通过" else "❌ 失败"}")
                if (!diagOk) sb.appendLine("  ${diagRaw.take(120)}")

                val json = org.json.JSONObject().apply {
                    put("ok", true)
                    put("status", if (allOk) "ok" else if (queryOk || diagOk) "partial" else "error")
                    put("token", token)
                    put("tokenLen", token.length)
                    put("summary", summary)
                    put("queryResult", if (queryOk) "通过" else queryRaw.take(200))
                    put("diagnoseResult", if (diagOk) "通过" else diagRaw.take(200))
                    put("detail", sb.toString())
                }
                Triple(200, "application/json; charset=utf-8", json.toString())
            }
        } catch (e: Exception) {
            val json = org.json.JSONObject().apply {
                put("ok", true)
                put("status", "error")
                put("summary", "自检执行异常：${e.message}")
            }
            Triple(200, "application/json; charset=utf-8", json.toString())
        }
    }

    // ---- 账号管理 API ----

    private fun accountsList(sess: SessionInfo): Triple<Int, String, String> {
        if (sess.role != "admin") return Triple(403, "application/json", """{"ok":false,"msg":"仅管理员可操作"}""")
        val arr = org.json.JSONArray()
        AccountStore.list().forEach { arr.put(org.json.JSONObject(it)) }
        val json = org.json.JSONObject().apply { put("ok", true); put("accounts", arr) }
        return Triple(200, "application/json; charset=utf-8", json.toString())
    }

    private fun accountsAdd(sess: SessionInfo, p: Map<String, String>): Triple<Int, String, String> {
        if (sess.role != "admin") return Triple(403, "application/json", """{"ok":false,"msg":"仅管理员可操作"}""")
        val username = p["username"] ?: ""; val password = p["password"] ?: ""; val role = p["role"] ?: "user"
        val actual = AccountStore.authenticate(sess.username, p["adminPassword"] ?: "")
            ?: return err("管理员密码错误")
        if (actual.role != "admin") return err("权限不足")
        val err = AccountStore.add(actual, username, password, role)
        return if (err != null) Triple(400, "application/json", """{"ok":false,"msg":${jsonQuote(err)}}""")
        else Triple(200, "application/json", """{"ok":true}""")
    }

    private fun accountsDelete(sess: SessionInfo, p: Map<String, String>): Triple<Int, String, String> {
        if (sess.role != "admin") return Triple(403, "application/json", """{"ok":false,"msg":"仅管理员可操作"}""")
        val username = p["username"] ?: return err("请提供用户名")
        val acc = AccountStore.authenticate(sess.username, p["adminPassword"] ?: "")
            ?: return err("管理员密码错误")
        val err = AccountStore.delete(acc, username)
        return if (err != null) Triple(400, "application/json", """{"ok":false,"msg":${jsonQuote(err)}}""")
        else Triple(200, "application/json", """{"ok":true}""")
    }

    private fun accountsChangePw(sess: SessionInfo, p: Map<String, String>): Triple<Int, String, String> {
        val oldPw = p["oldPassword"] ?: return err("请提供旧密码")
        val newPw = p["newPassword"] ?: return err("请提供新密码")
        val acc = AccountStore.authenticate(sess.username, oldPw)
            ?: return err("旧密码错误")
        val err = AccountStore.changePassword(acc, oldPw, newPw)
        return if (err != null) err(err) else Triple(200, "application/json", """{"ok":true}""")
    }

    private fun accountsResetPw(sess: SessionInfo, p: Map<String, String>): Triple<Int, String, String> {
        if (sess.role != "admin") return Triple(403, "application/json", """{"ok":false,"msg":"仅管理员可操作"}""")
        val username = p["username"] ?: return err("请提供用户名")
        val newPw = p["newPassword"] ?: return err("请提供新密码")
        val acc = AccountStore.authenticate(sess.username, p["adminPassword"] ?: "")
            ?: return err("管理员密码错误")
        val err = AccountStore.resetPassword(acc, username, newPw)
        return if (err != null) err(err) else Triple(200, "application/json", """{"ok":true}""")
    }

    // ---- 工具 ----

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val eq = it.indexOf("=")
            if (eq > 0) URLDecoder.decode(it.substring(0, eq), "UTF-8") to URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            else null
        }.toMap()
    }

    /**
     * 解析 multipart/form-data 中的文件部分，返回文件字节数组。
     * 纯字节操作：直接搜 boundary 和 \r\n\r\n，避免 String 转换破坏二进制。
     */
    private fun extractMultipartFile(bodyBytes: ByteArray, contentType: String): ByteArray? {
        if (!contentType.contains("multipart/form-data")) return null
        val bm = Regex("boundary=(\".+\"|[^\";]+)").find(contentType) ?: return null
        var boundary = bm.groupValues[1].trim().trim('"')
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val crlfcrlf = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val crlf = "\r\n".toByteArray(Charsets.ISO_8859_1)

        var pos = indexOf(bodyBytes, delimiter, 0)
        while (pos >= 0) {
            // 这个 part 的 header 起点
            val headerStart = pos + delimiter.size
            // header 结束（\r\n\r\n）
            val headerEnd = indexOf(bodyBytes, crlfcrlf, headerStart)
            if (headerEnd < 0) return null
            val header = String(bodyBytes, headerStart, headerEnd - headerStart, Charsets.ISO_8859_1)
            val contentStart = headerEnd + 4

            if (header.contains("filename=\"")) {
                // 找这个 part 的结束 boundary（"--boundary" 前缀）
                val endMarker = indexOf(bodyBytes, delimiter, contentStart)
                // 文件内容在 contentStart 到 endMarker-2（去掉 \r\n）之间
                val contentEnd = if (endMarker >= 0) endMarker - 2 else bodyBytes.size
                val len = maxOf(0, contentEnd - contentStart)
                return bodyBytes.copyOfRange(contentStart, contentStart + len)
            }
            // 非文件 part（普通字段），跳到下一个 boundary
            val next = indexOf(bodyBytes, delimiter, contentStart)
            if (next < 0) return null
            pos = next
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun err(msg: String) = Triple(400, "application/json; charset=utf-8", """{"ok":false,"msg":${jsonQuote(msg)}}""")
    private fun ok(r: String) = Triple(200, "application/json; charset=utf-8", """{"ok":true,"result":${jsonQuote(r)}}""")
    private fun jsonQuote(s: String) = org.json.JSONObject.quote(s)
    private fun rnd() = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    // ---- HTML ----

    private val HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>天翼物联一站式服务工具</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f5f7fa; color: #333; min-height: 100vh; }
  .container { max-width: 520px; margin: 0 auto; padding: 20px; }
  .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 24px; border-radius: 16px; margin-bottom: 20px; text-align: center; }
  .header h1 { font-size: 20px; font-weight: 600; }
  .header .status { font-size: 12px; opacity: 0.85; margin-top: 8px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
  .header .status span { background: rgba(255,255,255,0.2); padding: 3px 10px; border-radius: 10px; }
  .card { background: white; border-radius: 14px; padding: 20px; margin-bottom: 14px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
  .card h2 { font-size: 15px; font-weight: 600; margin-bottom: 14px; color: #555; }
  input[type="text"], input[type="password"], input[type="file"] { width: 100%; padding: 12px 14px; border: 1.5px solid #e0e4ea; border-radius: 10px; font-size: 15px; outline: none; transition: border .2s; }
  input:focus { border-color: #667eea; }
  .btn-row { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
  .btn { flex: 1; min-width: 80px; padding: 11px 14px; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all .2s; text-align: center; }
  .btn:active { transform: scale(0.96); }
  .btn-query { background: #e8f0fe; color: #1967d2; }
  .btn-diag { background: #fce8e6; color: #c5221f; }
  .btn-rebind { background: #fef7e0; color: #ea8600; }
  .btn-renew { background: linear-gradient(135deg, #667eea, #764ba2); color: white; }
  .btn-ocr { background: #e6f4ea; color: #137333; }
  .btn-danger { background: #fce8e6; color: #c5221f; }
  .btn-sm { padding: 8px 12px; font-size: 13px; flex: none; min-width: 60px; border-radius: 8px; border: none; cursor: pointer; font-weight: 600; }
  .btn:disabled { opacity: 0.5; pointer-events: none; }
  .result { margin-top: 12px; padding: 12px; border-radius: 10px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; display: none; }
  .result.show { display: block; }
  .result.ok { background: #e6f4ea; color: #137333; }
  .result.err { background: #fce8e6; color: #c5221f; }
  .result.info { background: #e8f0fe; color: #1967d2; }
  .login-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 100; }
  .login-box { background: white; border-radius: 16px; padding: 30px; width: 340px; box-shadow: 0 10px 40px rgba(0,0,0,0.15); }
  .login-box h2 { font-size: 18px; margin-bottom: 18px; }
  .login-box label { font-size: 13px; color: #666; margin-bottom: 4px; display: block; margin-top: 10px; }
  .tabs { display: flex; gap: 8px; margin-bottom: 16px; }
  .tab { padding: 8px 16px; border-radius: 8px; font-size: 13px; cursor: pointer; background: #eee; border: none; }
  .tab.active { background: #667eea; color: white; }
  .tab-content { display: none; }
  .tab-content.active { display: block; }
  .toast { position: fixed; top: 20px; left: 50%; transform: translateX(-50%); background: #333; color: white; padding: 10px 20px; border-radius: 8px; font-size: 13px; z-index: 200; display: none; }
  .toast.show { display: block; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th,td { padding: 8px 10px; text-align: left; border-bottom: 1px solid #e0e4ea; }
  .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 150; display: none; }
  .modal-overlay.show { display: flex; }
  .modal-box { background: white; border-radius: 14px; padding: 24px; width: 340px; }
  .modal-box h3 { margin-bottom: 14px; }
</style>
</head>
<body>
<div class="login-overlay" id="loginOverlay">
  <div class="login-box">
    <h2>🔐 登录</h2>
    <label>用户名</label>
    <input type="text" id="loginUser" placeholder="请输入用户名" onkeydown="if(event.key==='Enter')document.getElementById('loginPw').focus()">
    <label>密码</label>
    <input type="password" id="loginPw" placeholder="请输入密码" onkeydown="if(event.key==='Enter')login()">
    <div class="btn-row" style="margin-top:16px;"><button class="btn btn-renew" onclick="login()" style="flex:1;">登录</button></div>
    <div class="result err" id="loginError"></div>
  </div>
</div>
<div class="toast" id="toast"></div>
<div class="modal-overlay" id="modalOverlay">
  <div class="modal-box" id="modalContent"></div>
</div>
<div class="container">
  <div class="header">
    <h1>🛰️ 天翼物联一站式服务工具</h1>
    <div class="status" id="statusBar"><span>加载中...</span></div>
    <div style="margin-top:8px;font-size:12px;opacity:0.7;" id="userInfo"></div>
    <div style="margin-top:8px;"><button class="btn btn-renew btn-sm" onclick="showAccountPanel()" style="background:rgba(255,255,255,0.25);color:white;">👤 账号管理</button></div>
  </div>

  <div class="card">
    <h2>🔑 一键续期</h2>
    <div class="btn-row">
      <button class="btn btn-renew" onclick="doRenew()" id="btnRenew">🔄 强制刷新登录态</button>
      <button class="btn btn-query" onclick="doSelfCheck()" id="btnSelfCheck">🩺 自检 Token</button>
    </div>
    <div class="result" id="renewResult"></div>
  </div>

  <div class="card">
    <h2>📋 物联卡操作</h2>
    <input type="text" id="iccid" placeholder="输入 ICCID 或 接入号" onkeydown="if(event.key==='Enter')doQuery()">
    <div class="btn-row">
      <button class="btn btn-query" onclick="doQuery()">🔍 查询</button>
      <button class="btn btn-diag" onclick="doDiagnose()">🩺 诊断</button>
      <button class="btn btn-rebind" onclick="doRebind()">🔄 重绑</button>
    </div>
    <div class="result" id="opResult"></div>
  </div>

  <div class="card">
    <h2>📷 AI 识图</h2>
    <input type="file" id="imageFile" accept="image/*" onchange="previewImage(this)" style="margin-bottom:8px;">
    <div class="btn-row">
      <button class="btn btn-ocr" onclick="doOcr()">🤖 识别 ICCID</button>
      <button class="btn btn-query btn-sm" onclick="fillFromOcr()">📋 填入查询</button>
    </div>
    <div class="result" id="ocrResult"></div>
  </div>
</div>

<script>
var sessionToken='', username='', role='';
(function init(){
  var c=document.cookie.split('; ').find(function(r){return r.startsWith('session=')});
  if(c){ sessionToken=c.split('=')[1]; checkSession(); }
  else document.getElementById('loginOverlay').style.display='flex';
})();

function login(){
  var user=document.getElementById('loginUser').value.trim();
  var pw=document.getElementById('loginPw').value;
  if(!user||!pw){ document.getElementById('loginError').textContent='请输入用户名和密码'; document.getElementById('loginError').className='result err show'; return; }
  fetch('/api/login',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username='+encodeURIComponent(user)+'&password='+encodeURIComponent(pw)})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){
      sessionToken=d.token; username=d.username; role=d.role;
      document.cookie='session='+d.token+'; path=/; max-age=3600';
      document.getElementById('loginOverlay').style.display='none';
      document.getElementById('loginError').className='result';
      loadStatus();
    }else{
      var e=document.getElementById('loginError'); e.textContent=d.msg; e.className='result err show';
    }
  });
}

function checkSession(){
  fetch('/api/status',{method:'POST'}).then(function(r){
    if(r.status===401) document.getElementById('loginOverlay').style.display='flex';
    else return r.json();
  }).then(function(d){ if(d&&d.ok) loadStatus(); }).catch(function(){});
}

function loadStatus(){
  fetch('/api/status',{method:'POST'}).then(function(r){return r.json()}).then(function(d){
    if(!d.ok) return;
    document.getElementById('statusBar').innerHTML='<span>'+(d.webViewAlive?'🟢 WebView':'🔴 WebView')+'</span><span>Token: '+(d.tokenLen>0?'有效':'无')+'</span><span>白名单: '+d.whitelistCount+'</span>';
    if(username) document.getElementById('userInfo').textContent='已登录: '+username+' ('+role+')';
  }).catch(function(){});
}

// ---- CTWing ops ----

function doOp(op){
  var iccid=document.getElementById('iccid').value.trim();
  if(!iccid){ toast('请先输入卡号'); return; }
  var el=document.getElementById('opResult'); el.className='result info show';
  el.textContent=({query:'查询中',diagnose:'诊断中',rebind:'重绑中'})[op]+'...';
  fetch('/api/'+op,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'iccid='+encodeURIComponent(iccid)})
  .then(function(r){return r.json()}).then(function(d){
    el.className='result '+(d.ok?'ok':'err')+' show'; el.textContent=d.ok?d.result:d.msg;
  }).catch(function(e){ el.className='result err show'; el.textContent='请求失败: '+e.message; });
}
function doQuery(){ doOp('query'); }
function doDiagnose(){ doOp('diagnose'); }
function doRebind(){ doOp('rebind'); }

function doRenew(){
  var el=document.getElementById('renewResult'), btn=document.getElementById('btnRenew');
  btn.disabled=true; el.className='result info show'; el.textContent='正在强制刷新（约 10 秒）...';
  fetch('/api/renew',{method:'POST'}).then(function(r){return r.json()}).then(function(d){
    el.className='result '+(d.ok&&d.changed?'ok':'err')+' show';
    if(d.ok&&d.changed) el.textContent='✅ 续期成功，token 已刷新\n旧: '+d.oldToken.substring(0,12)+'...\n新: '+d.newToken.substring(0,12)+'...';
    else if(d.ok) el.textContent='⚠️ 续期完成，但 token 未变化';
    else el.textContent='❌ '+d.msg;
    loadStatus();
  }).catch(function(e){ el.className='result err show'; el.textContent='请求失败: '+e.message; }).finally(function(){ btn.disabled=false; });
}

function doSelfCheck(){
  var el=document.getElementById('renewResult'), btn=document.getElementById('btnSelfCheck');
  btn.disabled=true; el.className='result info show'; el.textContent='正在自检 Token…\n（用真实卡号查询+诊断验证 token 可用性）';
  fetch('/api/selfcheck',{method:'POST'}).then(function(r){return r.json()}).then(function(d){
    if(d.status==='ok') el.className='result ok show';
    else if(d.status==='partial') el.className='result info show';
    else el.className='result err show';
    el.textContent=d.detail;
    loadStatus();
  }).catch(function(e){ el.className='result err show'; el.textContent='自检请求失败: '+e.message; })
  .finally(function(){ btn.disabled=false; });
}

// ---- AI OCR ----

var lastOcrIccid='';
function doOcr(){
  var file=document.getElementById('imageFile').files[0];
  if(!file){ toast('请先选择图片'); return; }
  var el=document.getElementById('ocrResult'); el.className='result info show'; el.textContent='正在 AI 识别...';
  var formData=new FormData(); formData.append('image',file);
  fetch('/api/ocr',{method:'POST',body:formData})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){ lastOcrIccid=d.result; el.className='result ok show'; el.textContent='✅ 识别结果: '+d.result; }
    else { el.className='result err show'; el.textContent='❌ '+d.msg; }
  }).catch(function(e){ el.className='result err show'; el.textContent='请求失败: '+e.message; });
}

function fillFromOcr(){
  if(!lastOcrIccid){ toast('请先识别一张图片'); return; }
  document.getElementById('iccid').value=lastOcrIccid;
  toast('已填入: '+lastOcrIccid);
}

function previewImage(input){
  if(!input.files||!input.files[0]) return;
  var reader=new FileReader();
  reader.onload=function(e){
    var el=document.getElementById('ocrResult'); el.className='result info show';
    el.innerHTML='<img src="'+e.target.result+'" style="max-width:100%;max-height:200px;border-radius:8px;">';
  };
  reader.readAsDataURL(input.files[0]);
}

// ---- 账号管理面板 ----

function showAccountPanel(){
  var overlay=document.getElementById('modalOverlay');
  var content=document.getElementById('modalContent');
  overlay.classList.add('show');
  content.innerHTML='<h3>👤 账号管理</h3><div id="accountList">加载中...</div>'+
    '<div class="btn-row" style="margin-top:12px;">'+
    '<button class="btn btn-sm btn-query" onclick="showAddAccount()">+ 添加用户</button>'+
    '<button class="btn btn-sm btn-query" onclick="showChangePw()">🔒 改自己密码</button>'+
    '<button class="btn btn-sm btn-danger" onclick="document.getElementById(\'modalOverlay\').classList.remove(\'show\')">关闭</button></div>';
  loadAccountList();
}

function loadAccountList(){
  fetch('/api/accounts/list',{method:'POST'}).then(function(r){return r.json()}).then(function(d){
    if(!d.ok){ document.getElementById('accountList').textContent=d.msg; return; }
    var html='<table><tr><th>用户名</th><th>角色</th>'+(role==='admin'?'<th>操作</th>':'')+'</tr>';
    d.accounts.forEach(function(a){
      html+='<tr><td>'+a.username+'</td><td>'+(a.role==='admin'?'管理员':'用户')+'</td>';
      if(role==='admin'&&a.username!==username) html+='<td><button class="btn btn-sm btn-danger" onclick="deleteAccount(\''+a.username+'\')">删除</button> <button class="btn btn-sm btn-query" onclick="showResetPw(\''+a.username+'\')">重置密码</button></td>';
      html+='</tr>';
    });
    html+='</table>';
    document.getElementById('accountList').innerHTML=html;
  });
}

function showAddAccount(){
  var content=document.getElementById('modalContent');
  content.innerHTML='<h3>➕ 添加用户</h3>'+
    '<input type="text" id="newUsername" placeholder="用户名"><br><br>'+
    '<input type="password" id="newPassword" placeholder="密码"><br><br>'+
    '<select id="newRole" style="width:100%;padding:10px;border:1.5px solid #e0e4ea;border-radius:10px;"><option value="user">普通用户</option><option value="admin">管理员</option></select><br><br>'+
    '<input type="password" id="adminConfirmPw" placeholder="管理员密码确认"><br>'+
    '<div class="btn-row" style="margin-top:12px;">'+
    '<button class="btn btn-renew btn-sm" onclick="doAddAccount()">确定</button>'+
    '<button class="btn btn-sm btn-danger" onclick="showAccountPanel()">返回</button></div>';
}

function doAddAccount(){
  var u=document.getElementById('newUsername').value.trim();
  var p=document.getElementById('newPassword').value;
  var r=document.getElementById('newRole').value;
  var ap=document.getElementById('adminConfirmPw').value;
  if(!u||!p||!ap){ toast('请填写完整'); return; }
  fetch('/api/accounts/add',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p)+'&role='+encodeURIComponent(r)+'&adminPassword='+encodeURIComponent(ap)})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){ toast('添加成功'); showAccountPanel(); } else toast('失败: '+d.msg);
  });
}

function deleteAccount(user){
  var ap=prompt('请输入管理员密码确认删除 '+user);
  if(!ap) return;
  fetch('/api/accounts/delete',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'username='+encodeURIComponent(user)+'&adminPassword='+encodeURIComponent(ap)})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){ toast('已删除 '+user); loadAccountList(); } else toast('失败: '+d.msg);
  });
}

function showChangePw(){
  var content=document.getElementById('modalContent');
  content.innerHTML='<h3>🔒 修改密码</h3>'+
    '<input type="password" id="oldPw" placeholder="旧密码"><br><br>'+
    '<input type="password" id="newPw" placeholder="新密码"><br>'+
    '<div class="btn-row" style="margin-top:12px;">'+
    '<button class="btn btn-renew btn-sm" onclick="doChangePw()">确定</button>'+
    '<button class="btn btn-sm btn-danger" onclick="showAccountPanel()">返回</button></div>';
}

function doChangePw(){
  var o=document.getElementById('oldPw').value;
  var n=document.getElementById('newPw').value;
  if(!o||!n){ toast('请填写完整'); return; }
  fetch('/api/accounts/changepw',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'oldPassword='+encodeURIComponent(o)+'&newPassword='+encodeURIComponent(n)})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){ toast('密码已修改'); showAccountPanel(); } else toast('失败: '+d.msg);
  });
}

function showResetPw(user){
  var content=document.getElementById('modalContent');
  content.innerHTML='<h3>🔑 重置密码 - '+user+'</h3>'+
    '<input type="password" id="resetNewPw" placeholder="新密码"><br><br>'+
    '<input type="password" id="adminConfirmPw2" placeholder="管理员密码确认"><br>'+
    '<div class="btn-row" style="margin-top:12px;">'+
    '<button class="btn btn-renew btn-sm" onclick="doResetPw(\''+user+'\')">确定</button>'+
    '<button class="btn btn-sm btn-danger" onclick="showAccountPanel()">返回</button></div>';
}

function doResetPw(user){
  var p=document.getElementById('resetNewPw').value;
  var ap=document.getElementById('adminConfirmPw2').value;
  if(!p||!ap){ toast('请填写完整'); return; }
  fetch('/api/accounts/resetpw',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'username='+encodeURIComponent(user)+'&newPassword='+encodeURIComponent(p)+'&adminPassword='+encodeURIComponent(ap)})
  .then(function(r){return r.json()}).then(function(d){
    if(d.ok){ toast('密码已重置'); showAccountPanel(); } else toast('失败: '+d.msg);
  });
}

function toast(msg){
  var t=document.getElementById('toast'); t.textContent=msg; t.className='toast show';
  setTimeout(function(){ t.className='toast'; }, 2000);
}

setInterval(loadStatus, 30_000);
</script>
</body>
</html>
""".trimIndent()
}