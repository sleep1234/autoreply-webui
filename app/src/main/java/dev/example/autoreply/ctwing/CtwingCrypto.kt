package dev.example.autoreply.ctwing

import android.util.Base64
import de.robv.android.xposed.XposedBridge
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CTWing 瑞数动态加密的纯原生实现（从 WX H5 Auto 模块逆向移植）。
 *
 * 加密链路：
 *   1. deriveKey(seed) = MD5(seed).toHex() → 32 字符 hex → 16 字节 AES-128 key
 *   2. encrypt(plainBytes, key) = AES/CBC/PKCS5Padding(IV="X9^-fRvXE3mbR2Zc") → Base64.URL_SAFE
 *   3. 请求信封：随机种子 → deriveKey → 加密请求体 → 种子用 DEFAULT_KEY1 加密 → {ciphertextB64, key, meta}
 *
 * 完全绕过 WebView/sbu1，可在锁屏后台运行。
 */
object CtwingCrypto {

    private const val TAG = "[CtwingCrypto]"

    // -- 逆向常量 (from classes9.dex string table) --
    private val FIXED_IV = "X9^-fRvXE3mbR2Zc".toByteArray(Charsets.US_ASCII)   // 16 字节
    private const val AES_ALG = "AES/CBC/PKCS5Padding"
    private const val HEX_DIGITS = "0123456789abcdef"

    /** DEFAULT_KEY1 — 64 字节 URL_SAFE Base64 blob，用于加密会话种子（信封密钥）。 */
    private const val DEFAULT_KEY1_B64 =
        "_Mn_gC3IYBTK8tUVYPBeH9_geiOi-OhTLJJIfnYiB71FQ1PiCz4HxSr4keBmT3aOclasVkwk02eQ4JWfl4Vh9w"

    /** DEFAULT_KEY1 解码后的字节（约 64 字节 raw key material）。 */
    val DEFAULT_KEY1: ByteArray by lazy {
        Base64.decode(DEFAULT_KEY1_B64, Base64.URL_SAFE)
    }

    // ================================================================
    //  密钥派生
    // ================================================================

    /** MD5(seed).toHex() → 32 字符 ASCII 字节 → 16 字节 AES-128 key。 */
    fun deriveKey(seed: String): ByteArray =
        hex(MessageDigest.getInstance("MD5").digest(seed.toByteArray(Charsets.UTF_8)))
            .toByteArray(Charsets.US_ASCII)

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX_DIGITS[(b.toInt() shr 4) and 0x0f])
            sb.append(HEX_DIGITS[b.toInt() and 0x0f])
        }
        return sb.toString()
    }

    // ================================================================
    //  AES-CBC 加密/解密（固定 IV）
    // ================================================================

    /** AES/CBC/PKCS5Padding 加密，输出 URL_SAFE Base64（无换行）。 */
    fun encrypt(plain: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance(AES_ALG)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(FIXED_IV))
        return Base64.encodeToString(cipher.doFinal(plain), Base64.URL_SAFE or Base64.NO_WRAP)
    }

    /** AES/CBC 解密，输入 URL_SAFE Base64，输出 UTF-8 字符串。 */
    fun decrypt(cipherB64: String, key: ByteArray): String {
        val cipher = Cipher.getInstance(AES_ALG)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(FIXED_IV))
        return String(cipher.doFinal(Base64.decode(cipherB64, Base64.URL_SAFE)), Charsets.UTF_8)
    }

    // ================================================================
    //  请求信封加密（envelope）
    // ================================================================

    /**
     * 生成随机种子 → deriveKey → 加密请求体 →
     * 种子用 DEFAULT_KEY1 加密 → 返回 {ciphertextB64, key, meta} JSON。
     */
    fun encryptRequest(plainParams: String): String {
        val seed = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val sessionKey = deriveKey(String(seed, Charsets.ISO_8859_1))
        val cipherB64 = encrypt(plainParams.toByteArray(Charsets.UTF_8), sessionKey)
        val encKey = encrypt(seed.copyOf(16), DEFAULT_KEY1)
        val meta = MessageDigest.getInstance("MD5")
            .digest((cipherB64 + encKey).toByteArray(Charsets.UTF_8))
            .let { hex(it).take(8) }
        return """{"ciphertextB64":"$cipherB64","key":"$encKey","meta":"$meta"}"""
    }

    // ================================================================
    //  CTROBF1 响应解密
    // ================================================================

    /** \u001eCTROBF1\u001f<长度>:<base64> */
    private val CTROBF_PATTERN = Regex("\u001eCTROBF1\u001f\\d+:(.+)")

    fun decryptCtrobf1(body: String, key: ByteArray): String? {
        val m = CTROBF_PATTERN.find(body) ?: return null
        return try { decrypt(m.groupValues[1], key) } catch (e: Exception) {
            XposedBridge.log("$TAG decryptCtrobf1: ${e.message}"); null
        }
    }

    // ================================================================
    //  调试自测
    // ================================================================

    fun testEncryptDecrypt() {
        val key = deriveKey("test-seed")
        val enc = encrypt("hello ctwing".toByteArray(Charsets.UTF_8), key)
        val dec = decrypt(enc, key)
        XposedBridge.log("$TAG self-test: ok=${
            dec == "hello ctwing"
        }")
    }
}