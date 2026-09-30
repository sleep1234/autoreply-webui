package dev.example.autoreply.tunnel

import org.json.JSONObject
import java.io.File

/**
 * 隧道客户端配置持久化。
 * 存储路径：{dataDir}/files/tunnel/config.json
 */
object TunnelConfig {

    private const val FILE_NAME = "config.json"

    /** 默认自检测试卡号（ICCID），所有自检/续期验证的兜底值。 */
    const val DEFAULT_SELF_CHECK_CARD = "8986032548200686692"

    data class Config(
        val enabled: Boolean = false,
        val serverAddr: String = "",
        val serverPort: Int = 7000,
        val authToken: String = "",
        val remotePort: Int = 0,
        val localPort: Int = 60080,
        /** 自检/续期验证用的测试卡号（ICCID 或接入号均可）。 */
        val selfCheckCard: String = DEFAULT_SELF_CHECK_CARD,
    )

    @Volatile
    private var cached: Config? = null
    private var configDir: File? = null

    fun init(dataDir: String) {
        configDir = File(dataDir, "files/tunnel").also { it.mkdirs() }
        cached = load()
    }

    fun load(): Config {
        val f = configFile() ?: return Config()
        if (!f.exists()) return Config()
        return try {
            val j = JSONObject(f.readText())
            Config(
                enabled = j.optBoolean("enabled", false),
                serverAddr = j.optString("serverAddr", ""),
                serverPort = j.optInt("serverPort", 7000),
                authToken = j.optString("authToken", ""),
                remotePort = j.optInt("remotePort", 0),
                localPort = j.optInt("localPort", 60080),
                selfCheckCard = j.optString("selfCheckCard", DEFAULT_SELF_CHECK_CARD),
            )
        } catch (_: Exception) { Config() }
    }

    fun save(cfg: Config) {
        val f = configFile() ?: return
        f.parentFile?.mkdirs()
        val j = JSONObject().apply {
            put("enabled", cfg.enabled)
            put("serverAddr", cfg.serverAddr)
            put("serverPort", cfg.serverPort)
            put("authToken", cfg.authToken)
            put("remotePort", cfg.remotePort)
            put("localPort", cfg.localPort)
            put("selfCheckCard", cfg.selfCheckCard)
        }
        f.writeText(j.toString(2))
        cached = cfg
    }

    fun current(): Config = cached ?: load().also { cached = it }

    private fun configFile(): File? {
        val dir = configDir ?: return null
        return File(dir, FILE_NAME)
    }

    /** 迁移旧版 frp_config.json → tunnel/config.json */
    fun migrateFromFrp(dataDir: String) {
        val oldFile = File(dataDir, "files/frp/frpc_config.json")
        if (oldFile.exists()) {
            try {
                val j = JSONObject(oldFile.readText())
                val cfg = Config(
                    enabled = j.optBoolean("enabled", false),
                    serverAddr = j.optString("serverAddr", ""),
                    serverPort = j.optInt("serverPort", 7000),
                    authToken = j.optString("authToken", ""),
                    remotePort = j.optInt("remotePort", 0),
                    localPort = j.optInt("localPort", 60080),
                )
                save(cfg)
                oldFile.delete()
            } catch (_: Exception) {}
        }
    }
}