/// 萤石设备（简化模型，最小闭环只取展示所需字段）。
class EzvizDevice {
  /// 设备序列号，作为设备唯一标识，所有控制/删除都用它。
  final String deviceSerial;

  /// 设备名称。
  final String deviceName;

  /// 是否在线（原生侧由 status==1 换算）。
  final bool online;

  /// 设备型号。
  final String? deviceType;

  /// 通道数（摄像头数量）。
  final int cameraNum;

  /// 布防状态。
  final int defence;

  /// 是否支持声波（EZ/SmartConfig）配网。AP 模式无独立标志位，默认可用。
  final bool isSupportSoundWave;

  const EzvizDevice({
    required this.deviceSerial,
    required this.deviceName,
    this.online = false,
    this.deviceType,
    this.cameraNum = 1,
    this.defence = 0,
    this.isSupportSoundWave = false,
  });

  factory EzvizDevice.fromMap(Map<String, dynamic> map) {
    return EzvizDevice(
      deviceSerial: map['deviceSerial'] as String? ?? '',
      deviceName: map['deviceName'] as String? ?? '未命名设备',
      online: map['isOnline'] as bool? ?? false,
      deviceType: map['deviceType'] as String?,
      cameraNum: (map['cameraNum'] as num?)?.toInt() ?? 1,
      defence: (map['defence'] as num?)?.toInt() ?? 0,
      isSupportSoundWave: map['isSupportSoundWave'] as bool? ?? false,
    );
  }

  @override
  String toString() =>
      'EzvizDevice(serial: $deviceSerial, name: $deviceName, online: $online)';
}
