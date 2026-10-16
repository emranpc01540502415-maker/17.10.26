package com.rinagift.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the customer's quick-login token in Android's encrypted
 * storage (backed by the hardware Keystore where available).
 * BiometricPrompt is used separately as a gate before reading it -
 * see MainActivity.unlockSmartLogin().
 */
object SmartLogin {

    private const val PREFS_NAME = "rina_secure_prefs"
    private const val KEY_TOKEN = "customer_token"
    private const val KEY_PHONE = "customer_phone"
    private const val KEY_DECLINED_AT = "declined_at"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun hasSavedLogin(context: Context): Boolean =
        try { prefs(context).contains(KEY_TOKEN) } catch (_: Exception) { false }

    fun saveLogin(context: Context, token: String, phone: String) {
        try {
            prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_PHONE, phone)
                .apply()
        } catch (_: Exception) {
            // If secure storage fails on some device, we simply skip smart
            // login for this session rather than crash the app.
        }
    }

    fun getToken(context: Context): String? =
        try { prefs(context).getString(KEY_TOKEN, null) } catch (_: Exception) { null }

    fun getPhone(context: Context): String? =
        try { prefs(context).getString(KEY_PHONE, null) } catch (_: Exception) { null }

    fun clearLogin(context: Context) {
        try { prefs(context).edit().clear().apply() } catch (_: Exception) {}
    }

    /** Remembers that the customer declined the quick-login prompt, so we
     * don't ask again every single app open - only after a cooldown. */
    fun markDeclined(context: Context) {
        context.getSharedPreferences("rina_prefs", Context.MODE_PRIVATE)
            .edit().putLong(KEY_DECLINED_AT, System.currentTimeMillis()).apply()
    }

    fun canAskAgain(context: Context): Boolean {
        if (hasSavedLogin(context)) return false
        val declinedAt = context.getSharedPreferences("rina_prefs", Context.MODE_PRIVATE)
            .getLong(KEY_DECLINED_AT, 0L)
        if (declinedAt == 0L) return true
        val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - declinedAt > sevenDaysMs
    }
}