package com.dualamp.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.dualamp.audio.AudioTap
import java.nio.ByteBuffer

/** Wraps ExoPlayer's audio sink to copy every decoded buffer (and the playback clock) to [AudioTap]. */
@UnstableApi
class TapAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var isPcm = true
    private var lastBuffer: ByteBuffer? = null
    private var lastPts = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        isPcm = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW
        if (isPcm) AudioTap.configure(inputFormat.sampleRate, inputFormat.channelCount, inputFormat.pcmEncoding)
        lastBuffer = null
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // A buffer that was only partly consumed is handed in again; capture it once.
        if (isPcm && (buffer !== lastBuffer || presentationTimeUs != lastPts)) {
            lastBuffer = buffer
            lastPts = presentationTimeUs
            AudioTap.capture(buffer, presentationTimeUs)
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val pos = super.getCurrentPositionUs(sourceEnded)
        if (pos != AudioSink.CURRENT_POSITION_NOT_SET) AudioTap.onPosition(pos)
        return pos
    }

    override fun play() {
        super.play()
        AudioTap.playing = true
    }

    override fun pause() {
        super.pause()
        AudioTap.playing = false
    }

    override fun flush() {
        super.flush()
        lastBuffer = null
        AudioTap.flush()
    }

    override fun reset() {
        super.reset()
        AudioTap.playing = false
        AudioTap.flush()
    }
}
