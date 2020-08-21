package com.example.android.camera2.video.recorder

import android.media.MediaRecorder
import android.view.Surface
import java.io.File

/**
 * recorder パッケージ内で使うためのMediaRecorderインスタンス生成器
 */
internal class MediaRecorderFactory() {
    /** Creates a [MediaRecorder] instance using the provided [Surface] as input */
    fun create(configuration: VideoRecorderConfiguration, inputSurface: Surface, outputFile: File? = null, dummy: Boolean = false) = MediaRecorder().apply {
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        if (configuration.withAudio) {
            // setOutputFormat の前に呼ばなければならない
            setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(configuration.outputFile.absolutePath)
        setVideoEncodingBitRate(VIDEO_BITRATE)
        if (configuration.videoFps != null) setVideoFrameRate(configuration.videoFps.lower) // upper とどっちがいいか？
        setVideoSize(configuration.videoSize.width, configuration.videoSize.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (configuration.withAudio) {
            setAudioSamplingRate(AUDIO_SAMPLING_RATE)
            // setOutputFormat の後に呼ばなければならない
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