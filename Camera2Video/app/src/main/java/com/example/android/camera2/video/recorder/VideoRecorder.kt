package com.example.android.camera2.video.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.android.camera.utils.OrientationLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 録画制御のためのインスタンス。
 * カメラが開いて閉じるまでの間のみ利用可能で、録画前にsetupを呼ぶ必要があります。
 * また、録画の利用には VideoRecorder::requiredPermissions(withAudio: Boolean) で得られる権限が必要です(withAudio=trueなら音声ありの権限、falseなら音声なしの権限)。
 *
 */
class VideoRecorder (
    private val context: Context,
    /** ビデオ録画サイズ */
    videoSize: Size,
    /** ビデオ録画FPS。VideoCameraHelper::getFps()で計算可能です。 */
    videoFps: Int,
    /** 音声録画するか */
    withAudio: Boolean,
    /** 出力ファイルパス。省略時は自動で生成します。 */
    outputFile: File? = null
) {
    /** [HandlerThread] where all camera operations run */
    private val backgroundThread = HandlerThread("VideoCallbackThread").apply { start() }
    /** [Handler] corresponding to [cameraThread] */
    private val handler  = Handler(backgroundThread.looper)

    private val configuration: VideoRecorderConfiguration = VideoRecorderConfiguration(
            videoSize = videoSize,
            videoFps = videoFps,
            withAudio = withAudio,
            outputFile = outputFile ?: createFile(context)
    )

    /** 利用側でプレビューなどに使うsurface群 */
    private var extraSurfaces = ArrayList<Surface>()
    private lateinit var session: CameraCaptureSession
    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    private val mediaRecorderFactory = MediaRecorderFactory()

    /**
     * Setup a persistent [Surface] for the recorder so we can use it as an output target for the
     * camera session without preparing the recorder
     */
    val recorderSurface: Surface by lazy {

        // Get a persistent Surface from MediaCodec, don't forget to release when done
        val surface = MediaCodec.createPersistentInputSurface()

        // Prepare and release a dummy MediaRecorder with our new surface
        // Required to allocate an appropriately sized buffer before passing the Surface as the
        //  output target to the capture session
        mediaRecorderFactory.create(configuration, surface, dummy = true)

        surface
    }

    suspend fun startRecordingSession(): VideoRecorderSession {
        val recorderSession = VideoRecorderSessionImpl(context, configuration, mediaRecorderFactory, handler, recorderSurface, extraSurfaces, session, relativeOrientation)
        recorderSession.startRecording()
        return recorderSession
    }

    fun release() {
        recorderSurface.release()
        backgroundThread.quitSafely()
    }

    fun setup(session: CameraCaptureSession, extraSurfaces: List<Surface> = emptyList(), relativeOrientation: OrientationLiveData) {
        this.extraSurfaces = ArrayList(extraSurfaces)
        this.session = session
        this.relativeOrientation = relativeOrientation
    }

    /** 権限が足りているかのチェックをして、1つでも足りてなければ false を返す。 */
    fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * このインスタンス設定で必要なパーミッション一覧を取得する。
     */
    fun requiredPermissions() = requiredPermissions(configuration.withAudio)

    companion object {
        private val TAG = VideoRecorder::class.java.simpleName

        /** Creates a [File] named with the current date and time */
        fun createFile(context: Context): File {
            val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
            return File(context.filesDir, "VID_${sdf.format(Date())}.$FILE_EXT")
        }

        /**
         * outputFile で使う拡張子。
         */
        @SuppressWarnings
        const val FILE_EXT = "mp4"

        /**
         * 必要なパーミッション。
         */
        fun requiredPermissions(withAudio: Boolean): Array<String> {
            return arrayOf(Manifest.permission.CAMERA) +
                    if(withAudio) { arrayOf(Manifest.permission.RECORD_AUDIO) } else emptyArray()
        }
    }
}