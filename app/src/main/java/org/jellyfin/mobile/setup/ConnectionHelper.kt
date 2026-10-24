package org.jellyfin.mobile.setup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.discovery.LocalServerDiscovery
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import timber.log.Timber

class ConnectionHelper(private val context: Context, private val jellyfin: Jellyfin) {
    @Suppress("LongMethod")
    suspend fun checkServerUrl(enteredUrl: String): CheckUrlState {
        Timber.i("Checking entered server address")

        val candidates = withContext(Dispatchers.IO) {
            jellyfin.discovery.getAddressCandidates(enteredUrl)
        }
        Timber.i("Server discovery produced %d candidates", candidates.size)

        // Find servers and classify them into groups.
        // BAD servers are collected in case we need an error message,
        // GOOD are kept if there's no GREAT one.
        val badServers = mutableListOf<RecommendedServerInfo>()
        val goodServers = mutableListOf<RecommendedServerInfo>()
        val greatServer = withContext(Dispatchers.IO) {
            jellyfin.discovery.getRecommendedServers(candidates)
        }.firstOrNull { recommendedServer ->
            when (recommendedServer.score) {
                RecommendedServerInfoScore.GREAT -> true

                RecommendedServerInfoScore.GOOD -> {
                    goodServers += recommendedServer
                    false
                }

                RecommendedServerInfoScore.OK,
                RecommendedServerInfoScore.BAD,
                -> {
                    badServers += recommendedServer
                    false
                }
            }
        }

        val server = greatServer ?: goodServers.firstOrNull()
        if (server != null) {
            val systemInfo = requireNotNull(server.systemInfo)
            val serverVersion = systemInfo.getOrNull()?.version
            Timber.i("Found valid server rating=%s version=%s", server.score, serverVersion)
            return CheckUrlState.Success(server.address)
        }

        // No valid server found, log and show error message
        Timber.i("No valid server found invalidCandidateCount=%d", badServers.size)

        val error = when {
            badServers.isNotEmpty() -> {
                val count = badServers.size
                val (unreachableServers, incompatibleServers) = badServers.partition { result ->
                    result.systemInfo.getOrNull() ==
                        null
                }

                StringBuilder().apply {
                    append(context.resources.getQuantityString(R.plurals.connection_error_prefix, count, count))
                    if (unreachableServers.isNotEmpty()) {
                        append("\n\n")
                        append(context.getString(R.string.connection_error_unable_to_reach_sever))
                        append(":\n")
                        append(
                            unreachableServers.joinToString(separator = "\n") { result ->
                                "\u00b7 ${result.address.secretSafeServerLabel()}"
                            },
                        )
                    }
                    if (incompatibleServers.isNotEmpty()) {
                        append("\n\n")
                        append(context.getString(R.string.connection_error_unsupported_version_or_product))
                        append(":\n")
                        append(
                            incompatibleServers.joinToString(separator = "\n") { result ->
                                "\u00b7 ${result.address.secretSafeServerLabel()}"
                            },
                        )
                    }
                }.toString()
            }

            else -> null
        }

        return CheckUrlState.Error(error)
    }

    private fun String.secretSafeServerLabel(): String = runCatching {
        val uri = android.net.Uri.parse(this)
        val host = uri.host ?: return@runCatching "Server address"
        buildString {
            uri.scheme?.let { scheme -> append(scheme).append("://") }
            append(host)
            if (uri.port >= 0) append(':').append(uri.port)
            uri.path?.takeIf(String::isNotBlank)?.let(::append)
        }
    }.getOrDefault("Server address")

    fun discoverServersAsFlow(): Flow<ServerDiscoveryInfo> = jellyfin.discovery
        .discoverLocalServers(maxServers = LocalServerDiscovery.DISCOVERY_MAX_SERVERS)
        .flowOn(Dispatchers.IO)
}
