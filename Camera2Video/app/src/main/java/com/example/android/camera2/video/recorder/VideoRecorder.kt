package com.example.android.camera2.video.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.media.MediaCodec
import android.view.Surface
import androidx.core.content.ContextCompat
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
    private val configuration: VideoRecorderConfiguration
) {
    /** 利用側でプレビューなどに使うsurface群 */
    private var extraSurfaces = ArrayList<Surface>()
    private lateinit var session: CameraCaptureSession

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

    suspend fun startRecordingSession(orientationDegree: Int?): VideoRecorderSession {
        val recorderSession = VideoRecorderSessionImpl(context, configuration, mediaRecorderFactory, recorderSurface, extraSurfaces, session)
        recorderSession.startRecording(orientationDegree)
        return recorderSession
    }

    fun release() {
        recorderSurface.release()
    }

    fun setup(session: CameraCaptureSession, extraSurfaces: List<Surface> = emptyList()) {
        this.extraSurfaces = ArrayList(extraSurfaces)
        this.session = session
    }

    /** 権限が足りているかのチェックをして、1つでも足りてなければ false を返す。 */
    fun hasEnoughPermissions() = getRequiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * このインスタンス設定で必要なパーミッション一覧を取得する。
     */
    fun getRequiredPermissions() = getRequiredPermissions(configuration.withAudio)

    companion object {
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
        fun getRequiredPermissions(withAudio: Boolean): Array<String> {
            return arrayOf(Manifest.permission.CAMERA) +
                    if(withAudio) { arrayOf(Manifest.permission.RECORD_AUDIO) } else emptyArray()
        }

        /** 権限が足りているかのチェックをして、1つでも足りてなければ false を返す。 */
        fun hasEnoughPermissions(context: Context, withAudio: Boolean) = getRequiredPermissions(withAudio = withAudio).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}