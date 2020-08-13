package com.example.android.camera2.video.recorder

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.android.camera.utils.OrientationLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class VideoRecorder (
    private val context: Context,
    /** ビデオ録画サイズ */
    videoSize: Size,
    /** ビデオ録画FPS */
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

    private var recorderSession: VideoRecorderSession? = null

    suspend fun startRecording() {
        val recorderSession = VideoRecorderSession(context, configuration, mediaRecorderFactory, handler, recorderSurface, extraSurfaces, session, relativeOrientation)
        recorderSession.startRecording()
        this.recorderSession = recorderSession
    }

    suspend fun stopRecording(): File {
        val recorderSession = recorderSession ?: throw IllegalStateException("recording not started")
        val res = recorderSession.stopRecording()
        this.recorderSession = null
        return res
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
    }
    private fun log(msg: String) {
        Log.d(TAG, msg)
    }
}