package com.example.android.camera2.video.recorder

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Handler
import android.util.Log
import android.util.Range
import android.view.Surface
import com.example.android.camera.utils.OrientationLiveData
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.lang.Exception
import java.lang.RuntimeException
import java.util.*

/**
 * 録画中をあらわす録画セッションクラス。
 * インスタンスを解放するまえに、必ず cancel または stopRecording を実行して処理を終了させること。
 */
interface VideoRecorderSession {
    /**
     * 録画停止する。
     */
    suspend fun stopRecording(): File

    /**
     * 録画を中断する。
     */
    fun cancel()
}
internal class VideoRecorderSessionImpl(
    private val context: Context,
    private val configuration: VideoRecorderConfiguration,
    private val mediaRecorderFactory: MediaRecorderFactory,
    private val handler: Handler,
    private val recorderSurface: Surface,
    /** 利用側でプレビューなどに使うsurface群 */
    private val extraSurfaces: ArrayList<Surface>,
    private val session: CameraCaptureSession,
    /** Live data listener for changes in the device orientation relative to the camera */
    private val relativeOrientation: OrientationLiveData
): VideoRecorderSession {

    private var recordingStartMillis: Long = 0L

    private var recorder: MediaRecorder? = null

    /** Requests used for preview and recording in the [CameraCaptureSession] */
    private val recordRequest: CaptureRequest by lazy {
        // Capture request holds references to target surfaces
        session.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            // Add the recording surface and extra surfaces targets
            extraSurfaces.forEach { addTarget(it) }
            addTarget(recorderSurface)
            // Sets user requested FPS for all targets
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(configuration.videoFps, configuration.videoFps))
        }.build()
    }

    suspend fun startRecording() {
        mutex.withLock {
            recorder = mediaRecorderFactory.create(configuration, recorderSurface)
            log("startRecording(): start")
            // camera-samples サンプルでは setRepeatingRequest は listener=nullでもhandlerを指定していますが、
            // ドキュメントからもAOSPソースからもhandlerはlistenerの反応スレッドを制御するためだけに使われるようなので、handlerは使わないことにしました。
            // 参考: https://github.com/android/camera-samples/blob/master/Camera2Video/app/src/main/java/com/example/android/camera2/video/fragments/CameraFragment.kt#L254
            // 参考: http://gerrit.aospextended.com/plugins/gitiles/AospExtended/platform_frameworks_base/+/25df673b849de374cf1de40250dfd8a48b7ac28b/core/java/android/hardware/camera2/impl/CameraDevice.java#269
            // Start recording repeating requests, which will stop the ongoing preview
            // repeating requests without having to explicitly call `session.stopRepeating`
            // session.setRepeatingRequest(recordRequest, null, handler)
            // session.setRepeatingRequest(recordRequest, null, null)
            session.setRepeatingRequest(recordRequest, object: CameraCaptureSession.CaptureCallback() {
                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    log("onCaptureSequenceAborted($session, $sequenceId)")
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    log("onCaptureCompleted($session, $request, $result)")
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    log("onCaptureFailed($session, $request, $failure)")
                }

                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    log("onCaptureSequenceCompleted($session, $sequenceId, $frameNumber)")
                }

                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    log("onCaptureStarted($session, $request, $timestamp, $frameNumber)")
                }

                override fun onCaptureProgressed(session: CameraCaptureSession, request: CaptureRequest, partialResult: CaptureResult) {
                    log("onCaptureProgressed($session, $request, $partialResult)")
                }

                override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                    log("onCaptureBufferLost($session, $request, $target, $frameNumber)")
                }
            }, handler)

            // Finalizes recorder setup and starts recording
            recorder?.apply {
                // Sets output orientation based on current sensor value at start time
                relativeOrientation.value?.let { setOrientationHint(it) }
                prepare()
                start()
            }
            recordingStartMillis = System.currentTimeMillis()
            log("startRecording(): end")
        }
    }

    private val mutex = Mutex()

    /**
     * レコーディングを停止して、出力ファイルを返す。
     * インスタンスあたりのoutputFileは固定(コンストラクタでの指定またはインスタンス化時の自動生成)なので、このメソッドは常に同一ファイルインスタンスを返します。
     * @exception RecordingException 録画開始から終了が早すぎる場合に発生します。遅延することでなるべく出ないようにしていますが、負荷が高い場合などに起こる可能性がありそうです。
     */
    override suspend fun stopRecording(): File {
        mutex.withLock {
            log("stopRecording(): start")
            // 録画開始後に即時終了するとRuntimeExceptionが出るため、例外をできるだけ出さない対策。
            // 早すぎる場合は少し待つ。
            // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
            val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }

            try {
                recorder?.stop()
            } catch(e: RuntimeException) {
                throw RecordingException("MediaRecorder stopped too early. ${e.message}", e)
            } finally {
                release()
            }

            // Broadcasts the media file to the rest of the system
            MediaScannerConnection.scanFile(
                    context, arrayOf(configuration.outputFile.absolutePath), null, null)

            log("stopRecording(): end")
            return configuration.outputFile
        }
    }

    /** レコーディングを中断する。 */
    override fun cancel() {
        try {
            recorder?.stop()
        } catch (e: RuntimeException) {
            // 早すぎるストップ時に RuntimeException を起こすが、キャンセル時は何も行う必要がないので無視する。
            // 詳細はMediaRecorder.stop() のドキュメント参照。
            log("Suppress RuntimeException caused by MediaRecorder.stop(): $e")
        }
        release()
    }

    private fun release() {
        recorder?.release()
        recorder = null
    }

    companion object {
        private val TAG = VideoRecorderSession::class.java.simpleName
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L
    }
    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
}

