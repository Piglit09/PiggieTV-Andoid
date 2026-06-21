@file:Suppress(
    "ArgumentListWrapping",
    "BinaryExpressionWrapping",
    "ClassSignature",
    "FunctionExpressionBody",
    "FunctionLiteral",
    "FunctionSignature",
    "MaximumLineLength",
    "ParameterListWrapping",
)

package org.jellyfin.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val readerStore: LibraryReaderStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> get() = _uiState

    fun load(force: Boolean = false) {
        if (!force && _uiState.value is LibraryUiState.Content) return

        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            runCatching {
                repository.loadHome()
            }.onSuccess { home ->
                val enrichedHome = home.withLocalReadingState()
                _uiState.value = LibraryUiState.Content(home = enrichedHome)
                loadExtras(enrichedHome)
            }.onFailure { error ->
                _uiState.value = when (error) {
                    is LibraryLoginRequiredException -> LibraryUiState.LoginRequired
                    else -> LibraryUiState.Error(error.message ?: "Could not reach the Library server.")
                }
            }
        }
    }

    private fun loadExtras(home: LibraryHome) {
        viewModelScope.launch {
            runCatching {
                repository.loadHomeExtras(home)
            }.onSuccess { updatedHome ->
                val content = _uiState.value as? LibraryUiState.Content ?: return@onSuccess
                _uiState.value = content.copy(home = updatedHome.withLocalReadingState())
            }
        }
    }

    fun selectBook(book: LibraryBook) {
        viewModelScope.launch {
            val content = _uiState.value as? LibraryUiState.Content ?: return@launch
            val localBook = book.withLocalReadingState()
            _uiState.value = content.copy(selectedBook = localBook, isLoadingDetail = true)
            runCatching {
                repository.loadBookDetail(book)
            }.onSuccess { detail ->
                val updatedHome = content.home.withUpdatedBook(detail.withLocalReadingState())
                _uiState.value = content.copy(
                    home = updatedHome,
                    selectedBook = detail.withLocalReadingState(),
                    isLoadingDetail = false,
                )
            }.onFailure { error ->
                _uiState.value = if (error is LibraryLoginRequiredException) {
                    LibraryUiState.LoginRequired
                } else {
                    content.copy(selectedBook = localBook, isLoadingDetail = false)
                }
            }
        }
    }

    fun closeBook() {
        val content = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = content.copy(selectedBook = null, isLoadingDetail = false)
    }

    fun clearProgress(book: LibraryBook) {
        readerStore.clearResume(book.readerKey)
        refreshLocalReadingState()
    }

    fun toggleFavorite(book: LibraryBook) {
        readerStore.setFavorite(book.readerKey, !book.isFavorite)
        refreshLocalReadingState()
    }

    private fun refreshLocalReadingState() {
        val content = _uiState.value as? LibraryUiState.Content ?: return
        val updatedHome = content.home.withLocalReadingState()
        _uiState.value = content.copy(
            home = updatedHome,
            selectedBook = content.selectedBook?.let { selected ->
                updatedHome.allBooks.firstOrNull { book -> book.readerKey == selected.readerKey }
                    ?: selected.withLocalReadingState()
            },
        )
    }

    private fun LibraryHome.withUpdatedBook(updatedBook: LibraryBook): LibraryHome =
        copy(
            allBooks = allBooks.map { book -> if (book.readerKey == updatedBook.readerKey) updatedBook else book },
            recentBooks = recentBooks.map { book -> if (book.readerKey == updatedBook.readerKey) updatedBook else book },
        ).withLocalReadingState()

    private fun LibraryHome.withLocalReadingState(): LibraryHome {
        val enrichedBooks = allBooks.map { book -> book.withLocalReadingState() }
        val byKey = enrichedBooks.associateBy(LibraryBook::readerKey)
        val enrichedRecent = recentBooks.map { book ->
            byKey[book.readerKey] ?: book.withLocalReadingState()
        }

        return copy(
            allBooks = enrichedBooks,
            recentBooks = enrichedRecent,
            continueReading = enrichedBooks
                .filter { book -> book.progress != null }
                .sortedByDescending { book -> book.progress?.updatedAtMs ?: 0L },
            favorites = enrichedBooks.filter(LibraryBook::isFavorite),
            comics = enrichedBooks.filter { book -> book.readingKind == LibraryReadingKind.COMIC },
            manga = enrichedBooks.filter { book -> book.readingKind == LibraryReadingKind.MANGA },
            comicsManga = enrichedBooks.filter { book -> book.readingKind in setOf(LibraryReadingKind.COMIC, LibraryReadingKind.MANGA) },
        )
    }

    private fun LibraryBook.withLocalReadingState(): LibraryBook =
        withReadingState(
            resumeState = readerStore.loadResume(readerKey),
            favorite = isFavorite || readerStore.isFavorite(readerKey),
        )
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object LoginRequired : LibraryUiState
    data class Content(
        val home: LibraryHome,
        val selectedBook: LibraryBook? = null,
        val isLoadingDetail: Boolean = false,
    ) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}
