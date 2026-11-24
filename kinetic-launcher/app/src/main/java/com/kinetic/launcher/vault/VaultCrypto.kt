package com.kinetic.launcher.vault

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The Vault's encryption.
 *
 * - Your files are encrypted with a random 256-bit **vault key** (AES-GCM).
 * - The vault key is locked twice: first with a key made from your **Kinetic code**
 *   (PIN or pattern, stretched with PBKDF2), then with a key that lives inside the
 *   phone's security chip (Android Keystore) and can never leave it.
 * - So copied files are useless without both this exact phone and your code, and
 *   guessing the code on the phone is slowed down by lock-outs.
 *
 * The code is Kinetic's own — separate from your lock screen, fingerprints or face.
 */
object VaultCrypto {

    enum class Kind { Pin, Pattern }

    private const val PREFS = "kinetic_vault"
    private const val KEYSTORE_ALIAS = "kinetic_vault_wrap"
    private const val ITERATIONS = 120_000
    private const val CHUNK = 512 * 1024
    private val MAGIC = byteArrayOf('K'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSetUp(context: Context) = prefs(context).contains("wrapped")

    fun kind(context: Context): Kind =
        if (prefs(context).getString("kind", "pin") == "pattern") Kind.Pattern else Kind.Pin

    // ---- setting / checking the code -------------------------------------------------

    /** First-time setup: makes a new vault key and locks it with [code]. */
    fun setUp(context: Context, code: String, kind: Kind) {
        val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
        store(context, master, code, kind)
        ensureDropBox(context, SecretKeySpec(master, "AES"))
        master.fill(0)
    }

    // ---- drop box: lets secure screenshots go IN without the code --------------------
    //
    // A key pair: the public half (stored openly) can only *lock* things; the private
    // half is itself locked with the vault key. So a screenshot can be sealed while the
    // Vault is locked, but nobody can open it again without the Kinetic code.

    private val DROP_MAGIC = byteArrayOf('K'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"

    /** True once screenshots can be dropped in without the code. */
    fun canDrop(context: Context) = prefs(context).contains("drop_pub")

    /** Creates the drop-box key pair if it doesn't exist yet (needs the unlocked vault key). */
    fun ensureDropBox(context: Context, key: SecretKey) {
        if (canDrop(context)) return
        val pair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        prefs(context).edit()
            .putString("drop_pub", b64(pair.public.encoded))
            .putString("drop_priv", b64(gcmEncrypt(key, pair.private.encoded)))
            .apply()
    }

    /** Seals [input] so only the unlocked Vault can open it. No code needed. */
    fun dropEncrypt(context: Context, input: InputStream, output: OutputStream) {
        val pubBytes = unb64(prefs(context).getString("drop_pub", null) ?: error("No drop box"))
        val pub = java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))
        val fileKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val c = Cipher.getInstance(RSA)
        c.init(Cipher.ENCRYPT_MODE, pub)
        val wrapped = c.doFinal(fileKey.encoded)
        val out = DataOutputStream(output)
        out.write(DROP_MAGIC)
        out.writeInt(wrapped.size)
        out.write(wrapped)
        encryptStream(fileKey, "drop", input, out)
        out.flush()
    }

    /** Opens a file written by [dropEncrypt], using the unlocked vault [key]. */
    fun dropDecrypt(context: Context, key: SecretKey, input: InputStream, output: OutputStream) {
        val privBytes = gcmDecrypt(key, unb64(prefs(context).getString("drop_priv", null) ?: error("No drop box")))
        val priv = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privBytes))
        privBytes.fill(0)
        val inp = DataInputStream(input)
        val magic = ByteArray(4)
        inp.readFully(magic)
        require(magic.contentEquals(DROP_MAGIC)) { "Not a drop file" }
        val wrapped = ByteArray(inp.readInt().also { require(it in 1..2048) })
        inp.readFully(wrapped)
        val c = Cipher.getInstance(RSA)
        c.init(Cipher.DECRYPT_MODE, priv)
        val fileKey = SecretKeySpec(c.doFinal(wrapped), "AES")
        decryptStream(fileKey, "drop", inp, output)
    }

    /** Changes the code (needs the current one). False if [oldCode] is wrong. */
    fun changeCode(context: Context, oldCode: String, newCode: String, kind: Kind): Boolean {
        val key = unlock(context, oldCode) ?: return false
        val master = key.encoded
        store(context, master, newCode, kind)
        master.fill(0)
        return true
    }

    /** Locks the (already unlocked) vault key with a new code. */
    fun rewrap(context: Context, key: SecretKey, newCode: String, kind: Kind) {
        val master = key.encoded
        store(context, master, newCode, kind)
        master.fill(0)
    }

    private fun store(context: Context, master: ByteArray, code: String, kind: Kind) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kek = derive(code, salt)
        val inner = gcmEncrypt(kek, master)
        val outer = gcmEncrypt(keystoreKey(), inner)
        prefs(context).edit()
            .putString("salt", b64(salt))
            .putString("wrapped", b64(outer))
            .putString("kind", if (kind == Kind.Pattern) "pattern" else "pin")
            .putInt("fails", 0)
            .putLong("locked_until", 0L)
            .apply()
    }

    /** Milliseconds until another try is allowed (0 = now). */
    fun lockedFor(context: Context): Long =
        (prefs(context).getLong("locked_until", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Checks [code] and returns the vault key, or null if it's wrong (or tries are
     * locked out). Takes about half a second on purpose (it's what slows guessing).
     */
    fun unlock(context: Context, code: String, allowDecoy: Boolean = true): SecretKey? {
        if (lockedFor(context) > 0) return null
        val p = prefs(context)
        val salt = unb64(p.getString("salt", null) ?: return null)
        val outer = unb64(p.getString("wrapped", null) ?: return null)
        return try {
            val inner = gcmDecrypt(keystoreKey(), outer)
            val master = gcmDecrypt(derive(code, salt), inner)
            p.edit().putInt("fails", 0).putLong("locked_until", 0L).apply()
            lastWasDecoy = false
            SecretKeySpec(master, "AES")
        } catch (e: Exception) {
            // The decoy code opens a separate, harmless Vault (and nothing else).
            if (allowDecoy) {
                tryDecoy(p, code)?.let {
                    p.edit().putInt("fails", 0).putLong("locked_until", 0L).apply()
                    lastWasDecoy = true
                    return it
                }
            }
            // Wrong code: after 5 misses, wait 30 s, then 1 min, 2 min… up to 15 min.
            val fails = p.getInt("fails", 0) + 1
            val wait = if (fails >= 5) (30_000L shl (fails - 5).coerceAtMost(5)).coerceAtMost(15 * 60_000L) else 0L
            p.edit().putInt("fails", fails).putLong("locked_until", if (wait > 0) System.currentTimeMillis() + wait else 0L).apply()
            null
        }
    }

    fun failedTries(context: Context) = prefs(context).getInt("fails", 0)

    // ---- decoy code -------------------------------------------------------------------

    /** True right after the decoy code (not the real one) was entered. */
    @Volatile
    var lastWasDecoy = false
        private set

    fun hasDecoy(context: Context) = prefs(context).contains("d_wrapped")

    private fun tryDecoy(p: SharedPreferences, code: String): SecretKey? = runCatching {
        val salt = unb64(p.getString("d_salt", null) ?: return null)
        val outer = unb64(p.getString("d_wrapped", null) ?: return null)
        val inner = gcmDecrypt(keystoreKey(), outer)
        SecretKeySpec(gcmDecrypt(derive(code, salt), inner), "AES")
    }.getOrNull()

    private fun matchesReal(context: Context, code: String): Boolean = runCatching {
        val p = prefs(context)
        val salt = unb64(p.getString("salt", null)!!)
        val inner = gcmDecrypt(keystoreKey(), unb64(p.getString("wrapped", null)!!))
        gcmDecrypt(derive(code, salt), inner)
        true
    }.getOrDefault(false)

    /** Sets the decoy code. False if it's the same as the real code. */
    fun setUpDecoy(context: Context, code: String): Boolean {
        if (matchesReal(context, code)) return false
        val master = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val inner = gcmEncrypt(derive(code, salt), master)
        val outer = gcmEncrypt(keystoreKey(), inner)
        master.fill(0)
        prefs(context).edit().putString("d_salt", b64(salt)).putString("d_wrapped", b64(outer)).apply()
        return true
    }

    fun removeDecoy(context: Context) {
        prefs(context).edit().remove("d_salt").remove("d_wrapped").apply()
        java.io.File(context.filesDir, "vault_decoy").deleteRecursively()
    }

    /** Turns a pattern (dots 0–8 in order) into a code string. */
    fun patternCode(dots: List<Int>) = "pattern:" + dots.joinToString("-")

    private fun derive(code: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        fun make(strongBox: Boolean): SecretKey {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            )
            return gen.generateKey()
        }
        // The S25 Ultra has a StrongBox security chip; fall back to the regular keystore otherwise.
        return runCatching { make(true) }.getOrElse { make(false) }
    }

    private fun gcmEncrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    private fun gcmDecrypt(key: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    // ---- files (encrypted in 512 KB chunks so big videos never sit in memory) --------

    /** Encrypts everything from [input] into [output]. [tag] ties the file to its item. */
    fun encryptStream(key: SecretKey, tag: String, input: InputStream, output: OutputStream) {
        val out = DataOutputStream(output)
        out.write(MAGIC)
        val buf = ByteArray(CHUNK)
        var index = 0
        while (true) {
            var n = 0
            while (n < CHUNK) {
                val r = input.read(buf, n, CHUNK - n)
                if (r < 0) break
                n += r
            }
            if (n == 0 && index > 0) break
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            c.updateAAD("$tag#$index".toByteArray())
            val sealed = c.doFinal(buf, 0, n)
            out.write(c.iv)
            out.writeInt(sealed.size)
            out.write(sealed)
            index++
            if (n < CHUNK) break
        }
        out.flush()
    }

    /** Decrypts a file written by [encryptStream] into [output]. Throws if it was tampered with. */
    fun decryptStream(key: SecretKey, tag: String, input: InputStream, output: OutputStream) {
        val inp = DataInputStream(input)
        val magic = ByteArray(4)
        inp.readFully(magic)
        require(magic.contentEquals(MAGIC)) { "Not a vault file" }
        var index = 0
        while (true) {
            val iv = ByteArray(12)
            try {
                inp.readFully(iv)
            } catch (_: EOFException) {
                break
            }
            val len = inp.readInt()
            val sealed = ByteArray(len)
            inp.readFully(sealed)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            c.updateAAD("$tag#$index".toByteArray())
            output.write(c.doFinal(sealed))
            index++
        }
        output.flush()
    }
}
