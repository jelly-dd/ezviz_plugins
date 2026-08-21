import 'dart:convert';

/// 探测设备后 App 需要处理的五种状态。
enum EzvizDeviceStatus {
  retry,
  add,
  connectNetwork,
  alreadyAdded,
  addedByOtherAccount,
}

/// 插件根据设备能力自动选定的配网方式。
enum EzvizProvisioningMethod { ap, smartAndSoundWave, smart, soundWave }

class EzvizTouchApToken {
  final String token;
  final String registerUrl;
  final String userId;

  const EzvizTouchApToken({
    required this.token,
    required this.registerUrl,
    this.userId = '',
  });

  factory EzvizTouchApToken.fromMap(Map<String, dynamic> map) {
    return EzvizTouchApToken(
      token: map['token'] as String? ?? '',
      registerUrl: map['registerUrl'] as String? ?? '',
      userId: map['userId'] as String? ?? '',
    );
  }
}

class EzvizTouchApDeviceInfo {
  final String deviceSerial;
  final String deviceType;
  final String firmwareVersion;
  final String apVersion;
  final String deviceMac;

  const EzvizTouchApDeviceInfo({
    required this.deviceSerial,
    this.deviceType = '',
    this.firmwareVersion = '',
    this.apVersion = '',
    this.deviceMac = '',
  });

  factory EzvizTouchApDeviceInfo.fromMap(Map<String, dynamic> map) {
    return EzvizTouchApDeviceInfo(
      deviceSerial: map['deviceSerial'] as String? ?? '',
      deviceType: map['deviceType'] as String? ?? '',
      firmwareVersion: map['firmwareVersion'] as String? ?? '',
      apVersion: map['apVersion'] as String? ?? '',
      deviceMac: map['deviceMac'] as String? ?? '',
    );
  }
}

/// 萤石设备二维码中携带的设备身份信息。
class EzvizDeviceQrInfo {
  final String source;
  final String deviceSerial;
  final String verifyCode;
  final String deviceType;

  const EzvizDeviceQrInfo({
    required this.source,
    required this.deviceSerial,
    required this.verifyCode,
    this.deviceType = '',
  });
}

/// 配网前由 App 使用的必要信息。
class EzvizProvisioningInfo {
  final EzvizProvisioningMethod method;
  final bool supports5G;
  final bool requiresManualHotspotConnection;
  final String? hotspotSsid;
  final String? hotspotPassword;

  const EzvizProvisioningInfo({
    required this.method,
    this.supports5G = false,
    this.requiresManualHotspotConnection = false,
    this.hotspotSsid,
    this.hotspotPassword,
  });

  factory EzvizProvisioningInfo.fromMap(Map<String, dynamic> map) {
    return EzvizProvisioningInfo(
      method: EzvizProvisioningMethod.values.byName(map['method'] as String),
      supports5G: map['supports5G'] as bool? ?? false,
      requiresManualHotspotConnection:
          map['requiresManualHotspotConnection'] as bool? ?? false,
      hotspotSsid: map['hotspotSsid'] as String?,
      hotspotPassword: map['hotspotPassword'] as String?,
    );
  }
}

/// [EzvizControl.probeDeviceInfo] 的黑盒探测结果。
class EzvizProbeResult {
  final EzvizDeviceStatus status;
  final int? sdkErrorCode;
  final String? message;
  final EzvizProvisioningInfo? provisioning;

  const EzvizProbeResult({
    required this.status,
    this.sdkErrorCode,
    this.message,
    this.provisioning,
  });

  factory EzvizProbeResult.fromMap(Map<String, dynamic> map) {
    final provisioning = map['provisioning'];
    return EzvizProbeResult(
      status: EzvizDeviceStatus.values.byName(map['status'] as String),
      sdkErrorCode: (map['sdkErrorCode'] as num?)?.toInt(),
      message: map['message'] as String?,
      provisioning: provisioning is Map
          ? EzvizProvisioningInfo.fromMap(
              provisioning.map((key, value) => MapEntry(key.toString(), value)),
            )
          : null,
    );
  }
}

/// 配网并绑定成功后的设备信息。
class EzvizConfigResult {
  final String deviceSerial;
  final String deviceName;
  final EzvizProvisioningMethod method;

  const EzvizConfigResult({
    required this.deviceSerial,
    required this.deviceName,
    required this.method,
  });

  factory EzvizConfigResult.fromMap(Map<String, dynamic> map) {
    return EzvizConfigResult(
      deviceSerial: map['deviceSerial'] as String? ?? '',
      deviceName: map['deviceName'] as String? ?? '',
      method: EzvizProvisioningMethod.values.byName(
        map['provisioningMethod'] as String,
      ),
    );
  }
}

enum EzvizTalkCapability { none, halfDuplex, fullDuplex }

class EzvizUpgradeStatus {
  final int status;
  final int progress;

  const EzvizUpgradeStatus({required this.status, required this.progress});

  factory EzvizUpgradeStatus.fromMap(Map<String, dynamic> map) {
    return EzvizUpgradeStatus(
      status: (map['status'] as num?)?.toInt() ?? -1,
      progress: (map['progress'] as num?)?.toInt() ?? 0,
    );
  }
}

class EzvizStorageStatus {
  final int index;
  final String name;
  final int status;
  final int formatRate;

  const EzvizStorageStatus({
    required this.index,
    required this.name,
    required this.status,
    required this.formatRate,
  });

  factory EzvizStorageStatus.fromMap(Map<String, dynamic> map) {
    return EzvizStorageStatus(
      index: (map['index'] as num?)?.toInt() ?? 0,
      name: map['name'] as String? ?? '存储介质',
      status: (map['status'] as num?)?.toInt() ?? -1,
      formatRate: (map['formatRate'] as num?)?.toInt() ?? 0,
    );
  }
}

class EzvizDeviceRecord {
  final String recordId;
  final DateTime startTime;
  final DateTime endTime;

  const EzvizDeviceRecord({
    required this.recordId,
    required this.startTime,
    required this.endTime,
  });

  Duration get duration => endTime.difference(startTime);

  factory EzvizDeviceRecord.fromMap(Map<String, dynamic> map) {
    return EzvizDeviceRecord(
      recordId: map['recordId'] as String? ?? '',
      startTime: DateTime.fromMillisecondsSinceEpoch(
        (map['startTime'] as num?)?.toInt() ?? 0,
      ),
      endTime: DateTime.fromMillisecondsSinceEpoch(
        (map['endTime'] as num?)?.toInt() ?? 0,
      ),
    );
  }
}

class EzvizAlarm {
  final String alarmId;
  final String alarmName;
  final String deviceSerial;
  final String deviceName;
  final int cameraNo;
  final int alarmType;
  final String? alarmPicUrl;
  final String alarmStartTime;
  final bool read;
  final bool encrypted;
  final int crypt;
  final String? checksum;
  final int preTime;
  final int delayTime;
  final int recordState;
  final String? category;

  /// SDK 厂商扩展事件类型，具体取值由设备/事件类型定义。
  final String? customerType;

  /// SDK 厂商扩展事件详情，可能是 JSON，也可能是原始文本。
  final String? customerInfo;

  const EzvizAlarm({
    required this.alarmId,
    required this.alarmName,
    required this.deviceSerial,
    required this.deviceName,
    required this.cameraNo,
    required this.alarmType,
    this.alarmPicUrl,
    required this.alarmStartTime,
    this.read = false,
    this.encrypted = false,
    this.crypt = 0,
    this.checksum,
    this.preTime = 0,
    this.delayTime = 0,
    this.recordState = 0,
    this.category,
    this.customerType,
    this.customerInfo,
  });

  bool get hasRecord => recordState != 0;

  Map<String, dynamic>? get customerInfoJson {
    final value = customerInfo?.trim();
    if (value == null || value.isEmpty) return null;
    try {
      final decoded = jsonDecode(value);
      return decoded is Map ? Map<String, dynamic>.from(decoded) : null;
    } on FormatException {
      return null;
    }
  }

  /// 返回扩展字段中可直接展示给用户的事件详情。
  ///
  /// 部分设备会将 [customerInfo] 用作设备序列号等内部关联值，不能误显示为告警内容。
  String? get readableDetailMessage {
    final json = customerInfoJson;
    if (json != null) {
      for (final key in [
        'message',
        'msg',
        'description',
        'eventName',
        'alarmName',
      ]) {
        final value = json[key]?.toString().trim();
        if (value != null && !_isInternalCustomerValue(value)) return value;
      }
    }
    final raw = customerInfo?.trim();
    if (raw == null || _isInternalCustomerValue(raw)) return null;
    return raw;
  }

  /// 兼容旧调用：没有可读详情时回退到告警名称。
  String get detailMessage => readableDetailMessage ?? alarmName;

  bool _isInternalCustomerValue(String value) {
    final normalized = value.trim().toUpperCase();
    return normalized.isEmpty ||
        normalized == deviceSerial.trim().toUpperCase() ||
        normalized == alarmId.trim().toUpperCase() ||
        normalized == alarmName.trim().toUpperCase();
  }

  factory EzvizAlarm.fromMap(Map<String, dynamic> map) {
    return EzvizAlarm(
      alarmId: map['alarmId'] as String? ?? '',
      alarmName: map['alarmName'] as String? ?? '设备告警',
      deviceSerial: map['deviceSerial'] as String? ?? '',
      deviceName: map['deviceName'] as String? ?? '',
      cameraNo: (map['cameraNo'] as num?)?.toInt() ?? 1,
      alarmType: (map['alarmType'] as num?)?.toInt() ?? -1,
      alarmPicUrl: map['alarmPicUrl'] as String?,
      alarmStartTime: map['alarmStartTime'] as String? ?? '',
      read: map['isRead'] as bool? ?? false,
      encrypted: map['isEncrypted'] as bool? ?? false,
      crypt: (map['crypt'] as num?)?.toInt() ?? 0,
      checksum: map['checksum'] as String?,
      preTime: (map['preTime'] as num?)?.toInt() ?? 0,
      delayTime: (map['delayTime'] as num?)?.toInt() ?? 0,
      recordState: (map['recordState'] as num?)?.toInt() ?? 0,
      category: map['category'] as String?,
      customerType: map['customerType'] as String?,
      customerInfo: map['customerInfo'] as String?,
    );
  }
}

/// 设备或通道由 EZOpenSDK 返回的标准能力集。
class EzvizCapabilities {
  final EzvizTalkCapability talk;
  final bool ptz;
  final bool zoom;
  final bool defence;
  final bool defencePlan;
  final bool upgrade;
  final bool mirrorCenter;
  final bool audioOnOff;
  final bool soundWave;
  final bool ptzFocus;
  final bool playbackRate;
  final bool directInnerRelaySpeed;
  final bool sdRecordDownload;
  final bool sdCover;
  final bool multiChannel;
  final bool autoVideoLevel;
  final bool videoMeeting;

  const EzvizCapabilities({
    this.talk = EzvizTalkCapability.none,
    this.ptz = false,
    this.zoom = false,
    this.defence = false,
    this.defencePlan = false,
    this.upgrade = false,
    this.mirrorCenter = false,
    this.audioOnOff = false,
    this.soundWave = false,
    this.ptzFocus = false,
    this.playbackRate = false,
    this.directInnerRelaySpeed = false,
    this.sdRecordDownload = false,
    this.sdCover = false,
    this.multiChannel = false,
    this.autoVideoLevel = false,
    this.videoMeeting = false,
  });

  factory EzvizCapabilities.fromMap(Map<String, dynamic> map) {
    return EzvizCapabilities(
      talk: EzvizTalkCapability.values.byName(
        map['talk'] as String? ?? EzvizTalkCapability.none.name,
      ),
      ptz: map['ptz'] as bool? ?? false,
      zoom: map['zoom'] as bool? ?? false,
      defence: map['defence'] as bool? ?? false,
      defencePlan: map['defencePlan'] as bool? ?? false,
      upgrade: map['upgrade'] as bool? ?? false,
      mirrorCenter: map['mirrorCenter'] as bool? ?? false,
      audioOnOff: map['audioOnOff'] as bool? ?? false,
      soundWave: map['soundWave'] as bool? ?? false,
      ptzFocus: map['ptzFocus'] as bool? ?? false,
      playbackRate: map['playbackRate'] as bool? ?? false,
      directInnerRelaySpeed: map['directInnerRelaySpeed'] as bool? ?? false,
      sdRecordDownload: map['sdRecordDownload'] as bool? ?? false,
      sdCover: map['sdCover'] as bool? ?? false,
      multiChannel: map['multiChannel'] as bool? ?? false,
      autoVideoLevel: map['autoVideoLevel'] as bool? ?? false,
      videoMeeting: map['videoMeeting'] as bool? ?? false,
    );
  }
}

class EzvizVideoQuality {
  final String name;
  final int videoLevel;
  final int streamType;

  const EzvizVideoQuality({
    required this.name,
    required this.videoLevel,
    required this.streamType,
  });

  factory EzvizVideoQuality.fromMap(Map<String, dynamic> map) {
    return EzvizVideoQuality(
      name: map['name'] as String? ?? '',
      videoLevel: (map['videoLevel'] as num?)?.toInt() ?? 0,
      streamType: (map['streamType'] as num?)?.toInt() ?? 0,
    );
  }
}

/// 一个可独立播放和控制的设备通道。
class EzvizCamera {
  final String deviceSerial;
  final int cameraNo;
  final String cameraName;
  final String? cameraCover;
  final bool isSubDevice;
  final bool shared;
  final int permission;
  final int? videoLevel;
  final List<EzvizVideoQuality> videoQualities;
  final EzvizCapabilities capabilities;

  const EzvizCamera({
    required this.deviceSerial,
    required this.cameraNo,
    required this.cameraName,
    this.cameraCover,
    this.isSubDevice = false,
    this.shared = false,
    this.permission = 0,
    this.videoLevel,
    this.videoQualities = const [],
    this.capabilities = const EzvizCapabilities(),
  });

  factory EzvizCamera.fromMap(Map<String, dynamic> map) {
    final qualities = (map['videoQualities'] as List?) ?? const [];
    return EzvizCamera(
      deviceSerial: map['deviceSerial'] as String? ?? '',
      cameraNo: (map['cameraNo'] as num?)?.toInt() ?? 1,
      cameraName: map['cameraName'] as String? ?? '',
      cameraCover: map['cameraCover'] as String?,
      isSubDevice: map['isSubDevice'] as bool? ?? false,
      shared: map['isShared'] as bool? ?? false,
      permission: (map['permission'] as num?)?.toInt() ?? 0,
      videoLevel: (map['videoLevel'] as num?)?.toInt(),
      videoQualities: qualities
          .map((item) => EzvizVideoQuality.fromMap(_stringKeyMap(item)))
          .toList(growable: false),
      capabilities: EzvizCapabilities.fromMap(
        _stringKeyMap(map['capabilities']),
      ),
    );
  }
}

/// 萤石设备（简化模型，最小闭环只取展示所需字段）。
class EzvizDevice {
  /// 设备序列号，作为设备唯一标识，所有控制/删除都用它。
  final String deviceSerial;

  /// 设备名称。
  final String deviceName;

  /// 是否在线（原生侧由 status==1 换算）。
  final bool online;

  /// 实时视频是否需要设备验证码解密。
  final bool encrypted;

  /// 设备型号。
  final String? deviceType;

  /// SDK 返回的设备分类原值，用于区分摄像机、网关等产品族。
  final String? category;

  /// 通道数（摄像头数量）。
  final int cameraNum;

  /// SDK 返回的可播放通道列表，播放和控制必须使用其中的 [EzvizCamera.cameraNo]。
  final List<EzvizCamera> cameras;

  /// 设备级能力；实际通道控制优先使用 [EzvizCamera.capabilities]。
  final EzvizCapabilities capabilities;

  /// 布防状态。
  final int defence;

  /// 是否支持声波（EZ/SmartConfig）配网。AP 模式无独立标志位，默认可用。
  final bool isSupportSoundWave;

  const EzvizDevice({
    required this.deviceSerial,
    required this.deviceName,
    this.online = false,
    this.encrypted = false,
    this.deviceType,
    this.category,
    this.cameraNum = 1,
    this.cameras = const [],
    this.capabilities = const EzvizCapabilities(),
    this.defence = 0,
    this.isSupportSoundWave = false,
  });

  factory EzvizDevice.fromMap(Map<String, dynamic> map) {
    final cameras = ((map['cameras'] as List?) ?? const [])
        .map((item) => EzvizCamera.fromMap(_stringKeyMap(item)))
        .toList(growable: false);
    return EzvizDevice(
      deviceSerial: map['deviceSerial'] as String? ?? '',
      deviceName: map['deviceName'] as String? ?? '未命名设备',
      online: map['isOnline'] as bool? ?? false,
      encrypted: map['isEncrypted'] as bool? ?? false,
      deviceType: map['deviceType'] as String?,
      category: map['category'] as String?,
      cameraNum: (map['cameraNum'] as num?)?.toInt() ?? cameras.length,
      cameras: cameras,
      capabilities: EzvizCapabilities.fromMap(
        _stringKeyMap(map['capabilities']),
      ),
      defence: (map['defence'] as num?)?.toInt() ?? 0,
      isSupportSoundWave: map['isSupportSoundWave'] as bool? ?? false,
    );
  }

  @override
  String toString() =>
      'EzvizDevice(serial: $deviceSerial, name: $deviceName, online: $online)';
}

Map<String, dynamic> _stringKeyMap(Object? value) {
  if (value is! Map) return const {};
  return value.map((key, item) => MapEntry(key.toString(), item));
}
