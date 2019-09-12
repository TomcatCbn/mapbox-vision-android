package com.mapbox.vision.session

import com.mapbox.vision.mobile.core.NativeVisionManager
import com.mapbox.vision.mobile.core.models.video.VideoClip
import com.mapbox.vision.mobile.core.models.video.VideoClipPro
import com.mapbox.vision.models.video.VideoCombined
import com.mapbox.vision.models.video.mapToTelemetry
import com.mapbox.vision.models.video.mapToVisionPro
import com.mapbox.vision.sync.telemetry.TelemetryImageSaverImpl
import com.mapbox.vision.utils.FileUtils
import com.mapbox.vision.utils.file.RotatedBuffers
import com.mapbox.vision.utils.threads.WorkThreadHandler
import com.mapbox.vision.video.videosource.camera.VideoRecorder
import java.io.File
import java.util.concurrent.TimeUnit

internal class RotatedBuffersSessionWriter(
    private val nativeVisionManager: NativeVisionManager,
    private val buffers: RotatedBuffers,
    private val rootCacheDir: String,
    private val videoRecorder: VideoRecorder,
    private val telemetryImageSaverImpl: TelemetryImageSaverImpl,
    private val sessionWriterListener: SessionWriterListener
) : SessionWriter {

    companion object {
        private val SESSION_LENGTH_MILLIS = TimeUnit.MINUTES.toMillis(5)
    }

    private val workingHandler = WorkThreadHandler("Session")
    private var sessionCacheDir: String = ""
    private var coreSessionStartMillis = 0L

    override fun start() {
        if (!workingHandler.isStarted()) {
            workingHandler.start()
            startSession()
        }
    }

    override fun stop() {
        if (workingHandler.isStarted()) {
            workingHandler.stop()
            stopSession()
        }
    }

    private fun startSession() {
        generateCacheDirForCurrentTime()
        videoRecorder.startRecording(buffers.getBuffer())
        nativeVisionManager.startTelemetrySavingSession(sessionCacheDir)
        telemetryImageSaverImpl.start(sessionCacheDir)

        coreSessionStartMillis =
            TimeUnit.SECONDS.toMillis(nativeVisionManager.getCoreTimeSeconds().toLong())

        workingHandler.postDelayed({
            stopSession()
            startSession()
        }, SESSION_LENGTH_MILLIS)
    }

    private fun stopSession() {
        videoRecorder.stopRecording()
        nativeVisionManager.stopTelemetrySavingSession()
        telemetryImageSaverImpl.stop()

        val clips = nativeVisionManager.getClips()
        nativeVisionManager.resetClips()

        val clipsPro = nativeVisionManager.getClipsPro()
        nativeVisionManager.resetClipsPro()

        sessionWriterListener.onSessionStop(
            clips = videosToCombined(clips, clipsPro),
            videoPath = buffers.getBuffer(),
            cachedTelemetryPath = sessionCacheDir,
            coreSessionStartMillis = coreSessionStartMillis
        )
        buffers.rotate()
    }

    private fun videosToCombined(clips: Array<VideoClip>, clipsPro: Array<VideoClipPro>): VideoCombined =
        VideoCombined(
            telemetries = clips.map { it.mapToTelemetry() }.toTypedArray().ifEmpty { null },
            visionPros = clipsPro.map { it.mapToVisionPro() }.toTypedArray().ifEmpty { null }
        )

    private fun generateCacheDirForCurrentTime() {
        sessionCacheDir =
            "${FileUtils.getAbsoluteDir(
                File(
                    rootCacheDir,
                    System.currentTimeMillis().toString()
                ).absolutePath
            )}/"
    }
}
