package com.example.android.camera2.video.recorder

import android.media.MediaRecorder
import android.view.Surface
import java.io.File

internal class MediaRecorderFactory() {
    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    fun create(configuration: VideoRecorderConfiguration, inputSurface: Surface, outputFile: File? = null, dummy: Boolean = false) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(configuration.outputFile.absolutePath)
        setVideoEncodingBitRate(VIDEO_BITRATE)
        if (configuration.videoFps > 0) setVideoFrameRate(configuration.videoFps)
        setVideoSize(configuration.videoSize.width, configuration.videoSize.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (configuration.withAudio) {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setAudioSamplingRate(AUDIO_SAMPLING_RATE)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        setInputSurface(inputSurface)

        if (dummy) {
            prepare()
            release()
        }
    }

    companion object {
        const val VIDEO_BITRATE: Int = 10_000_000
        const val AUDIO_SAMPLING_RATE: Int = 16_000
    }
}