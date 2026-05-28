package org.jellyfin.mobile.reporting

enum class MediaReportReason(val displayName: String) {
    SOUND_SYNC("Sound Sync"),
    AUDIO_LANGUAGE("Audio language"),
    PLAYBACK("playback"),
    WRONG_MOVIE("wrong movie"),
    SUBTITLES_WRONG("subtitles wrong"),
    SUBTITLE_SYNC("subtittle sync"),
    CUSTOM("custom"),
    ;

    val requiresDetails: Boolean
        get() = this == CUSTOM
}
