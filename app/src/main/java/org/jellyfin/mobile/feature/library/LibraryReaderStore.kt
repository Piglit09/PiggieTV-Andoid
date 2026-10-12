@file:Suppress("FunctionExpressionBody", "FunctionSignature")

package org.jellyfin.mobile.feature.library

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class LibraryReaderStore(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        purgeUnsafePersistentState()
    }

    fun loadSettings(): LibraryReaderSettings =
        sharedPreferences.getString(KEY_SETTINGS, null)
            ?.let(LibraryReaderSettingsSerializer::decode)
            ?: LibraryReaderSettings()

    fun saveSettings(settings: LibraryReaderSettings) {
        sharedPreferences.edit {
            putString(KEY_SETTINGS, LibraryReaderSettingsSerializer.encode(settings))
        }
    }

    fun loadResume(readerKey: String): LibraryReaderResumeState? {
        if (!readerKey.isSafePersistentReaderKey()) return null
        val storageKey = resumeKey(readerKey)
        val encoded = sharedPreferences.getString(storageKey, null) ?: return null
        val state = LibraryReaderResumeStateSerializer.decode(encoded)
        if (state?.readerKey != readerKey) {
            removeUnsafeResumeAndVerify(storageKey)
            return null
        }
        return state
    }

    fun saveResume(state: LibraryReaderResumeState) {
        if (!state.readerKey.isSafePersistentReaderKey()) return
        sharedPreferences.edit {
            putString(resumeKey(state.readerKey), LibraryReaderResumeStateSerializer.encode(state))
        }
    }

    fun clearResume(readerKey: String) {
        if (!readerKey.isSafePersistentReaderKey()) return
        sharedPreferences.edit { remove(resumeKey(readerKey)) }
    }

    fun loadFavoriteKeys(): Set<String> {
        val stored = sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        val safe = stored.safePersistentReaderKeys()
        if (safe.size != stored.size) {
            writeSafeFavoritesAndVerify(safe)
        }
        return safe
    }

    fun isFavorite(readerKey: String): Boolean =
        readerKey.isSafePersistentReaderKey() && readerKey in loadFavoriteKeys()

    fun setFavorite(readerKey: String, favorite: Boolean) {
        if (!readerKey.isSafePersistentReaderKey()) return
        val updated = loadFavoriteKeys().toMutableSet().apply {
            if (favorite) add(readerKey) else remove(readerKey)
        }
        sharedPreferences.edit { putStringSet(KEY_FAVORITES, updated) }
    }

    private fun purgeUnsafePersistentState() {
        val resumeKeysToRemove = mutableListOf<String>()
        val resumeValuesToRewrite = mutableMapOf<String, String>()
        sharedPreferences.all.entries
            .filter { (key) -> key.startsWith(KEY_RESUME_PREFIX) }
            .forEach { (key, value) ->
                val encoded = value as? String
                val state = encoded?.let(LibraryReaderResumeStateSerializer::decode)
                if (state == null || !state.readerKey.isSafePersistentReaderKey()) {
                    resumeKeysToRemove += key
                } else {
                    val canonical = LibraryReaderResumeStateSerializer.encode(state)
                    if (canonical != encoded) resumeValuesToRewrite[key] = canonical
                }
            }
        val storedFavorites = sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        val safeFavorites = storedFavorites.safePersistentReaderKeys()
        if (
            resumeKeysToRemove.isEmpty() &&
            resumeValuesToRewrite.isEmpty() &&
            safeFavorites.size == storedFavorites.size
        ) {
            return
        }

        val editor = sharedPreferences.edit().apply {
            resumeKeysToRemove.forEach { key -> remove(key) }
            resumeValuesToRewrite.forEach { (key, value) -> putString(key, value) }
            putStringSet(KEY_FAVORITES, safeFavorites)
        }
        val committed = editor.commit()
        val verified = resumeKeysToRemove.none(sharedPreferences::contains) &&
            resumeValuesToRewrite.all { (key, value) -> sharedPreferences.getString(key, null) == value } &&
            sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty() == safeFavorites
        check(committed && verified) { "Unsafe reader state could not be removed." }
    }

    private fun removeUnsafeResumeAndVerify(storageKey: String) {
        val committed = sharedPreferences.edit().remove(storageKey).commit()
        check(committed && !sharedPreferences.contains(storageKey)) {
            "Unsafe reader state could not be removed."
        }
    }

    private fun writeSafeFavoritesAndVerify(safeFavorites: Set<String>) {
        val committed = sharedPreferences.edit().putStringSet(KEY_FAVORITES, safeFavorites).commit()
        val verified = sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty() == safeFavorites
        check(committed && verified) { "Unsafe reader favorites could not be removed." }
    }

    private fun resumeKey(readerKey: String): String = "$KEY_RESUME_PREFIX${readerKey.stableStorageHash()}"

    private companion object {
        const val PREFS_NAME = "ptv_books_reader"
        const val KEY_SETTINGS = "settings"
        const val KEY_FAVORITES = "favorites"
        const val KEY_RESUME_PREFIX = "resume_"
    }
}

internal fun String.stableStorageHash(): String =
    sha256Hex()

internal fun Iterable<String>.safePersistentReaderKeys(): Set<String> =
    filterTo(mutableSetOf(), String::isSafePersistentReaderKey)
