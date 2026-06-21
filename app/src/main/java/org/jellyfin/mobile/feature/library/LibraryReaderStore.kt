@file:Suppress("FunctionExpressionBody", "FunctionSignature")

package org.jellyfin.mobile.feature.library

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class LibraryReaderStore(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): LibraryReaderSettings =
        sharedPreferences.getString(KEY_SETTINGS, null)
            ?.let(LibraryReaderSettingsSerializer::decode)
            ?: LibraryReaderSettings()

    fun saveSettings(settings: LibraryReaderSettings) {
        sharedPreferences.edit {
            putString(KEY_SETTINGS, LibraryReaderSettingsSerializer.encode(settings))
        }
    }

    fun loadResume(readerKey: String): LibraryReaderResumeState? =
        sharedPreferences.getString(resumeKey(readerKey), null)
            ?.let(LibraryReaderResumeStateSerializer::decode)

    fun saveResume(state: LibraryReaderResumeState) {
        sharedPreferences.edit {
            putString(resumeKey(state.readerKey), LibraryReaderResumeStateSerializer.encode(state))
        }
    }

    fun clearResume(readerKey: String) {
        sharedPreferences.edit { remove(resumeKey(readerKey)) }
    }

    fun loadFavoriteKeys(): Set<String> =
        sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    fun isFavorite(readerKey: String): Boolean = readerKey in loadFavoriteKeys()

    fun setFavorite(readerKey: String, favorite: Boolean) {
        val updated = loadFavoriteKeys().toMutableSet().apply {
            if (favorite) add(readerKey) else remove(readerKey)
        }
        sharedPreferences.edit { putStringSet(KEY_FAVORITES, updated) }
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
    Integer.toHexString(hashCode())
