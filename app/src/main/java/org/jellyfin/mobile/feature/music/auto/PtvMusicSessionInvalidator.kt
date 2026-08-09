package org.jellyfin.mobile.feature.music.auto

class PtvMusicSessionInvalidator(private val resumeStore: PtvMusicAutoResumeStore) {
    @Volatile
    private var stopPlayback: ((String) -> Unit)? = null

    fun attach(stopPlayback: (String) -> Unit) {
        this.stopPlayback = stopPlayback
    }

    fun detach() {
        stopPlayback = null
    }

    fun invalidate(reason: String) {
        resumeStore.clearPlaybackState()
        stopPlayback?.invoke(reason)
    }
}
