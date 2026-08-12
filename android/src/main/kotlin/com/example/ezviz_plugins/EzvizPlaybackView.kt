package com.example.ezviz_plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.videogo.errorlayer.ErrorInfo
import com.videogo.openapi.EZConstants
import com.videogo.openapi.EZOpenSDK
import com.videogo.openapi.EZPlayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.util.Calendar
import java.util.concurrent.Executors

class EzvizPlaybackView(
    context: Context,
    id: Int,
    messenger: BinaryMessenger,
    creationParams: Map<*, *>?,
) : PlatformView {

    private val surfaceView = SurfaceView(context)
    private val channel = MethodChannel(messenger, "com.example.matter/ezviz_playback_$id")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val deviceSerial = creationParams?.get("deviceSerial") as? String
    private val cameraNo = (creationParams?.get("cameraNo") as? Number)?.toInt() ?: 1
    private val verifyCode = creationParams?.get("verifyCode") as? String
    private val startTime = (creationParams?.get("startTime") as? Number)?.toLong() ?: 0L
    private val endTime = (creationParams?.get("endTime") as? Number)?.toLong() ?: 0L
    private val autoPlay = creationParams?.get("autoPlay") as? Boolean ?: true

    private var player: EZPlayer? = null
    private var surfaceReady = false
    private var pendingPlay = false
    private var playing = false

    private val progressTask = object : Runnable {
        override fun run() {
            if (!playing) return
            player?.osdTime?.timeInMillis?.let { position ->
                channel.invokeMethod(
                    "onProgress",
                    mapOf("position" to position.coerceIn(startTime, endTime)),
                )
            }
            main.postDelayed(this, 1_000)
        }
    }

    private val playerHandler = Handler(Looper.getMainLooper()) { message: Message ->
        when (message.what) {
            EZConstants.EZPlaybackConstants.MSG_REMOTE_PLAYBACK_PLAY_PREPARED -> {
                channel.invokeMethod(
                    "onPrepared",
                    mapOf("streamFetchType" to (player?.streamFetchType ?: -1)),
                )
            }
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_PLAY_START,
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_PLAY_SUCCUSS,
            -> {
                playing = true
                main.removeCallbacks(progressTask)
                main.post(progressTask)
                channel.invokeMethod(
                    "onPrepared",
                    mapOf("streamFetchType" to (player?.streamFetchType ?: -1)),
                )
                channel.invokeMethod("onPlayStart", null)
            }
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_PLAY_FINISH -> {
                playing = false
                main.removeCallbacks(progressTask)
                player?.stopPlayback()
                channel.invokeMethod("onPlayFinish", null)
            }
            EZConstants.EZPlaybackConstants.MSG_REMOTE_PLAYBACK_RATE_LOWER ->
                channel.invokeMethod("onRateLower", null)
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_PLAY_FAIL,
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_CONNECTION_EXCEPTION,
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_ENCRYPT_PASSWORD_ERROR,
            EZConstants.EZPlaybackConstants.MSG_REMOTEPLAYBACK_PASSWORD_ERROR,
            -> {
                playing = false
                main.removeCallbacks(progressTask)
                val errorCode = (message.obj as? ErrorInfo)?.errorCode?.toString()
                    ?: message.what.toString()
                channel.invokeMethod("onPlayFail", mapOf("code" to errorCode))
            }
        }
        true
    }

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startPlayback" -> result.success(startPlayback())
                "stopPlayback" -> asyncResult(result) { player?.stopPlayback() ?: false }
                "pausePlayback" -> asyncResult(result) { player?.pausePlayback() ?: false }
                "resumePlayback" -> asyncResult(result) { player?.resumePlayback() ?: false }
                "seekPlayback" -> {
                    val target = (call.argument<Number>("time"))?.toLong()
                    if (target == null || target !in startTime..endTime) {
                        result.error("bad_args", "回放跳转时间超出录像范围", null)
                    } else {
                        asyncResult(result) {
                            player?.seekPlayback(calendarAt(target)) ?: false
                        }
                    }
                }
                "setPlaybackRate" -> {
                    val rate = (call.argument<Number>("rate"))?.toDouble()
                    val sdkRate = playbackRate(rate)
                    if (sdkRate == null) {
                        result.error("bad_args", "不支持的回放倍速: $rate", null)
                    } else {
                        asyncResult(result) { player?.setPlaybackRate(sdkRate) ?: false }
                    }
                }
                "openSound" -> result.success(player?.openSound() ?: false)
                "closeSound" -> result.success(player?.closeSound() ?: false)
                else -> result.notImplemented()
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                player?.setSurfaceHold(holder)
                if (pendingPlay || autoPlay) {
                    pendingPlay = false
                    startPlayback()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                player?.setSurfaceHold(null)
            }
        })
    }

    override fun getView(): View = surfaceView

    override fun dispose() {
        channel.setMethodCallHandler(null)
        playing = false
        main.removeCallbacks(progressTask)
        player?.stopPlayback()
        player?.let { EZOpenSDK.getInstance().releasePlayer(it) }
        player = null
        io.shutdown()
    }

    private fun startPlayback(): Boolean {
        val serial = deviceSerial ?: return false
        if (startTime <= 0 || endTime <= startTime) return false
        if (player == null) {
            player = EZOpenSDK.getInstance().createPlayer(serial, cameraNo)
            player?.setHandler(playerHandler)
            if (!verifyCode.isNullOrEmpty()) player?.setPlayVerifyCode(verifyCode)
        }
        if (!surfaceReady) {
            pendingPlay = true
            return true
        }
        player?.setSurfaceHold(surfaceView.holder)
        return player?.startPlayback(calendarAt(startTime), calendarAt(endTime)) ?: false
    }

    private fun asyncResult(result: MethodChannel.Result, action: () -> Boolean) {
        io.execute {
            try {
                val succeeded = action()
                main.post { result.success(succeeded) }
            } catch (error: Throwable) {
                main.post { result.error("playback_error", error.message ?: error.toString(), null) }
            }
        }
    }

    private fun playbackRate(rate: Double?): EZConstants.EZPlaybackRate? = when (rate) {
        0.5 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_2_1
        1.0 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_1
        2.0 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_2
        4.0 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_4
        8.0 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_8
        16.0 -> EZConstants.EZPlaybackRate.EZ_PLAYBACK_RATE_16
        else -> null
    }

    private fun calendarAt(timeInMillis: Long): Calendar =
        Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
}
