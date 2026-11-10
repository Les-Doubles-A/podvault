package com.podvault.app.ui.queue

import androidx.lifecycle.ViewModel
import com.podvault.app.PodVaultApplication

class QueueViewModel(app: PodVaultApplication) : ViewModel() {
    private val playback = app.playbackController

    val queue = playback.queue
    val playbackState = playback.state

    fun play(index: Int) = playback.playQueueItem(index)
    fun remove(index: Int) = playback.removeFromQueue(index)
    fun move(fromIndex: Int, toIndex: Int) = playback.moveInQueue(fromIndex, toIndex)
    fun clear() = playback.clearUpNext()
    fun togglePlayPause() = playback.togglePlayPause()
}
