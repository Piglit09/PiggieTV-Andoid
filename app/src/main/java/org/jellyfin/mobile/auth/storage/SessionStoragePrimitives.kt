package org.jellyfin.mobile.auth.storage

import android.content.Context
import android.content.SharedPreferences

internal interface SessionKeyValueStore {
    fun getString(key: String): String?

    fun keys(): Set<String>

    fun commit(
        puts: Map<String, String> = emptyMap(),
        removals: Set<String> = emptySet(),
        clear: Boolean = false,
    ): Boolean
}

internal class SharedPreferencesSessionKeyValueStore(private val preferences: SharedPreferences) :
    SessionKeyValueStore {
    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun keys(): Set<String> = preferences.all.keys

    override fun commit(puts: Map<String, String>, removals: Set<String>, clear: Boolean): Boolean =
        preferences.edit().run {
            if (clear) clear()
            removals.forEach(::remove)
            puts.forEach { (key, value) -> putString(key, value) }
            commit()
        }
}

internal fun Context.sessionKeyValueStore(name: String): SessionKeyValueStore =
    SharedPreferencesSessionKeyValueStore(applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

internal data class SecureSessionStorageConfig(
    val metadataPreferencesName: String,
    val credentialPreferencesName: String,
    val keyMaterialPreferencesName: String,
    val clearGuardPreferencesName: String,
    val modernKeyAlias: String,
    val legacyRsaKeyAlias: String,
    val clearGuardKeyAlias: String,
) {
    companion object {
        val PRODUCTION = SecureSessionStorageConfig(
            metadataPreferencesName = "ptv_mobile_session_metadata",
            credentialPreferencesName = "ptv_mobile_session_credentials",
            keyMaterialPreferencesName = "ptv_mobile_session_key_material",
            clearGuardPreferencesName = "ptv_mobile_auth_guard",
            modernKeyAlias = "piggietv_mobile_session_token_v1",
            legacyRsaKeyAlias = "piggietv_mobile_session_token_legacy_rsa_v1",
            clearGuardKeyAlias = "piggietv_mobile_session_clear_pending_v1",
        )
    }
}
