package com.example.ezviz_plugins

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.videogo.openapi.EZConstants
import com.videogo.openapi.EZOpenSDK
import com.videogo.openapi.EZPlayer
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

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
    private val channel = MethodChannel(messenger, "com.example.matter/ezviz_player_$id")
    private val main = Handler(Looper.getMainLooper())

    private var player: EZPlayer? = null
    private var surfaceReady = false
    private var pendingPlay = false

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
        stopRealPlay()
        player?.let { EZOpenSDK.getInstance().releasePlayer(it) }
        player = null
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
}
