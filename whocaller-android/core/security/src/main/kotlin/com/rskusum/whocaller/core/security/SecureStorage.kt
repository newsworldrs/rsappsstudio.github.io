package com.rskusum.whocaller.core.security

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Encrypted key–value storage for sensitive values (session tokens, entitlement cache). */
interface SecureStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun clear()
}

/**
 * Values are encrypted with [KeystoreCipher] before being written to a private SharedPreferences
 * file (excluded from backups, see `data_extraction_rules.xml`).
 */
@Singleton
class KeystoreSecureStorage @Inject constructor(
    @ApplicationContext context: Context,
) : SecureStorage {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher()

    override fun getString(key: String): String? =
        prefs.getString(key, null)?.let { cipher.decrypt(it) }

    override fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, cipher.encrypt(value)).apply()
        }
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val FILE = "whocaller_secure"
    }
}

/** Session token with expiry; expired tokens are treated as absent. */
class TokenStore @Inject constructor(private val storage: SecureStorage) {
    fun save(token: String, expiresAtMillis: Long) {
        storage.putString(KEY_TOKEN, token)
        storage.putString(KEY_EXPIRY, expiresAtMillis.toString())
    }

    fun validToken(nowMillis: Long): String? {
        val expiry = storage.getString(KEY_EXPIRY)?.toLongOrNull() ?: return null
        if (nowMillis >= expiry - EXPIRY_SKEW_MS) {
            clear()
            return null
        }
        return storage.getString(KEY_TOKEN)
    }

    fun clear() {
        storage.putString(KEY_TOKEN, null)
        storage.putString(KEY_EXPIRY, null)
    }

    private companion object {
        const val KEY_TOKEN = "session_token"
        const val KEY_EXPIRY = "session_expiry"
        const val EXPIRY_SKEW_MS = 60_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    abstract fun bindSecureStorage(impl: KeystoreSecureStorage): SecureStorage
}
