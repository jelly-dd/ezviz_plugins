import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 萤石实时画面播放组件。
///
/// 内部通过 [AndroidView] 嵌入原生 PlatformView（viewType: `ezviz_player_view`），
/// 由原生侧 `EzvizPlayerView` 用 EZPlayer 在 SurfaceView 上渲染视频。
class EzvizPlayer extends StatefulWidget {
  const EzvizPlayer({
    super.key,
    required this.deviceSerial,
    this.cameraNo = 1,
    this.verifyCode,
    this.isDeviceTalkBack = true,
    this.autoPlay = true,
    this.onPlaySuccess,
    this.onPlayFail,
    this.onRecordComplete,
    this.onRecordFail,
  });

  final String deviceSerial;
  final int cameraNo;
  final String? verifyCode;
  final bool isDeviceTalkBack;
  final bool autoPlay;
  final VoidCallback? onPlaySuccess;
  final void Function(String code)? onPlayFail;
  final void Function(String path)? onRecordComplete;
  final void Function(String code)? onRecordFail;

  @override
  State<EzvizPlayer> createState() => EzvizPlayerState();
}

class EzvizPlayerState extends State<EzvizPlayer> {
  MethodChannel? _channel;

  Future<void> startRealPlay() async {
    await _channel?.invokeMethod('startRealPlay');
  }

  Future<void> stopRealPlay() async {
    await _channel?.invokeMethod('stopRealPlay');
  }

  Future<void> openSound() async {
    await _channel?.invokeMethod('openSound');
  }

  Future<void> closeSound() async {
    await _channel?.invokeMethod('closeSound');
  }

  Future<bool> startVoiceTalk() async {
    return await _channel?.invokeMethod<bool>('startVoiceTalk') ?? false;
  }

  Future<bool> stopVoiceTalk() async {
    return await _channel?.invokeMethod<bool>('stopVoiceTalk') ?? false;
  }

  Future<bool> setDigitalZoom(bool enabled) async {
    return await _channel?.invokeMethod<bool>('setDigitalZoom', {
          'enabled': enabled,
        }) ??
        false;
  }

  Future<String> capturePicture() async {
    return await _channel?.invokeMethod<String>('capturePicture') ?? '';
  }

  Future<String> startLocalRecord() async {
    return await _channel?.invokeMethod<String>('startLocalRecord') ?? '';
  }

  Future<bool> stopLocalRecord() async {
    return await _channel?.invokeMethod<bool>('stopLocalRecord') ?? false;
  }

  void _onPlatformViewCreated(int id) {
    final channel = MethodChannel('com.example.matter/ezviz_player_$id');
    channel.setMethodCallHandler(_handleCall);
    _channel = channel;
  }

  Future<dynamic> _handleCall(MethodCall call) async {
    switch (call.method) {
      case 'onPlaySuccess':
        widget.onPlaySuccess?.call();
        break;
      case 'onPlayFail':
        final args = (call.arguments as Map?) ?? const {};
        widget.onPlayFail?.call(args['code']?.toString() ?? 'unknown');
        break;
      case 'onRecordComplete':
        widget.onRecordComplete?.call(call.arguments?.toString() ?? '');
        break;
      case 'onRecordFail':
        widget.onRecordFail?.call(call.arguments?.toString() ?? 'unknown');
        break;
    }
  }

  @override
  void dispose() {
    _channel?.setMethodCallHandler(null);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final creationParams = <String, dynamic>{
      'deviceSerial': widget.deviceSerial,
      'cameraNo': widget.cameraNo,
      'verifyCode': widget.verifyCode,
      'isDeviceTalkBack': widget.isDeviceTalkBack,
      'autoPlay': widget.autoPlay,
    };
    return AndroidView(
      viewType: 'ezviz_player_view',
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: _onPlatformViewCreated,
    );
  }
}
