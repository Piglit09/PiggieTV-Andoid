package org.jellyfin.mobile.reporting

data class MediaReportTarget(
    val itemId: String,
    val title: String,
    val subtitle: String?,
    val type: String?,
    val source: String,
    val userName: String? = null,
    val playbackPositionMs: Long? = null,
    val mediaSourceId: String? = null,
    val playMethod: String? = null,
)
