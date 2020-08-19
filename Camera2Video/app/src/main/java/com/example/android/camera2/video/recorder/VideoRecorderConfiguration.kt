package com.example.android.camera2.video.recorder

import android.util.Range
import android.util.Size
import java.io.File

data class VideoRecorderConfiguration (
    /** ビデオ録画サイズ */
    val videoSize: Size,
    /** ビデオ録画FPS。VideoCameraHelper::getFps()で計算可能です。 */
    val videoFps: Range<Int>?,
    /** 音声録画するか */
    val withAudio: Boolean,
    /** 出力ファイルパス */
    val outputFile: File
)
