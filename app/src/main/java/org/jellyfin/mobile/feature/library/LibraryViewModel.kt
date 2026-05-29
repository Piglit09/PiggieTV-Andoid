package org.jellyfin.mobile.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: LibraryRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> get() = _uiState

    fun load(force: Boolean = false) {
        if (!force && _uiState.value is LibraryUiState.Content) return

        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading
            runCatching {
                repository.loadHome()
            }.onSuccess { home ->
                _uiState.value = LibraryUiState.Content(home = home)
                loadExtras(home)
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
                _uiState.value = content.copy(home = updatedHome)
            }
        }
    }

    fun selectBook(book: LibraryBook) {
        viewModelScope.launch {
            val content = _uiState.value as? LibraryUiState.Content ?: return@launch
            _uiState.value = content.copy(selectedBook = book, isLoadingDetail = true)
            runCatching {
                repository.loadBookDetail(book)
            }.onSuccess { detail ->
                _uiState.value = content.copy(selectedBook = detail, isLoadingDetail = false)
            }.onFailure { error ->
                _uiState.value = if (error is LibraryLoginRequiredException) {
                    LibraryUiState.LoginRequired
                } else {
                    content.copy(selectedBook = book, isLoadingDetail = false)
                }
            }
        }
    }

    fun closeBook() {
        val content = _uiState.value as? LibraryUiState.Content ?: return
        _uiState.value = content.copy(selectedBook = null, isLoadingDetail = false)
    }
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
