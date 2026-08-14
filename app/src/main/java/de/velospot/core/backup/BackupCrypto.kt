package de.velospot.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure, Android-free password-based encryption for a VeloSpot backup container.
 *
 * A backup is normally a plain ZIP. When the user supplies a passphrase, the whole
 * ZIP is wrapped with **AES-256-GCM**, keyed from the passphrase via
 * `PBKDF2WithHmacSHA256` (210 000 iterations, 256-bit key). Salt and IV are random
 * per backup, so encrypting the same data twice yields different containers.
 *
 * Container layout (all binary, no base64):
 * ```
 * magic "VSBKENC1" (8 ASCII bytes) | version (1 byte) | salt (16) | iv (12) | ciphertext+tag
 * ```
 *
 * A legacy plain backup starts with the ZIP local-file magic `PK\x03\x04`, which can
 * never collide with [MAGIC], so [isEncryptedContainer] cleanly distinguishes the two.
 *
 * Only `javax.crypto` / `java.security` are used, so the whole thing is JVM-unit-testable.
 */
object BackupCrypto {

    /** 8 ASCII bytes prefixing every encrypted container. */
    private val MAGIC = "VSBKENC1".toByteArray(Charsets.US_ASCII)

    /** Container format version (bumped only on an incompatible layout change). */
    private const val VERSION: Byte = 1

    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256

    private const val HEADER_LENGTH = 8 + 1 + SALT_LENGTH + IV_LENGTH // magic + version + salt + iv

    /** Encrypts [plaintext] with [passphrase], returning a self-describing container. */
    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArray(HEADER_LENGTH + ciphertext.size).also { out ->
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
            out[MAGIC.size] = VERSION
            System.arraycopy(salt, 0, out, MAGIC.size + 1, SALT_LENGTH)
            System.arraycopy(iv, 0, out, MAGIC.size + 1 + SALT_LENGTH, IV_LENGTH)
            System.arraycopy(ciphertext, 0, out, HEADER_LENGTH, ciphertext.size)
        }
    }

    /**
     * Decrypts a container produced by [encrypt]. Returns `null` — never throws — on a
     * wrong passphrase, a tampered/truncated container, or an unrecognised magic/version.
     */
    fun decrypt(container: ByteArray, passphrase: String): ByteArray? {
        if (!isEncryptedContainer(container)) return null
        if (container.size < HEADER_LENGTH) return null
        if (container[MAGIC.size] != VERSION) return null

        return try {
            val salt = container.copyOfRange(MAGIC.size + 1, MAGIC.size + 1 + SALT_LENGTH)
            val iv = container.copyOfRange(MAGIC.size + 1 + SALT_LENGTH, HEADER_LENGTH)
            val ciphertext = container.copyOfRange(HEADER_LENGTH, container.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            // AEADBadTagException (wrong passphrase / tamper) and any other failure ⇒ null.
            null
        }
    }

    /** True iff [bytes] starts with the encrypted-container [MAGIC]. */
    fun isEncryptedContainer(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return false
        return true
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

