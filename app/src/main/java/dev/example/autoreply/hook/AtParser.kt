package dev.example.autoreply.hook

import de.robv.android.xposed.XposedBridge
import java.nio.ByteBuffer

/**
 * 从微信消息的 lvbuffer 二进制字段解析「被 @ 的用户列表」。
 * 完全参照 WeKit MessageInfo.msgSource / mentionedUsers 的实现。
 */
object AtParser {

    /**
     * 从 lvbuffer 解析 msgSource XML 字符串。
     * 参照 WeKit MessageInfo.msgSource（逐字节偏移，带容错）。
     */
    fun parseMsgSource(lvBuffer: ByteArray?): String {
        if (lvBuffer == null) return ""
        if (lvBuffer.isEmpty()) return ""
        if (lvBuffer[0] != '{'.code.toByte() || lvBuffer.last() != '}'.code.toByte()) {
            // 结构不符，尝试全量 UTF-8 解码后搜 XML 标记
            return searchXmlInBytes(lvBuffer)
        }

        return runCatching {
            val bb = ByteBuffer.wrap(lvBuffer)
            bb.position(1) // skip '{'

            // 第 1 段：string field（2 字节长度前缀 + 数据）
            if (bb.remaining() >= 2) {
                val n1 = bb.short.toInt()
                if (n1 in 1..3072 && bb.remaining() >= n1) bb.position(bb.position() + n1)
            }

            // 跳过 4 字节 int 字段
            if (bb.remaining() >= 4) bb.position(bb.position() + 4)

            // 第 3 段：msgSource string（2 字节长度前缀 + 数据）
            if (bb.remaining() < 2) return ""
            val n2 = bb.short.toInt()
            if (n2 < 1 || n2 > 3072) return ""
            if (bb.remaining() < n2) return ""

            val bytes = ByteArray(n2)
            bb.get(bytes)
            String(bytes, Charsets.UTF_8)
        }.getOrElse { searchXmlInBytes(lvBuffer) }
    }

    /** 兜底：直接在原始字节里搜 `<msgsource>`...`</msgsource>` */
    private fun searchXmlInBytes(data: ByteArray): String {
        return runCatching {
            val raw = String(data, Charsets.UTF_8).replace("\u0000", "")
            val start = raw.indexOf("<msgsource>")
            if (start < 0) return ""
            val end = raw.indexOf("</msgsource>", start) + "</msgsource>".length
            if (end <= start) return ""
            raw.substring(start, end)
        }.getOrElse { "" }
    }

    /** 从 msgSource XML 中提取 atuserlist（被 @ 的用户 wxid 列表）。 */
    fun parseAtUserList(msgSource: String): List<String> {
        if (msgSource.isEmpty()) return emptyList()
        return runCatching {
            val marker = "<atuserlist>"
            val start = msgSource.indexOf(marker)
            if (start < 0) return emptyList()
            val contentStart = start + marker.length
            val end = msgSource.indexOf("</atuserlist>", contentStart)
            if (end < 0) return emptyList()
            msgSource.substring(contentStart, end)
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    /**
     * 判断这条群聊消息是否 @ 了自己（或 @所有人）。
     */
    fun isAtMeOrAll(lvBuffer: ByteArray?, selfWxId: String?): Boolean {
        if (selfWxId.isNullOrBlank()) return false
        val msgSource = parseMsgSource(lvBuffer)
        if (msgSource.isEmpty()) return false
        val atList = parseAtUserList(msgSource)
        if (atList.isEmpty()) return false
        return atList.contains(selfWxId) ||
            atList.contains("notify@all") ||
            atList.contains("announcement@all")
    }
}