package dev.example.autoreply.tunnel

import de.robv.android.xposed.XposedBridge

/**
 * 隧道管理器（纯 Socket 反向 TCP 隧道）。
 * 用 TunnelClient 实现内网穿透，免外部进程、免 root、SELinux 无阻。
 */
object TunnelManager {

    private const val TAG = "[Tunnel]"

    @Volatile
    private var tunnel: TunnelClient? = null

    /**
     * 初始化：读取 TunnelConfig，若 enabled 则启动隧道。
     * @param appDataDir 微信 dataDir
     */
    fun init(appDataDir: String) {
        TunnelConfig.init(appDataDir)
        TunnelConfig.migrateFromFrp(appDataDir)
        val cfg = TunnelConfig.current()
        XposedBridge.log("$TAG init: enabled=${cfg.enabled} server=${cfg.serverAddr}:${cfg.serverPort}")
        if (cfg.enabled && cfg.serverAddr.isNotBlank() && cfg.remotePort > 0) {
            doStart()
        }
    }

    /** 配置变更后重启隧道。 */
    fun applyConfigAndRestart() {
        stop()
        if (TunnelConfig.current().enabled) {
            doStart()
        }
    }

    private fun doStart() {
        val cfg = TunnelConfig.current()
        if (!cfg.enabled || cfg.serverAddr.isBlank() || cfg.remotePort <= 0) {
            XposedBridge.log("$TAG skip: config incomplete")
            return
        }
        tunnel?.stop()
        tunnel = TunnelClient(
            serverAddr = cfg.serverAddr,
            serverPort = cfg.serverPort,
            authToken = cfg.authToken,
            remotePort = cfg.remotePort,
            localPort = cfg.localPort,
        )
        tunnel!!.start()
    }

    fun stop() {
        tunnel?.stop()
        tunnel = null
    }

    fun status(): Map<String, Any> {
        val cfg = TunnelConfig.current()
        return mapOf(
            "running" to (tunnel?.isConnected() ?: false),
            "enabled" to cfg.enabled,
            "serverAddr" to cfg.serverAddr,
            "serverPort" to cfg.serverPort,
            "remotePort" to cfg.remotePort,
            "localPort" to cfg.localPort,
        )
    }
}