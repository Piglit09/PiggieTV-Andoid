@file:Suppress("MaximumLineLength")

package org.jellyfin.mobile.feature.library

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LibraryReaderStoreCleanupTest {
    @Test
    fun `failed legacy signed URL cleanup fails closed`() {
        val context = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences("ptv_books_reader", Context.MODE_PRIVATE) } returns preferences
        every { preferences.all } returns mapOf("resume_legacy" to LEGACY_RESUME)
        every { preferences.getStringSet(any(), any()) } returns emptySet<String>()
        every { preferences.edit() } returns editor
        every { editor.remove("resume_legacy") } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns false
        every { preferences.contains("resume_legacy") } returns true

        assertThrows(IllegalStateException::class.java) { LibraryReaderStore(context) }
    }

    private companion object {
        const val LEGACY_RESUME =
            """{"readerKey":"https://host/book?X-Amz-Signature=secret","title":"Book","pageIndex":1,"pageCount":2,"progress":0.5,"updatedAtMs":42}"""
    }
}
