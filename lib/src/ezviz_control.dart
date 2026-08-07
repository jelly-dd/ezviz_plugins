import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

import 'ezviz_models.dart';

/// 萤石异常（把原生 PlatformException 归一化）。
class EzvizException implements Exception {
  final String code;
  final String message;
  const EzvizException(this.code, this.message);

  @override
  String toString() => 'EzvizException($code): $message';
}

/// 萤石能力门面（App 内原生桥接，不依赖第三方插件）。
///
/// 最小闭环：init → 换取 accessToken → setAccessToken → 设备列表。
class EzvizControl {
  static const MethodChannel _channel = MethodChannel(
    'com.example.matter/ezviz',
  );

  /// 解析萤石设备二维码，返回序列号、验证码和设备型号。
  ///
  /// 官方二维码字段使用回车或换行分隔：
  /// `网址\r设备序列号\r验证码\r设备型号`。
  EzvizDeviceQrInfo parseDeviceQrCode(String rawValue) {
    final fields = rawValue
        .split(RegExp(r'\r\n|\n\r|\r|\n'))
        .map((value) => value.trim())
        .where((value) => value.isNotEmpty)
        .toList(growable: false);
    if (fields.length < 3) {
      throw const EzvizException('invalid_device_qr', '二维码不包含设备序列号和验证码');
    }

    final deviceSerial = fields[1].toUpperCase();
    final verifyCode = fields[2].toUpperCase();
    if (!RegExp(r'^[A-Z0-9]{9}$').hasMatch(deviceSerial)) {
      throw const EzvizException('invalid_device_qr', '二维码中的设备序列号格式错误');
    }
    if (!RegExp(r'^[A-Z0-9]{6}$').hasMatch(verifyCode)) {
      throw const EzvizException('invalid_device_qr', '二维码中的验证码格式错误');
    }

    return EzvizDeviceQrInfo(
      source: fields[0],
      deviceSerial: deviceSerial,
      verifyCode: verifyCode,
      deviceType: fields.length > 3 ? fields[3] : '',
    );
  }

  /// 初始化萤石 SDK（幂等）。取代宿主 Application.onCreate 里的 EZOpenSDK.initLib。
  /// AppKey 由宿主 AndroidManifest 里的 `EZVIZ_APP_KEY` meta-data 提供。
  Future<Map<String, dynamic>> init() async {
    final result = await _invoke('init', const {});
    return _asMap(result);
  }

  /// 检查 SDK 初始化状态。
  Future<Map<String, dynamic>> checkInit() async {
    final result = await _invoke('checkInit', const {});
    return _asMap(result);
  }

  /// ⚠️ 仅测试用：App 端直接用 AppKey/AppSecret 向萤石服务端换 accessToken。
  /// 生产环境务必改为「后端签发」，AppSecret 不能出现在客户端。
  Future<String> exchangeAccessTokenForTest({
    required String appKey,
    required String appSecret,
  }) async {
    final uri = Uri.parse('https://open.ys7.com/api/lapp/token/get');
    final client = HttpClient();
    try {
      final request = await client.postUrl(uri);
      request.headers.contentType = ContentType(
        'application',
        'x-www-form-urlencoded',
        charset: 'utf-8',
      );
      request.write('appKey=$appKey&appSecret=$appSecret');
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      final json = jsonDecode(body) as Map<String, dynamic>;
      if (json['code'] != '200') {
        throw EzvizException(
          json['code']?.toString() ?? 'token_error',
          json['msg']?.toString() ?? '换取 accessToken 失败',
        );
      }
      final token = (json['data'] as Map?)?['accessToken'] as String?;
      if (token == null || token.isEmpty) {
        throw const EzvizException('token_error', '返回的 accessToken 为空');
      }
      await setAccessToken(token);
      return token;
    } on EzvizException {
      rethrow;
    } catch (e) {
      throw EzvizException('token_error', '换取 accessToken 异常：$e');
    } finally {
      client.close(force: true);
    }
  }

  /// 把 accessToken 写入 SDK。
  Future<void> setAccessToken(String accessToken) async {
    await _invoke('setAccessToken', {'accessToken': accessToken});
  }

  /// 分页获取当前账号下的设备列表。
  Future<List<EzvizDevice>> getDeviceList({int page = 0, int size = 20}) async {
    final result = await _invoke('getDeviceList', {'page': page, 'size': size});
    final list = (result as List?) ?? const [];
    return list
        .map((e) => EzvizDevice.fromMap(_asMap(e)))
        .toList(growable: false);
  }

  /// 探测设备并返回 App 可直接消费的五种状态。
  ///
  /// 需要配网时，[EzvizProbeResult.provisioning] 包含插件自动选定的方式。
  Future<EzvizProbeResult> probeDeviceInfo(
    String deviceSerial, {
    String verifyCode = '',
    String deviceType = '',
  }) async {
    final result = await _invoke('probeDeviceInfo', {
      'deviceSerial': deviceSerial,
      'verifyCode': verifyCode,
      'deviceType': deviceType,
    });
    return EzvizProbeResult.fromMap(_asMap(result));
  }

  /// 读取手机当前连接的 WiFi SSID。
  /// 用于 AP 配网前校验手机是否已连接设备热点。
  /// 返回当前 WiFi 名称，若未连接或无法读取则返回空字符串。
  /// ⚠️ 需要定位权限（ACCESS_FINE_LOCATION）。
  Future<String> getCurrentWifiSsid() async {
    final result = await _invoke('getCurrentWifiSsid', const {});
    return result?.toString() ?? '';
  }

  /// 绑定设备。[verifyCode] 是机身上的验证码。
  Future<void> addDevice({
    required String deviceSerial,
    required String verifyCode,
  }) async {
    await _invoke('addDevice', {
      'deviceSerial': deviceSerial,
      'verifyCode': verifyCode,
    });
  }

  /// 解绑设备。
  Future<void> deleteDevice(String deviceSerial) async {
    await _invoke('deleteDevice', {'deviceSerial': deviceSerial});
  }

  /// 云台控制。[direction]: 0上 1下 2左 3右；[action]: 0开始 1停止。
  Future<void> controlPtz({
    required String deviceSerial,
    int channelNo = 1,
    required int direction,
    required int action,
    int speed = 1,
  }) async {
    await _invoke('controlPtz', {
      'deviceSerial': deviceSerial,
      'channelNo': channelNo,
      'direction': direction,
      'action': action,
      'speed': speed,
    });
  }

  /// 退出登录（清空本地 token）。
  Future<void> logout() async {
    await _invoke('logout', const {});
  }

  /// 发起 WiFi 配网并绑定设备。
  ///
  /// 插件会重新探测能力并自动选择 AP、Smart+声波、Smart 或声波配网。
  Future<EzvizConfigResult> startConfigWifi({
    required String ssid,
    required String password,
    required String deviceSerial,
    required String verifyCode,
    String deviceType = '',
  }) async {
    final result = await _invoke('startConfigWifi', {
      'ssid': ssid,
      'password': password,
      'deviceSerial': deviceSerial,
      'verifyCode': verifyCode,
      'deviceType': deviceType,
    });
    return EzvizConfigResult.fromMap(_asMap(result));
  }

  /// 停止当前配网流程。
  Future<void> stopConfigWifi() async {
    await _invoke('stopConfigWifi', const {});
  }

  Future<Object?> _invoke(String method, Map<String, dynamic> args) async {
    try {
      return await _channel.invokeMethod(method, args);
    } on PlatformException catch (e) {
      throw EzvizException(e.code, e.message ?? '原生调用失败');
    } on MissingPluginException {
      throw const EzvizException('channel_unavailable', '原生通道未就绪');
    }
  }

  Map<String, dynamic> _asMap(Object? value) {
    if (value is Map) {
      return value.map((k, v) => MapEntry(k.toString(), v));
    }
    throw const EzvizException('bad_response', '原生返回数据格式不正确');
  }
}
