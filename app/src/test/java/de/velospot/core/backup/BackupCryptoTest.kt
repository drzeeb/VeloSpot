package de.velospot.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val passphrase = "correct horse battery staple"

    @Test
    fun `round-trip encrypt then decrypt returns the original`() {
        val plaintext = "VeloSpot backup payload — äöü 🚲".toByteArray(Charsets.UTF_8)
        val container = BackupCrypto.encrypt(plaintext, passphrase)

        val decrypted = BackupCrypto.decrypt(container, passphrase)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong passphrase returns null`() {
        val container = BackupCrypto.encrypt("secret".toByteArray(), passphrase)
        assertNull(BackupCrypto.decrypt(container, "wrong passphrase"))
    }

    @Test
    fun `a tampered byte returns null`() {
        val container = BackupCrypto.encrypt("secret".toByteArray(), passphrase)
        // Flip a bit in the ciphertext region (past the 37-byte header).
        val tampered = container.copyOf()
        val idx = tampered.size - 1
        tampered[idx] = (tampered[idx].toInt() xor 0x01).toByte()
        assertNull(BackupCrypto.decrypt(tampered, passphrase))
    }

    @Test
    fun `isEncryptedContainer is true for encrypted output and false for a plain zip`() {
        val container = BackupCrypto.encrypt("data".toByteArray(), passphrase)
        assertTrue(BackupCrypto.isEncryptedContainer(container))

        val zipHeader = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04, 0x14, 0x00)
        assertFalse(BackupCrypto.isEncryptedContainer(zipHeader))
    }

    @Test
    fun `empty or short input decrypts to null without throwing`() {
        assertNull(BackupCrypto.decrypt(ByteArray(0), passphrase))
        assertNull(BackupCrypto.decrypt("VSBK".toByteArray(Charsets.US_ASCII), passphrase))
        assertNull(BackupCrypto.decrypt("VSBKENC1".toByteArray(Charsets.US_ASCII), passphrase))
        assertFalse(BackupCrypto.isEncryptedContainer(ByteArray(0)))
    }

    @Test
    fun `encrypting the same data twice yields different containers`() {
        val plaintext = "same".toByteArray()
        val a = BackupCrypto.encrypt(plaintext, passphrase)
        val b = BackupCrypto.encrypt(plaintext, passphrase)
        assertFalse(a.contentEquals(b))
    }
}

