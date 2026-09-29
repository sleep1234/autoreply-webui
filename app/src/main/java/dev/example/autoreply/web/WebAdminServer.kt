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

object WebAdminServer {

    private const val TAG = "[WebAdmin]"
    
    private var passwordHash: String? = null
    private var dataDir: File? = null
    private var port: Int = 60080
    
    private val sessions = ConcurrentHashMap<String, Long>()
    private const val SESSION_TTL_MS = 5 * 60 * 1000L
    private var serverJob: Job? = null

    fun start(appDataDir: String, userPort: Int) {
        if (serverJob?.isActive == true) return
        dataDir = File(appDataDir, "files/autoreply").also { it.mkdirs() }
        port = userPort

        val pwFile = File(dataDir, "web_password.hash")
        if (pwFile.exists()) passwordHash = pwFile.readText().trim()
        else {
            passwordHash = sha256("admin123")
            pwFile.writeText(passwordHash!!)
            XposedBridge.log("$TAG 初始密码: admin123")
        }

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            var server: ServerSocket? = null
            for (attempt in 0 until 5) {
                try { server = ServerSocket(port + attempt).apply { reuseAddress = true }; port += attempt; break }
                catch (_: Exception) { if (attempt == 4) return@launch }
            }
            val srv = server ?: return@launch
            XposedBridge.log("$TAG HTTP 服务已启动，端口 $port")
            try { while (isActive) { runCatching { srv.accept() }.getOrNull()?.let { launch { handle(it) } } } }
            catch (e: Exception) { XposedBridge.log("$TAG 服务异常: ${e.message}") }
        }
    }

    fun stop() { serverJob?.cancel(); serverJob = null }

    // ---- 客户端 ----

    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()
            val reqLine = input.readLine() ?: return
            XposedBridge.log("$TAG 请求: $reqLine")
            val parts = reqLine.split(" "); if (parts.size < 2) return
            val method = parts[0]; val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) { val l = input.readLine() ?: break; if (l.isEmpty()) break; val c = l.indexOf(": "); if (c > 0) headers[l.substring(0, c).lowercase()] = l.substring(c + 2) }
            var body = ""; val cl = headers["content-length"]?.toIntOrNull() ?: 0
            if (cl > 0) { val b = CharArray(cl); input.read(b, 0, cl); body = String(b) }

            val qi = rawPath.indexOf("?"); val path = if (qi >= 0) rawPath.substring(0, qi) else rawPath
            val qs = if (qi >= 0) rawPath.substring(qi + 1) else ""
            val params = parseQuery(qs)
            val bodyParams = if (body.isNotBlank()) parseQuery(body) else emptyMap()

            val (code, ct, resp) = route(method, path, params, bodyParams, headers)
            val bodyBytes = resp.toByteArray(Charsets.UTF_8)
            val st = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "OK" }
            val head = buildString {
                append("HTTP/1.1 $code $st\r\n"); append("Content-Type: $ct\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n"); append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            output.write(head); output.write(bodyBytes); output.flush()
            runCatching { socket.shutdownOutput() }
            XposedBridge.log("$TAG 响应: $code (${bodyBytes.size}B)")
        } catch (_: Exception) {}
        finally { try { Thread.sleep(200) } catch (_: Exception) {}; runCatching { socket.close() } }
    }

    // ---- 路由 ----

    private suspend fun route(m: String, p: String, q: Map<String, String>, b: Map<String, String>, h: Map<String, String>): Triple<Int, String, String> = when {
        p == "/" || p == "/index.html" -> Triple(200, "text/html; charset=utf-8", HTML)
        p == "/api/login" && m == "POST" -> login(b)
        p == "/api/status" -> auth(h) { status() }
        p == "/api/query" && m == "POST" -> auth(h) { query(b) }
        p == "/api/diagnose" && m == "POST" -> auth(h) { diagnose(b) }
        p == "/api/rebind" && m == "POST" -> auth(h) { rebind(b) }
        p == "/api/renew" && m == "POST" -> auth(h) { renew() }
        else -> Triple(404, "text/plain", "Not Found")
    }

    private suspend fun auth(h: Map<String, String>, act: suspend () -> Triple<Int, String, String>): Triple<Int, String, String> {
        val tok = (h["cookie"] ?: "").split("; ").firstOrNull { it.startsWith("session=") }?.removePrefix("session=") ?: ""
        val ts = sessions[tok]
        if (ts == null || System.currentTimeMillis() - ts > SESSION_TTL_MS) { sessions.remove(tok); return Triple(401, "application/json", """{"ok":false,"msg":"未登录"}""") }
        return act()
    }

    private fun login(p: Map<String, String>) = if (sha256(p["password"] ?: "") != passwordHash) Triple(401, "application/json", """{"ok":false,"msg":"密码错误"}""")
    else { val t = rnd(); sessions[t] = System.currentTimeMillis(); Triple(200, "application/json", """{"ok":true,"token":"$t"}""") }

    // ---- API ----

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
            val idType = CtwingKeywordRouter.inferType(iccid)
            val raw = CtwingKeywordRouter.nativeGetWithRetry("web-query") { NativeHttp.queryCard(it, idType, iccid) }
            val em = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
            if (em != null) return err("查询失败：$em")
            val best = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
            val r = best?.let { CtwingKeywordRouter.formatCardInfo(it) } ?: "查询未完成，请稍后重试"
            ok(r)
        } catch (e: Exception) { err("查询失败: ${e.message}") }
    }

    private suspend fun diagnose(p: Map<String, String>): Triple<Int, String, String> {
        val iccid = p["iccid"] ?: return err("请提供卡号")
        return try {
            val idType = CtwingKeywordRouter.inferType(iccid)
            val raw = CtwingKeywordRouter.nativeGetWithRetry("web-diagnose") { NativeHttp.diagnose(it, idType, iccid) }
            val em = raw?.let { CtwingKeywordRouter.extractQueryError(it) }
            if (em != null) return err("诊断失败：$em")
            val best = raw?.let { CtwingKeywordRouter.extractBestResponse(it) }
            val r = best?.let { CtwingKeywordRouter.formatDiagnosis(it) } ?: "诊断未完成，请稍后重试"
            ok(r)
        } catch (e: Exception) { err("诊断失败: ${e.message}") }
    }

    private suspend fun rebind(p: Map<String, String>): Triple<Int, String, String> {
        val iccid = p["iccid"] ?: return err("请提供卡号")
        return try {
            // 直接复用窗口已测通的重绑逻辑
            val result = CtwingFacade.webViewMutex.withLock {
                CtwingKeywordRouter.executeRebind(iccid)
            }
            ok("$result\n${CtwingKeywordRouter.idTypeLabel(iccid)}：$iccid")
        } catch (e: Exception) { err("重绑失败: ${e.message}") }
    }

    private suspend fun renew(): Triple<Int, String, String> {
        return try {
            val old = NativeHttp.cachedToken ?: ""
            // 和 WeChat 窗口"续期"命令完全一致：forceRebuild + pullToken
            CtwingFacade.forceRebuild(40_000L)
            CtwingFacade.pullToken()
            val nt = NativeHttp.cachedToken ?: ""
            val json = org.json.JSONObject().apply {
                put("ok", true)
                put("oldToken", old)
                put("newToken", nt)
                put("changed", old != nt)
            }
            Triple(200, "application/json; charset=utf-8", json.toString())
        } catch (e: Exception) {
            err("续期失败: ${e.message}")
        }
    }

    private fun parseRebindResult(raw: String, iccid: String, idType: String): String {
        val label = when (idType) { "msisdn" -> "接入号"; "imsi" -> "IMSI"; else -> "ICCID" }
        return try {
            var c: Any = raw.trim(); var g = 0
            while (c is String && g < 6) { val t = c.trim(); if (!(t.startsWith("\"") || t.startsWith("{"))) break; c = org.json.JSONTokener(t).nextValue(); g++ }
            val w = c as? org.json.JSONObject ?: throw RuntimeException()
            val bj = when { w.has("status") && w.has("body") -> when (val bt = w.opt("body")) { is org.json.JSONObject -> bt; is String -> org.json.JSONObject(org.json.JSONTokener(bt).nextValue() as String); else -> null }; else -> w } ?: throw RuntimeException()
            val bc = bj.optInt("code", -1); val bd = bj.optJSONObject("data")
            val st = bd?.optString("status", "") ?: ""; val rm = bd?.optString("remark", "") ?: ""; val wid = bd?.optString("id", "") ?: ""
            val r = when {
                bc == 401 -> "⚠️ 重绑失败：登录已过期\n💡 请发送「续期」刷新后重试"
                bc != 0 -> "❌ 重绑失败：${bj.optString("msg", "code=$bc")}"
                st.contains("成功") -> buildString { append("✅ 机卡重绑成功"); if (wid.isNotBlank()) append("（工单：$wid）"); if (rm.isNotBlank()) append("\n$rm") }
                st.contains("失败") -> buildString { append("❌ 机卡重绑失败"); if (rm.isNotBlank()) append("：$rm") }
                else -> buildString { append("✅ 机卡重绑已提交"); if (wid.isNotBlank()) append("（工单：$wid）"); append("\n⏳ 处理结果请稍后查询") }
            }
            "$r\n$label：$iccid"
        } catch (_: Exception) { if (raw.contains("\"code\":0")) "✅ 机卡重绑已提交成功\n$label：$iccid" else "⚠️ 重绑结果异常，请稍后重试\n$label：$iccid" }
    }

    // ---- 工具 ----
    private fun parseQuery(q: String) = if (q.isBlank()) emptyMap<String, String>() else q.split("&").mapNotNull { val eq = it.indexOf("="); if (eq > 0) URLDecoder.decode(it.substring(0, eq), "UTF-8") to URLDecoder.decode(it.substring(eq + 1), "UTF-8") else null }.toMap()
    private fun err(msg: String) = Triple(400, "application/json; charset=utf-8", """{"ok":false,"msg":${org.json.JSONObject.quote(msg)}}""")
    private fun ok(r: String) = Triple(200, "application/json; charset=utf-8", """{"ok":true,"result":${org.json.JSONObject.quote(r)}}""")
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun rnd() = (1..32).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

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
    <div class="btn-row" style="margin-top:16px;"><button class="btn btn-renew" onclick="login()" style="flex:1;">登录</button></div>
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
  fetch('/api/login', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:'password='+encodeURIComponent(document.getElementById('password').value) })
  .then(r=>r.json()).then(d=>{
    if (d.ok) { document.cookie = 'session='+d.token+'; path=/; max-age=3600'; sessionToken = d.token; document.getElementById('loginOverlay').style.display='none'; document.getElementById('loginError').className='result'; loadStatus(); }
    else { var e = document.getElementById('loginError'); e.textContent = d.msg; e.className = 'result err show'; }
  });
}
function checkSession() { fetch('/api/status').then(r=>{ if(r.status===401) document.getElementById('loginOverlay').style.display='flex'; }); }
function loadStatus() {
  fetch('/api/status').then(r=>r.json()).then(d=>{
    if (!d.ok) return;
    document.getElementById('statusBar').innerHTML = '<span>'+(d.webViewAlive?'🟢 WebView':'🔴 WebView')+'</span><span>Token: '+(d.tokenLen>0?'有效':'无')+'</span><span>白名单: '+d.whitelistCount+'</span>';
  }).catch(()=>{});
}
function doOp(op) {
  var iccid = document.getElementById('iccid').value.trim();
  if (!iccid) { toast('请先输入卡号'); return; }
  var el = document.getElementById('opResult'); el.className = 'result info show';
  el.textContent = ({query:'查询中',diagnose:'诊断中',rebind:'重绑中'})[op] + '...';
  fetch('/api/'+op, { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body:'iccid='+encodeURIComponent(iccid) })
  .then(r=>r.json()).then(d=>{ el.className = 'result '+(d.ok?'ok':'err')+' show'; el.textContent = d.ok?d.result:d.msg; })
  .catch(e=>{ el.className='result err show'; el.textContent='请求失败: '+e.message; });
}
function doQuery() { doOp('query'); }
function doDiagnose() { doOp('diagnose'); }
function doRebind() { doOp('rebind'); }
function doRenew() {
  var el = document.getElementById('renewResult'), btn = document.getElementById('btnRenew');
  btn.disabled = true; el.className = 'result info show'; el.textContent = '正在强制刷新（约 30 秒）...';
  fetch('/api/renew', { method:'POST' }).then(r=>r.json()).then(d=>{
    el.className = 'result '+(d.ok&&d.changed?'ok':'err')+' show';
    if (d.ok && d.changed) el.textContent = '✅ 续期成功，token 已刷新\n旧: '+d.oldToken.substring(0,12)+'...\n新: '+d.newToken.substring(0,12)+'...';
    else if (d.ok) el.textContent = '⚠️ 续期完成，但 token 未变化';
    else el.textContent = '❌ '+d.msg;
    loadStatus();
  }).catch(e=>{ el.className='result err show'; el.textContent='请求失败: '+e.message; }).finally(()=>{ btn.disabled = false; });
}
function toast(msg) { var t = document.getElementById('toast'); t.textContent = msg; t.className = 'toast show'; setTimeout(function(){ t.className = 'toast'; }, 2000); }
setInterval(loadStatus, 30_000);
</script>
</body>
</html>
""".trimIndent()
}