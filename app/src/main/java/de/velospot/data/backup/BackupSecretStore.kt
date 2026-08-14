 package de.velospot.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore file backing [BackupSecretStore] (one tiny store, shared per process). */
private val Context.backupSecretDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "velospot_backup_secret"
)

/**
 * At-rest storage for the **automatic backup** passphrase.
 *
 * The scheduled automatic-backup worker runs unattended, so
 * it cannot prompt the user for a passphrase. Instead the passphrase is kept encrypted
 * on disk: a non-exportable **AES/GCM** key lives in the AndroidKeystore (alias
 * [KEY_ALIAS], generated lazily on first use), and only the resulting
 * `iv + ciphertext` (base64) is persisted in a small DataStore. The plaintext never
 * touches disk, and the key cannot leave the hardware-backed keystore.
 *
 * Every operation is robust: any keystore/decrypt failure degrades to
 * [getPassphrase] returning `null` (⇒ the worker simply skips its run) rather than
 * throwing. The security-crypto (Jetpack) library is deliberately avoided as deprecated.
 */
@Singleton
class BackupSecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Stores the passphrase encrypted with the keystore key. */
    suspend fun setPassphrase(passphrase: String) = withContext(Dispatchers.IO) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
            val ciphertext = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + ciphertext
            context.backupSecretDataStore.edit { prefs ->
                prefs[KEY_SECRET] = Base64.encodeToString(payload, Base64.NO_WRAP)
            }
        }
        Unit
    }

    /** Returns the stored passphrase, or `null` if absent or on any decrypt failure. */
    suspend fun getPassphrase(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val stored = context.backupSecretDataStore.data.first()[KEY_SECRET]
                ?: return@runCatching null
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH) return@runCatching null
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Whether a (non-blank) passphrase is currently stored. */
    suspend fun hasPassphrase(): Boolean = !getPassphrase().isNullOrBlank()

    /** Removes any stored passphrase. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching {
            context.backupSecretDataStore.edit { prefs -> prefs.remove(KEY_SECRET) }
        }
        Unit
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "velospot_backup_secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        val KEY_SECRET = stringPreferencesKey("backup_secret_passphrase")
    }
}

