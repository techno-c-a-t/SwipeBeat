package com.technocat.slidebit

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class AudioPlayerEngine(private val context: Context) {

    private var player: ExoPlayer? = null

    init {
        player = ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    fun play(track: Track, startPositionMs: Long = 0L) {
        player?.let { p ->
            val mediaItem = MediaItem.fromUri(track.uri)
            p.setMediaItem(mediaItem)
            p.prepare()
            if (startPositionMs > 0) {
                p.seekTo(startPositionMs)
            }
            p.play()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stop() {
        player?.stop()
    }

    fun setVolume(volume: Float) {
        player?.volume = volume
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    fun getCurrentPosition(): Long {
        return player?.currentPosition ?: 0L
    }

    fun getDuration(): Long {
        return player?.duration ?: 0L
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun release() {
        player?.release()
        player = null
    }
}
