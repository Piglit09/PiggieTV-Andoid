package org.jellyfin.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.Constants

class MainViewModel(app: Application, private val apiClientController: ApiClientController,) : AndroidViewModel(app) {
    private val _serverState: MutableStateFlow<ServerState> = MutableStateFlow(ServerState.Pending)
    val serverState: StateFlow<ServerState> get() = _serverState

    init {
        viewModelScope.launch {
            refreshServer()
        }
    }

    suspend fun switchServer(hostname: String) {
        apiClientController.setupServer(hostname.ifBlank { Constants.PIGGIETV_DEFAULT_SERVER_URL })
        refreshServer()
    }

    private suspend fun refreshServer() {
        var serverEntity = apiClientController.loadSavedServer()

        if (serverEntity == null) {
            apiClientController.setupServer(Constants.PIGGIETV_DEFAULT_SERVER_URL)
            serverEntity = apiClientController.loadSavedServer()
        }

        _serverState.value = serverEntity?.let { entity -> ServerState.Available(entity) } ?: ServerState.Unset
    }

    /**
     * Temporarily unset the selected server to be able to connect to a different one
     */
    fun resetServer() {
        _serverState.value = ServerState.Unset
    }
}

sealed class ServerState {
    open val server: ServerEntity? = null

    object Pending : ServerState()
    object Unset : ServerState()
    class Available(override val server: ServerEntity) : ServerState()
}
