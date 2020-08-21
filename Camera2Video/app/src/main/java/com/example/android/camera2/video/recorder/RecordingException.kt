package com.example.android.camera2.video.recorder

/**
 * 録画失敗例外。早すぎる stop() 時に発生する。
 */
class RecordingException(
    message: String? = null,
    cause: RuntimeException? = null)
    : Throwable(message ?: cause?.toString(), cause)
