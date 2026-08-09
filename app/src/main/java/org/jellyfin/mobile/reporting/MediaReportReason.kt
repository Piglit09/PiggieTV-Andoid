package org.jellyfin.mobile.reporting

enum class MediaReportReason(val wireName: String, val displayName: String) {
    SOUND_SYNC("sound-sync", "Sound sync"),
    AUDIO_LANGUAGE("audio-language", "Audio language"),
    PLAYBACK("playback", "Playback problem"),
    WRONG_MOVIE("wrong-movie", "Wrong movie"),
    SUBTITLES_WRONG("wrong-subtitles", "Wrong subtitles"),
    SUBTITLE_SYNC("subtitle-sync", "Subtitle sync"),
    CUSTOM("custom", "Other"),
    ;

    val requiresDetails: Boolean
        get() = this == CUSTOM
}
