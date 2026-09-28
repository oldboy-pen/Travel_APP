package com.example.myfirstapp.data.user

import android.os.Build
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 口令哈希：PBKDF2 + 随机盐
 *
 * 用 PBKDF2 而不是 SHA/MD5 是因为后者算得太快，拖库后可以被暴力跑穿；
 * PBKDF2 靠迭代次数把单次校验成本抬高（本机上约几十毫秒，用户无感）。
 *
 * 算法按系统版本挑：PBKDF2WithHmacSHA256 要 Android 8.0（API 26）才有，
 * 而本 App minSdk=24，所以 7.x 上退回全版本都支持的 SHA1 变体。
 * 账号是本机存储，算法随版本不同不影响使用。
 */
object PasswordHasher {

    private val ALGORITHM =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256"
        else "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 120_000   // 想更快就调小，安全下限建议不低于 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16

    /** 生成一个新的随机盐（注册时用） */
    fun newSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

    /** 计算哈希，返回 Base64 字符串 */
    fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val secret = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec)
        return Base64.encodeToString(secret.encoded, Base64.NO_WRAP)
            .also { spec.clearPassword() }
    }

    /** 校验密码；用 isEqual 做定长比较，避免按字节提前退出泄露信息 */
    fun verify(password: String, saltBase64: String, expectedHashBase64: String): Boolean {
        val actual = hash(password, Base64.decode(saltBase64, Base64.NO_WRAP))
        return MessageDigest.isEqual(actual.toByteArray(), expectedHashBase64.toByteArray())
    }
}
