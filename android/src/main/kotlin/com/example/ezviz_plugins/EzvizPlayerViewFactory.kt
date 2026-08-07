package com.example.ezviz_plugins

import android.content.Context
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/// 萤石播放器 PlatformView 的工厂。
///
/// 在 EzvizPluginsPlugin.onAttachedToEngine 里注册，
/// Flutter 端用 AndroidView(viewType: "ezviz_player_view") 创建实例。
class EzvizPlayerViewFactory(
    private val messenger: BinaryMessenger,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, id: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *>
        return EzvizPlayerView(context, id, messenger, params)
    }
}
