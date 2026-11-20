package app.retrolauncher.ra

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the RetroAchievements account. RA asks for the Web API key to be kept like a password,
 * so it's encrypted with an AES key held by the Android Keystore, which never leaves the device;
 * only the encrypted form is stored. A backup restored onto another device can't be decrypted,
 * so it reads as signed out and you sign in again.
 */
class RaAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("retroachievements", Context.MODE_PRIVATE)

    fun load(): RaAccount? {
        val user = prefs.getString(USER, null) ?: return null
        val key = prefs.getString(KEY, null)?.let(::open) ?: return null
        return RaAccount(user, key)
    }

    fun save(account: RaAccount) = prefs.edit {
        putString(USER, account.user)
        putString(KEY, seal(account.key))
    }

    fun clear() = prefs.edit { clear() }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    /** Base64 of the 12-byte IV followed by the ciphertext and tag. */
    private fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun open(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, raw, 0, 12)) }
        cipher.doFinal(raw, 12, raw.size - 12).decodeToString()
    } catch (_: Exception) {
        null // from another device, or the keystore was reset: sign in again
    }

    private companion object {
        const val ALIAS = "retroachievements_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val USER = "user"
        const val KEY = "key_sealed"
    }
}
