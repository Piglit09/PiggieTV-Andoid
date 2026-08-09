package org.jellyfin.mobile.reporting

data class MediaReportTarget(
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val type: String?,
    val source: MediaReportSource,
    val playbackPositionMs: Long? = null,
    val mediaSourceId: String? = null,
    val playMethod: String? = null,
)

enum class MediaReportSource(val wireName: String) {
    NATIVE_HOME("native-home"),
    PLAYBACK("playback"),
}
