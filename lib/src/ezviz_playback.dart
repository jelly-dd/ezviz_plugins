import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class EzvizPlayback extends StatefulWidget {
  const EzvizPlayback({
    super.key,
    required this.deviceSerial,
    required this.cameraNo,
    required this.startTime,
    required this.endTime,
    this.verifyCode,
    this.autoPlay = true,
    this.onPrepared,
    this.onPlayStart,
    this.onPlayFinish,
    this.onPlayFail,
    this.onProgress,
    this.onRateLower,
  });

  final String deviceSerial;
  final int cameraNo;
  final DateTime startTime;
  final DateTime endTime;
  final String? verifyCode;
  final bool autoPlay;
  final void Function(int streamFetchType)? onPrepared;
  final VoidCallback? onPlayStart;
  final VoidCallback? onPlayFinish;
  final void Function(String code)? onPlayFail;
  final void Function(DateTime position)? onProgress;
  final VoidCallback? onRateLower;

  @override
  State<EzvizPlayback> createState() => EzvizPlaybackState();
}

class EzvizPlaybackState extends State<EzvizPlayback> {
  MethodChannel? _channel;

  Future<bool> startPlayback() async {
    return await _channel?.invokeMethod<bool>('startPlayback') ?? false;
  }

  Future<bool> stopPlayback() async {
    return await _channel?.invokeMethod<bool>('stopPlayback') ?? false;
  }

  Future<bool> pausePlayback() async {
    return await _channel?.invokeMethod<bool>('pausePlayback') ?? false;
  }

  Future<bool> resumePlayback() async {
    return await _channel?.invokeMethod<bool>('resumePlayback') ?? false;
  }

  Future<bool> seekPlayback(DateTime time) async {
    return await _channel?.invokeMethod<bool>('seekPlayback', {
          'time': time.millisecondsSinceEpoch,
        }) ??
        false;
  }

  Future<bool> setPlaybackRate(double rate) async {
    return await _channel?.invokeMethod<bool>('setPlaybackRate', {
          'rate': rate,
        }) ??
        false;
  }

  Future<bool> openSound() async {
    return await _channel?.invokeMethod<bool>('openSound') ?? false;
  }

  Future<bool> closeSound() async {
    return await _channel?.invokeMethod<bool>('closeSound') ?? false;
  }

  void _onPlatformViewCreated(int id) {
    final channel = MethodChannel('com.example.matter/ezviz_playback_$id');
    channel.setMethodCallHandler(_handleCall);
    _channel = channel;
  }

  Future<dynamic> _handleCall(MethodCall call) async {
    final arguments = (call.arguments as Map?) ?? const {};
    switch (call.method) {
      case 'onPrepared':
        widget.onPrepared?.call(
          (arguments['streamFetchType'] as num?)?.toInt() ?? -1,
        );
        break;
      case 'onPlayStart':
        widget.onPlayStart?.call();
        break;
      case 'onPlayFinish':
        widget.onPlayFinish?.call();
        break;
      case 'onPlayFail':
        widget.onPlayFail?.call(arguments['code']?.toString() ?? 'unknown');
        break;
      case 'onProgress':
        final position = (arguments['position'] as num?)?.toInt();
        if (position != null) {
          widget.onProgress?.call(
            DateTime.fromMillisecondsSinceEpoch(position),
          );
        }
        break;
      case 'onRateLower':
        widget.onRateLower?.call();
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
    return AndroidView(
      viewType: 'ezviz_playback_view',
      creationParams: {
        'deviceSerial': widget.deviceSerial,
        'cameraNo': widget.cameraNo,
        'startTime': widget.startTime.millisecondsSinceEpoch,
        'endTime': widget.endTime.millisecondsSinceEpoch,
        'verifyCode': widget.verifyCode,
        'autoPlay': widget.autoPlay,
      },
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: _onPlatformViewCreated,
    );
  }
}
