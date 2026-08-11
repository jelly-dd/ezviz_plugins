package com.example.ezviz_plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Environment
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.videogo.openapi.EZConstants
import com.videogo.openapi.EZOpenSDK
import com.videogo.openapi.EZOpenSDKListener
import com.videogo.openapi.EZPlayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.File
import java.util.concurrent.Executors

/// 单个萤石摄像头画面的 PlatformView。
///
/// 内部持有一个原生 SurfaceView，用 EZPlayer 在上面渲染实时画面。
/// 通过 per-view 的 MethodChannel（com.example.matter/ezviz_player_<id>）
/// 接收 Flutter 的 start/stop 指令，并把播放状态回调回 Flutter。
class EzvizPlayerView(
    context: Context,
    id: Int,
    messenger: BinaryMessenger,
    creationParams: Map<*, *>?,
) : PlatformView {

    private val surfaceView = SurfaceView(context)
    private val appContext = context.applicationContext
    private val channel = MethodChannel(messenger, "com.example.matter/ezviz_player_$id")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var player: EZPlayer? = null
    private var surfaceReady = false
    private var pendingPlay = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var recording = false
    private var currentRecordPath: String? = null

    private val deviceSerial = creationParams?.get("deviceSerial") as? String
    private val cameraNo = (creationParams?.get("cameraNo") as? Number)?.toInt() ?: 1
    private val isDeviceTalkBack = creationParams?.get("isDeviceTalkBack") as? Boolean ?: true
    private val verifyCode = creationParams?.get("verifyCode") as? String
    private val autoPlay = creationParams?.get("autoPlay") as? Boolean ?: true

    private val playerHandler = Handler(Looper.getMainLooper()) { msg: Message ->
        when (msg.what) {
            EZConstants.EZRealPlayConstants.MSG_REALPLAY_PLAY_SUCCESS ->
                channel.invokeMethod("onPlaySuccess", null)
            EZConstants.EZRealPlayConstants.MSG_REALPLAY_PLAY_FAIL ->
                channel.invokeMethod(
                    "onPlayFail",
                    mapOf("code" to (msg.obj?.toString() ?: "unknown")),
                )
            EZConstants.MSG_VIDEO_SIZE_CHANGED -> {
                val size = (msg.obj as? String)?.split(':')
                videoWidth = size?.getOrNull(0)?.toIntOrNull() ?: videoWidth
                videoHeight = size?.getOrNull(1)?.toIntOrNull() ?: videoHeight
            }
        }
        true
    }

    init {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startRealPlay" -> {
                    startRealPlay()
                    result.success(true)
                }
                "stopRealPlay" -> {
                    stopRealPlay()
                    result.success(true)
                }
                "openSound" -> {
                    result.success(player?.openSound() ?: false)
                }
                "closeSound" -> {
                    result.success(player?.closeSound() ?: false)
                }
                "startVoiceTalk" -> {
                    player?.closeSound()
                    result.success(player?.startVoiceTalk(isDeviceTalkBack) ?: false)
                }
                "stopVoiceTalk" -> {
                    result.success(player?.stopVoiceTalk() ?: false)
                }
                "setDigitalZoom" -> {
                    result.success(setDigitalZoom(call.argument<Boolean>("enabled") == true))
                }
                "capturePicture" -> capturePicture(result)
                "startLocalRecord" -> startLocalRecord(result)
                "stopLocalRecord" -> {
                    result.success(stopLocalRecord())
                }
                else -> result.notImplemented()
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                player?.setSurfaceHold(holder)
                if (pendingPlay || autoPlay) {
                    pendingPlay = false
                    startRealPlay()
                }
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                player?.setSurfaceHold(null)
            }
        })
    }

    override fun getView(): View = surfaceView

    override fun dispose() {
        channel.setMethodCallHandler(null)
        player?.stopVoiceTalk()
        if (recording) player?.stopLocalRecord()
        stopRealPlay()
        player?.let { EZOpenSDK.getInstance().releasePlayer(it) }
        player = null
        io.shutdown()
    }

    private fun startRealPlay() {
        val serial = deviceSerial ?: return
        if (player == null) {
            player = EZOpenSDK.getInstance().createPlayer(serial, cameraNo)
            player?.setHandler(playerHandler)
            if (!verifyCode.isNullOrEmpty()) {
                player?.setPlayVerifyCode(verifyCode)
            }
        }
        if (!surfaceReady) {
            pendingPlay = true
            return
        }
        player?.setSurfaceHold(surfaceView.holder)
        player?.startRealPlay()
    }

    private fun stopRealPlay() {
        player?.stopRealPlay()
    }

    private fun setDigitalZoom(enabled: Boolean): Boolean {
        val currentPlayer = player ?: return false
        if (!enabled) {
            return currentPlayer.setDisplayRegion(-1, -1, -1, -1)
        }
        if (videoWidth <= 0 || videoHeight <= 0) return false
        return currentPlayer.setDisplayRegion(
            (videoWidth / 4).toLong(),
            (videoHeight / 4).toLong(),
            (videoWidth * 3 / 4).toLong(),
            (videoHeight * 3 / 4).toLong(),
        )
    }

    private fun capturePicture(result: MethodChannel.Result) {
        val currentPlayer = player
        if (currentPlayer == null) {
            result.error("player_unavailable", "播放器尚未创建", null)
            return
        }
        val outputFile = mediaFile(Environment.DIRECTORY_PICTURES, "jpg")
        io.execute {
            val code = currentPlayer.capturePicture(outputFile.absolutePath)
            main.post {
                if (code == 0) {
                    result.success(outputFile.absolutePath)
                } else {
                    result.error("capture_failed", "截图失败，错误码: $code", null)
                }
            }
        }
    }

    private fun startLocalRecord(result: MethodChannel.Result) {
        val currentPlayer = player
        if (currentPlayer == null) {
            result.error("player_unavailable", "播放器尚未创建", null)
            return
        }
        if (recording) {
            result.success(currentRecordPath)
            return
        }
        val outputFile = mediaFile(Environment.DIRECTORY_MOVIES, "mp4")
        currentPlayer.setStreamDownloadCallback(
            object : EZOpenSDKListener.EZStreamDownloadCallback {
                override fun onSuccess(filepath: String?) {
                    recording = false
                    currentRecordPath = filepath
                    main.post { channel.invokeMethod("onRecordComplete", filepath) }
                }

                override fun onError(code: EZOpenSDKListener.EZStreamDownloadError?) {
                    recording = false
                    main.post {
                        channel.invokeMethod("onRecordFail", code?.name ?: "unknown")
                    }
                }
            },
        )
        if (!currentPlayer.startLocalRecordWithFile(outputFile.absolutePath)) {
            result.error("record_start_failed", "无法开始本地录像", null)
            return
        }
        recording = true
        currentRecordPath = outputFile.absolutePath
        result.success(outputFile.absolutePath)
    }

    private fun stopLocalRecord(): Boolean {
        if (!recording) return false
        return player?.stopLocalRecord() ?: false
    }

    private fun mediaFile(directory: String, extension: String): File {
        val root = appContext.getExternalFilesDir(directory) ?: appContext.filesDir
        val folder = File(root, "ezviz").apply { mkdirs() }
        return File(folder, "${System.currentTimeMillis()}.$extension")
    }
}
