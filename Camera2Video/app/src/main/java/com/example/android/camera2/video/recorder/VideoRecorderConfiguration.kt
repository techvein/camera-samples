package com.example.android.camera2.video.recorder

import android.util.Size
import java.io.File

data class VideoRecorderConfiguration (
    val videoSize: Size,
    val videoFps: Int,
    val withAudio: Boolean,
    val outputFile: File
)