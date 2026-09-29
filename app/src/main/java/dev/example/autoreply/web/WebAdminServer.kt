package dev.example.autoreply.web

import android.os.Handler
import android.os.Looper
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
 * 嵌入式 Web 管理后台，直接跑在微信主进程的协程里。
 * 所有操作直接复用已有测通逻辑（CtwingKeywordRouter/CtwingFacade/NativeHttp），
 * 零额外造轮子。
 */
object WebAdminServer {

    private const val TAG = "[WebAdmin]"
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var passwordHash: String? = null
    private var dataDir: File? = null
    private var port: Int = 60080
    
    private val sessions = ConcurrentHashMap<String, Long>()
    private const val SESSION_TTL_MS = 5 * 60 * 1000L

    private var serverJob: Job? = null

    // ---- 启动/停止 ----

    fun start(appDataDir: String, userPort: Int) {
        if (serverJob?.isActive == true) return
        dataDir = File(appDataDir, "files/autoreply")
        dataDir!!.mkdirs()
        port = userPort
        val maxRetries = 5

        val pwFile = File(dataDir, "web_password.hash")
        if (pwFile.exists()) {
            passwordHash = pwFile.readText().trim()
        } else {
            passwordHash = sha256("admin123")
            pwFile.writeText(passwordHash!!)
            XposedBridge.log("$TAG 初始密码: admin123（保存于 ${pwFile.absolutePath}）")
        }

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            var server: ServerSocket? = null
            for (attempt in 0 until maxRetries) {
                try {
                    server = ServerSocket(port + attempt).apply { reuseAddress = true }
                    port += attempt
                    break
                } catch (e: Exception) {
                    XposedBridge.log("$TAG 端口 ${port + attempt} 绑定失败: ${e.message}")
                    if (attempt == maxRetries - 1) {
                        XposedBridge.log("$TAG 所有端口都不可用，放弃启动")
                        return@launch
                    }
                }
            }
            val srv = server ?: return@launch
            XposedBridge.log("$TAG HTTP 服务已启动，端口 $port")
            try {
                while (isActive) {
                    val client = runCatching { srv.accept() }.getOrNull() ?: continue
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG 服务异常: ${e.message}")
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
    }

    // ---- 客户端处理 ----

    private suspend fun handleClient(socket: Socket) {
        try {
            XposedBridge.log("$TAG 收到连接: ${socket.inetAddress.hostAddress}")
            socket.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            XposedBridge.log("$TAG 请求: $requestLine")
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(": ")
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 2)
            }

            var body = ""
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                input.read(buf, 0, contentLength)
                body = String(buf)
            }

            val queryIdx = rawPath.indexOf("?")
            val path = if (queryIdx >= 0) rawPath.substring(0, queryIdx) else rawPath
            val queryStr = if (queryIdx >= 0) rawPath.substring(queryIdx + 1) else ""
            val params = parseQueryParams(queryStr)
            val bodyParams = if (body.isNotBlank()) parseQueryParams(body) else emptyMap()

            val (statusCode, contentType, responseBody) = route(method, path, params, bodyParams, headers)

            val bodyBytes = responseBody.toByteArray(Charsets.UTF_8)
            val statusText = when (statusCode) {
                200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
                else -> "OK"
            }
            val headBytes = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

            output.write(headBytes)
            output.write(bodyBytes)
            output.flush()
            runCatching { socket.shutdownOutput() }
            XposedBridge.log("$TAG 响应已发送: $statusCode (${bodyBytes.size}B)")
        } catch (_: Exception) {
        } finally {
            try { Thread.sleep(200) } catch (_: Exception) {}
            runCatching { socket.close() }
        }
    }

    // ---- 路由 ----

    private suspend fun route(
        method: String, path: String,
        params: Map<String, String>, bodyParams: Map<String, String>,
        headers: Map<String, String>,
    ): Triple<Int, String, String> {
        return when {
            path == "/" || path == "/index.html" -> Triple(200, "text/html; charset=utf-8", HTML)
            path == "/api/login" && method == "POST" -> handleLogin(bodyParams)
            path == "/api/status" -> handleAuth(headers) { handleStatus() }
            path == "/api/query" && method == "POST" -> handleAuth(headers) { handleQuery(bodyParams) }
            path == "/api/diagnose" && method == "POST" -> handleAuth(headers) { handleDiagnose(bodyParams) }
            path == "/api/rebind" && method == "POST" -> handleAuth(headers) { handleRebind(bodyParams) }
            path == "/api/renew" && method == "POST" -> handleAuth(headers) { handleRenew() }
            else -> Triple(404, "text/plain", "Not Found")
        }
    }

    // ---- 认证 ----

    private suspend fun handleAuth(headers: Map<String, String>, action: suspend () -> Triple<Int, String, String>): Triple<Int, String, String> {
        val cookie = headers["cookie"] ?: ""
        val sessionToken = cookie.split("; ")
            .firstOrNull { it.startsWith("session=") }
            ?.removePrefix("session=") ?: ""
        val ts = sessions[sessionToken]
        if (ts == null || System.currentTimeMillis() - ts > SESSION_TTL_MS) {
            sessions.remove(sessionToken)
            return Triple(401, "application/json", """{"ok":false,"msg":"未登录"}""")
        }
        return action()
    }

    private fun handleLogin(params: Map<String, String>): Triple<Int, String, String> {
        val hash = sha256(params["password"] ?: "")
        if (hash != passwordHash) return Triple(401, "application/json", """{"ok":false,"msg":"密码错误"}""")
        val token = randomToken()
        sessions[token] = System.currentTimeMillis()
        return Triple(200, "application/json", """{"ok":true,"token":"$token"}""")
    }

    // ---- API：全部直接复用已有测通逻辑 ----

    private fun handleStatus(): Triple<Int, String, String> {
        val wvAlive = CtwingWebViewHook.currentWebView() != null
        val token = NativeHttp.cachedToken ?: ""
        val json = org.json.JSONObject().apply {
            put("ok", true)
            put("webViewAlive", wvAlive)
            put("token", token)
            put("tokenLen", token.length)
            put("whitelistCount", WhitelistStore.list().size)
            put("tinkerSummary", TinkerGuard.statusSummary())
        }
        return Triple(200, "application/json; charset=utf-8", json.toString())
    }

    private suspend fun handleQuery(params: Map<String, String>): Triple<Int, String, String> {
        val iccid = params["iccid"] ?: return errorJson("请提供卡号")
        return try {
            val idType = CtwingKeywordRouter.inferType(iccid)
            // 直接复用 nativeGetWithRetry（自动 token 拉取 + 401 重试），跑在 IO 线程
            val raw = CtwingKeywordRouter.nativeGetWithRetry("web-query") { token ->
                NativeHttp.queryCard(token, idType, iccid)
            }
            val errorMsg = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
            if (errorMsg != null) return errorJson("查询失败：$errorMsg")
            val bestResp = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
            val result = bestResp?.let { CtwingKeywordRouter.formatCardInfo(it) }
                ?: "查询未完成，请稍后重试"
            Triple(200, "application/json; charset=utf-8", """{"ok":true,"result":${org.json.JSONObject.quote(result)}}""")
        } catch (e: Exception) {
            errorJson("查询失败: ${e.message}")
        }
    }

    private suspend fun handleDiagnose(params: Map<String, String>): Triple<Int, String, String> {
        val iccid = params["iccid"] ?: return errorJson("请提供卡号")
        return try {
            val idType = CtwingKeywordRouter.inferType(iccid)
            val raw = CtwingKeywordRouter.nativeGetWithRetry("web-diagnose") { token ->
                NativeHttp.diagnose(token, idType, iccid)
            }
            val errorMsg = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
            if (errorMsg != null) return errorJson("诊断失败：$errorMsg")
            val bestResp = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
            val result = bestResp?.let { CtwingKeywordRouter.formatDiagnosis(it) }
                ?: "诊断未完成，请稍后重试"
            Triple(200, "application/json; charset=utf-8", """{"ok":true,"result":${org.json.JSONObject.quote(result)}}""")
        } catch (e: Exception) {
            errorJson("诊断失败: ${e.message}")
        }
    }

    private suspend fun handleRebind(params: Map<String, String>): Triple<Int, String, String> {
        val iccid = params["iccid"] ?: return errorJson("请提供卡号")
        return withContext(Dispatchers.Main) {
            try {
                val idType = CtwingKeywordRouter.inferType(iccid)
                CtwingFacade.webViewMutex.withLock {
                    // 复用聊天重绑的完整链路：pullTokenOrRebuild → payload → XHR fallback NativeHttp → parse
                    CtwingFacade.pullTokenOrRebuild()
                    val token = NativeHttp.cachedToken ?: ""
                    val payload = org.json.JSONObject().apply {
                        put("type", idType)
                        put("id", iccid)
                        put("imei", "")
                        put("source", "其他")
                        put("orderNumber", "")
                        put("sessionId", "")
                        put("comment", "")
                        put("bindType", "")
                        put("file", org.json.JSONObject().put("ids", org.json.JSONArray()))
                        put("operation", "JKCB")
                    }.toString()

                    // 优先 WebView XHR，轮询 fallback NativeHttp
                    CtwingFacade.operationCommit(payload)
                    var raw = "null"
                    var webViewOk = false
                    for (round in 1..6) {
                        kotlinx.coroutines.delay(1_000L)
                        raw = CtwingFacade.pollDshResult()
                        if (raw.contains("operationCommit-ok") || raw.contains("operationCommit-err")) { webViewOk = true; break }
                        if (raw.length > 20 && raw != "null") { webViewOk = true; break }
                    }
                    if (!webViewOk) {
                        raw = runCatching {
                            NativeHttp.operationCommit(token, payload)
                        }.getOrElse { e -> """{"code":-1,"msg":"${e.message}"}""" }
                    }

                    if (raw.contains("\"code\":401")) {
                        CtwingFacade.forceRebuild(40_000L)
                        CtwingFacade.pullToken()
                        val newToken = NativeHttp.cachedToken ?: ""
                        raw = runCatching {
                            NativeHttp.operationCommit(newToken, payload)
                        }.getOrElse { e -> """{"code":-1,"msg":"${e.message}"}""" }
                    }
                    CtwingFacade.releaseWakeLock()

                    // 解析结果（和内联重绑逻辑等价）
                    val resultText = parseRebindResult(raw, iccid, idType)
                    Triple(200, "application/json; charset=utf-8", """{"ok":true,"result":${org.json.JSONObject.quote(resultText)}}""")
                }
            } catch (e: Exception) {
                errorJson("重绑失败: ${e.message}")
            }
        }
    }

    private suspend fun handleRenew(): Triple<Int, String, String> {
        return withContext(Dispatchers.Main) {
            try {
                val oldToken = NativeHttp.cachedToken ?: ""
                CtwingFacade.forceRebuild(40_000L)
                CtwingFacade.pullToken()
                delay(10_000L) // 等待 OAuth → 新 token 写入
                val newToken = NativeHttp.cachedToken ?: ""
                val json = org.json.JSONObject().apply {
                    put("ok", true)
                    put("oldToken", oldToken)
                    put("newToken", newToken)
                    put("changed", oldToken != newToken)
                }
                Triple(200, "application/json; charset=utf-8", json.toString())
            } catch (e: Exception) {
                errorJson("续期失败: ${e.message}")
            }
        }
    }

    /** 重绑结果解析（和内联逻辑一致）。 */
    private fun parseRebindResult(raw: String, iccid: String, idType: String): String {
        val idTypeLabel = when (idType) { "msisdn" -> "接入号"; "imsi" -> "IMSI"; else -> "ICCID" }
        return try {
            var cur: Any = raw.trim(); var guard = 0
            while (cur is String && guard < 6) {
                val t = cur.trim()
                if (!(t.startsWith("\"") || t.startsWith("{"))) break
                cur = org.json.JSONTokener(t).nextValue(); guard++
            }
            val wrap = cur as? org.json.JSONObject ?: throw RuntimeException("not object")
            val bj = when {
                wrap.has("status") && wrap.has("body") -> {
                    when (val bt = wrap.opt("body")) {
                        is org.json.JSONObject -> bt
                        is String -> org.json.JSONObject(org.json.JSONTokener(bt).nextValue() as String)
                        else -> null
                    }
                }
                else -> wrap
            } ?: throw RuntimeException("no body")
            val bcode = bj.optInt("code", -1)
            val bdata = bj.optJSONObject("data")
            val opStatus = bdata?.optString("status", "") ?: ""
            val remark = bdata?.optString("remark", "") ?: ""
            val workId = bdata?.optString("id", "") ?: ""
            val result = when {
                bcode == 401 -> "⚠️ 重绑失败：登录已过期\n💡 请发送「续期」刷新后重试"
                bcode != 0 -> {
                    val msg = bj.optString("msg", "code=$bcode")
                    "❌ 重绑失败：$msg"
                }
                opStatus.contains("成功") -> buildString {
                    append("✅ 机卡重绑成功")
                    if (workId.isNotBlank()) append("（工单：$workId）")
                    if (remark.isNotBlank()) append("\n$remark")
                }
                opStatus.contains("失败") -> buildString {
                    append("❌ 机卡重绑失败")
                    if (remark.isNotBlank()) append("：$remark")
                }
                else -> buildString {
                    append("✅ 机卡重绑已提交")
                    if (workId.isNotBlank()) append("（工单：$workId）")
                    append("\n⏳ 处理结果请稍后查询")
                }
            }
            "$result\n$idTypeLabel：$iccid"
        } catch (e: Exception) {
            if (raw.contains("\"code\":0")) "✅ 机卡重绑已提交成功\n$idTypeLabel：$iccid"
            else "⚠️ 重绑结果异常，请稍后重试\n$idTypeLabel：$iccid"
        }
    }

    // ---- 工具 ----

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val eq = pair.indexOf("=")
            if (eq > 0) {
                map[URLDecoder.decode(pair.substring(0, eq), "UTF-8")] =
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }
        }
        return map
    }

    private fun errorJson(msg: String) = Triple(400, "application/json; charset=utf-8",
        """{"ok":false,"msg":${org.json.JSONObject.quote(msg)}}""")

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32).map { chars.random() }.joinToString("")
    }

    // ---- HTML 页面 ----

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
  input[type="text"], input[type="password"] { width: 100%; padding: 12px 14px; border: 1.5px solid #e0e4ea; border-radius: 10px; font-size: 15px; outline: none; transition: border .2s; }
  input:focus { border-color: #667eea; }
  .btn-row { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
  .btn { flex: 1; min-width: 80px; padding: 11px 14px; border: none; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all .2s; text-align: center; }
  .btn:active { transform: scale(0.96); }
  .btn-query { background: #e8f0fe; color: #1967d2; }
  .btn-diag { background: #fce8e6; color: #c5221f; }
  .btn-rebind { background: #fef7e0; color: #ea8600; }
  .btn-renew { background: linear-gradient(135deg, #667eea, #764ba2); color: white; }
  .btn:disabled { opacity: 0.5; pointer-events: none; }
  .result { margin-top: 12px; padding: 12px; border-radius: 10px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; display: none; }
  .result.show { display: block; }
  .result.ok { background: #e6f4ea; color: #137333; }
  .result.err { background: #fce8e6; color: #c5221f; }
  .result.info { background: #e8f0fe; color: #1967d2; }
  .login-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 100; }
  .login-box { background: white; border-radius: 16px; padding: 30px; width: 320px; box-shadow: 0 10px 40px rgba(0,0,0,0.15); }
  .login-box h2 { font-size: 18px; margin-bottom: 18px; }
  .toast { position: fixed; top: 20px; left: 50%; transform: translateX(-50%); background: #333; color: white; padding: 10px 20px; border-radius: 8px; font-size: 13px; z-index: 200; display: none; }
  .toast.show { display: block; }
</style>
</head>
<body>
<div class="login-overlay" id="loginOverlay">
  <div class="login-box">
    <h2>🔐 管理员登录</h2>
    <input type="password" id="password" placeholder="输入管理密码" onkeydown="if(event.key==='Enter')login()">
    <div class="btn-row" style="margin-top:16px;">
      <button class="btn btn-renew" onclick="login()" style="flex:1;">登录</button>
    </div>
    <div class="result err" id="loginError" style="margin-top:12px;"></div>
  </div>
</div>
<div class="toast" id="toast"></div>
<div class="container">
  <div class="header">
    <h1>🛰️ 天翼物联一站式服务工具</h1>
    <div class="status" id="statusBar"><span>加载中...</span></div>
  </div>

  <div class="card">
    <h2>🔑 一键续期</h2>
    <button class="btn btn-renew" onclick="doRenew()" id="btnRenew" style="width:100%;">🔄 强制刷新登录态</button>
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
</div>

<script>
var sessionToken = document.cookie.split('; ').find(r=>r.startsWith('session='));
if (sessionToken) { sessionToken = sessionToken.split('=')[1]; document.getElementById('loginOverlay').style.display='none'; checkSession(); loadStatus(); }
else document.getElementById('loginOverlay').style.display='flex';

function login() {
  var pw = document.getElementById('password').value;
  fetch('/api/login', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:'password='+encodeURIComponent(pw) })
  .then(r=>r.json()).then(d=>{
    if (d.ok) {
      document.cookie = 'session='+d.token+'; path=/; max-age=3600';
      sessionToken = d.token;
      document.getElementById('loginOverlay').style.display='none';
      document.getElementById('loginError').className='result';
      loadStatus();
    } else {
      var el = document.getElementById('loginError');
      el.textContent = d.msg; el.className = 'result err show';
    }
  });
}

function checkSession() {
  fetch('/api/status').then(r=>{ if(r.status===401) { document.getElementById('loginOverlay').style.display='flex'; } });
}

function loadStatus() {
  fetch('/api/status').then(r=>r.json()).then(d=>{
    if (!d.ok) return;
    var sb = document.getElementById('statusBar');
    sb.innerHTML = '<span>' + (d.webViewAlive ? '🟢 WebView' : '🔴 WebView') + '</span>' +
      '<span>Token: ' + (d.tokenLen > 0 ? '有效' : '无') + '</span>' +
      '<span>白名单: ' + d.whitelistCount + '</span>';
  }).catch(()=>{});
}

function doQuery() { doOp('query'); }
function doDiagnose() { doOp('diagnose'); }
function doRebind() { doOp('rebind'); }

function doOp(op) {
  var iccid = document.getElementById('iccid').value.trim();
  if (!iccid) { toast('请先输入卡号'); return; }
  var el = document.getElementById('opResult');
  el.className = 'result info show';
  el.textContent = ({query:'查询中',diagnose:'诊断中',rebind:'重绑中'})[op] + '...';
  fetch('/api/'+op, { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:'iccid='+encodeURIComponent(iccid) })
  .then(r=>r.json()).then(d=>{
    el.className = 'result ' + (d.ok ? 'ok' : 'err') + ' show';
    el.textContent = d.ok ? d.result : d.msg;
  }).catch(e=>{ el.className='result err show'; el.textContent='请求失败: '+e.message; });
}

function doRenew() {
  var el = document.getElementById('renewResult');
  var btn = document.getElementById('btnRenew');
  btn.disabled = true;
  el.className = 'result info show';
  el.textContent = '正在强制刷新登录态（约 10 秒）...';
  fetch('/api/renew', { method:'POST' }).then(r=>r.json()).then(d=>{
    el.className = 'result ' + (d.ok && d.changed ? 'ok' : 'err') + ' show';
    if (d.ok && d.changed) {
      el.textContent = '✅ 续期成功，token 已刷新\n旧: ' + d.oldToken.substring(0,12) + '...\n新: ' + d.newToken.substring(0,12) + '...';
    } else if (d.ok) {
      el.textContent = '⚠️ 续期完成，但 token 未变化';
    } else {
      el.textContent = '❌ ' + d.msg;
    }
    loadStatus();
  }).catch(e=>{ el.className='result err show'; el.textContent='请求失败: '+e.message; })
  .finally(()=>{ btn.disabled = false; });
}

function toast(msg) {
  var t = document.getElementById('toast');
  t.textContent = msg; t.className = 'toast show';
  setTimeout(function(){ t.className = 'toast'; }, 2000);
}

setInterval(loadStatus, 30_000);
</script>
</body>
</html>
""".trimIndent()
}