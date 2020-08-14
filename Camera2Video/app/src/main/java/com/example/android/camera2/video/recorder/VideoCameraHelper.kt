package com.example.android.camera2.video.recorder

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.util.Size

/**
 * 録画カメラ用ヘルパ
 */
class VideoCameraHelper(
    private val cameraManager: CameraManager,
    val cameraId: String
) {
    private val characteristics by lazy { cameraManager.getCameraCharacteristics(cameraId) }
    private val capabilities by lazy {
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
    }
    private val cameraConfig by lazy {
        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    }

    /**
     * 録画に使える(MediaRecorderに対応している)カメラか。
     */
    fun isRecordableCamera()
        = capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false

    /**
     * 指定サイズにおけるFPSを計算する。何らかの理由で計算できない場合は0を返す。
     */
    fun getFps(size: Size): Int {
        val cameraConfig = cameraConfig ?: return 0

        // Recording should always be done in the most efficient format, which is
        //  the format native to the camera framework
        val targetClass = MediaRecorder::class.java
        val secondsPerFrame =
                cameraConfig.getOutputMinFrameDuration(targetClass, size) /
                        1_000_000_000.0
        return if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0

    }
}