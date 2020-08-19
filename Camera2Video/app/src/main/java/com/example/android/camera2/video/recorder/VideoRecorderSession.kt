package com.example.android.camera2.video.recorder

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.example.android.camera2.video.BuildConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.IllegalStateException
import java.lang.UnsupportedOperationException
import java.util.*

/**
 * 録画中をあらわす録画セッションクラス。
 * インスタンスを解放するまえに、必ず cancel または stopRecording を実行して処理を終了させること。
 */
interface VideoRecorderSession {
    /**
     * 録画停止しているか。stopまたはcancelするとtrueになります。
     */
    val isStopped: Boolean

    /**
     * 録画中断しているか。isStopped == false のときのみ意味があるパラメータです。
     */
    val isPaused: Boolean

    /**
     * 録画を中断する。
     *
     * @return 中断中にエラーがでた、もしくは中断できないステータスのときはfalse。
     * @throws UnsupportedOperationException Android N 未満での実行時
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun pause(): Boolean

    /**
     * 録画を再開する。
     *
     * @return 再開中にエラーがでた、もしくは中断できないステータスのときはfalse。
     * @throws UnsupportedOperationException Android N 未満での実行時
     */
    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun resume(): Boolean

    /**
     * レコーディングを停止して、出力ファイルを返す。
     * インスタンスあたりのoutputFileは固定(コンストラクタのconfiguration.outputFile)なので、このメソッドは常に同一ファイルインスタンスを返します。
     * @exception RecordingException 録画開始から終了が早すぎる場合に発生します。遅延することでなるべく出ないようにしていますが、負荷が高い場合などに起こる可能性がありそうです。また、すでに停止済みなどの状態異常時にも発生します。
     */
    suspend fun stopRecording(): File

    /**
     * 録画を中断して終了処理を行う。
     * すでに停止済やキャンセル済の場合もエラーせず動作します。
     */
    suspend fun cancel()
}
internal class VideoRecorderSessionImpl(
    private val context: Context,
    private val configuration: VideoRecorderConfiguration,
    private val mediaRecorderFactory: MediaRecorderFactory,
    private val recorderSurface: Surface,
    /** 利用側でプレビューなどに使うsurface群 */
    private val extraSurfaces: ArrayList<Surface>,
    private val session: CameraCaptureSession
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
            if (configuration.videoFps != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, configuration.videoFps)
        }.build()
    }

    override val isStopped: Boolean
        get() = recorder == null

    override var isPaused: Boolean = false
        private set

    suspend fun startRecording(orientationDegree: Int?) {
        mutex.withLock {
            if (recorder != null) throw IllegalStateException("Already started.")

            recorder = mediaRecorderFactory.create(configuration, recorderSurface)
            // camera-samples サンプルでは setRepeatingRequest は listener=nullでもhandlerを指定していますが、
            // ドキュメントからもAOSPソースからもhandlerはlistenerの反応スレッドを制御するためだけに使われるようなので、handlerは使わないことにしました。
            // 参考: https://github.com/android/camera-samples/blob/master/Camera2Video/app/src/main/java/com/example/android/camera2/video/fragments/CameraFragment.kt#L254
            // 参考: http://gerrit.aospextended.com/plugins/gitiles/AospExtended/platform_frameworks_base/+/25df673b849de374cf1de40250dfd8a48b7ac28b/core/java/android/hardware/camera2/impl/CameraDevice.java#269
            // Start recording repeating requests, which will stop the ongoing preview
            // repeating requests without having to explicitly call `session.stopRepeating`
            // session.setRepeatingRequest(recordRequest, null, handler)
            session.setRepeatingRequest(recordRequest, null, null)

            // Finalizes recorder setup and starts recording
            recorder?.apply {
                // Sets output orientation based on current sensor value at start time
                orientationDegree?.let { setOrientationHint(it) }
                prepare()
                start()
            }
            recordingStartMillis = System.currentTimeMillis()
        }
    }

    private val mutex = Mutex()

    @Suppress("UNREACHABLE_CODE")
    override suspend fun pause(): Boolean {
        // MediaRecorder.pause() は Android N 以上でないと使えない機能
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw UnsupportedOperationException()
        }

        return mutex.withLock {
            withSafeRecorder("pause") { recorder ->
                recorder.pause()
                isPaused = true
            }
        }
    }

    @Suppress("UNREACHABLE_CODE")
    override suspend fun resume(): Boolean {
        // MediaRecorder.resume() は Android N 以上でないと使えない機能
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw UnsupportedOperationException()
        }

        return mutex.withLock {
            withSafeRecorder("resume") { recorder ->
                recorder.resume()
                isPaused = false
            }
        }
    }

    /**
     * recorderを取得してfnを実行する。recorder==nullの場合や例外が出たときは黙ってfalseを返す。
     */
    private fun withSafeRecorder(operationDescription: String, fn: (recorder: MediaRecorder) -> Unit): Boolean {
        try {
            val recorder = recorder ?: return false
            fn(recorder)
            return true
        } catch (e: Throwable) { // IllegalStateException
            log("Failed to $operationDescription: $e")
            log(e)
            return false
        }
    }

    override suspend fun stopRecording(): File {
        mutex.withLock {
            // 録画開始後に即時終了するとRuntimeExceptionが出るため、例外をできるだけ出さない対策。
            // 早すぎる場合は少し待つ。
            // Requires recording of at least MIN_REQUIRED_RECORDING_TIME_MILLIS
            val elapsedTimeMillis = System.currentTimeMillis() - recordingStartMillis
            if (elapsedTimeMillis < MIN_REQUIRED_RECORDING_TIME_MILLIS) {
                delay(MIN_REQUIRED_RECORDING_TIME_MILLIS - elapsedTimeMillis)
            }

            val recorder = recorder ?: throw RecordingException("Missing MediaRecorder(maybe already stopped)")
            try {
                recorder.stop()
            } catch(e: java.lang.IllegalStateException) {
                throw RecordingException(e.message, e)
            } catch(e: RuntimeException) {
                throw RecordingException("MediaRecorder stopped too early. ${e.message}", e)
            } finally {
                releaseRecorder()
            }

            return configuration.outputFile
        }
    }

    override suspend fun cancel() {
        // 終了処理はコルーチンをキャンセルさせたくない。
        withContext(NonCancellable) {
            mutex.withLock {
                if (recorder == null) {
                    //　すでに終了済みなら何もしなくてよい。
                    return@withLock
                }

                //　キャンセル処理
                withSafeRecorder("cancel") {
                    recorder?.stop()
                }
                // 中断後、ゴミが残らないようにstopの成否によらずファイルを消しておく。
                try {
                    configuration.outputFile.delete()
                } catch (_: Exception) {
                    // 削除失敗は気にしない。
                }

                releaseRecorder()
            }
        }
    }

    private fun releaseRecorder() {
        recorder?.release()
        recorder = null
    }

    companion object {
        private val TAG = VideoRecorderSession::class.java.simpleName
        private const val MIN_REQUIRED_RECORDING_TIME_MILLIS: Long = 1000L
    }
    private fun log(msg: String) {
        if (!BuildConfig.DEBUG) { return }
        Log.d(TAG, msg)
    }
    private fun log(e: Throwable) {
        if (!BuildConfig.DEBUG) { return }
        e.printStackTrace()
    }
}
