package com.dualamp.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.dualamp.audio.DspProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dualamp.LauncherActivity
import com.dualamp.library.LibraryRepository
import com.dualamp.library.Song
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background playback with a media notification, lock-screen controls and headset buttons. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? {
                // DualAmp's own DSP (equalizer, tone, width, limiter) runs inside the sink.
                val sink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(DspProcessor()))
                    .build()
                return TapAudioSink(sink)
            }
        }.setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // A PC song that can't load almost always means the PC went away: tell the user.
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                if (id < 0) com.dualamp.library.RemoteLibrary.reportProblem(
                    "A song from your PC couldn't play: DualAmp couldn't reach the PC.", always = true,
                )
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, LauncherActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Items sent from a controller lose their URI; rebuild it from the MediaStore id.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        val id = item.mediaId.toLongOrNull()
                        if (item.localConfiguration == null && id != null) {
                            // PC songs (negative ids) stream from their server address.
                            val uri = if (id < 0) LibraryRepository.library.value?.songById?.get(id)?.uri else Song.contentUri(id)
                            if (uri != null) item.buildUpon().setUri(uri).build() else item
                        } else item
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // One screen was swiped away in Recents: the two screens are one app, so the other one closes too
        // (backs up the screens' own onDestroy, which some launchers' Recents skip).
        com.dualamp.AppEvents.taskRemoved()
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
