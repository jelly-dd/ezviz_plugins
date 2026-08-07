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

  /// 查询设备能力集（用于获取热点前缀）。
  /// 返回 Map，包含：hotspotPrefix、supportAP、supportWifi、support5G
  /// ⚠️ 需要设备已联网或处于配网状态；未联网设备可能返回 null。
  Future<Map<String, dynamic>?> probeDeviceInfo(
    String deviceSerial, {
    String deviceType = '',
  }) async {
    final result = await _invoke('probeDeviceInfo', {
      'deviceSerial': deviceSerial,
      'deviceType': deviceType,
    });
    if (result == null) return null;
    return _asMap(result);
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

  /// 发起 WiFi 配网，返回配网成功的设备信息 map。
  ///
  /// **AP 模式（useAP=true）使用前提：**
  /// - 必须先调用 [getCurrentWifiSsid] 校验手机已连接设备热点
  /// - 热点名称应为 `hotspotPrefix_deviceSerial`（如 `EZVIZ_Bk8898885`）
  /// - 未连接设备热点时调用会导致 90 秒超时
  ///
  /// [hotspotSsid]/[hotspotPwd]：设备AP热点名和密码，从 probeDeviceInfo 的 hotspotPrefix 构造
  ///   hotspotSsid = hotspotPrefix + '_' + deviceSerial
  ///   hotspotPwd  = hotspotPrefix + '_' + verifyCode （或空字符串）
  /// 不传时 SDK 自动用 EZVIZ_序列号 作为热点名。
  Future<Map<String, dynamic>> startConfigWifi({
    required String ssid,
    required String password,
    required String deviceSerial,
    required String verifyCode,
    bool useAP = true,
    int timeout = 60,
    String? hotspotSsid,
    String? hotspotPwd,
  }) async {
    final result = await _invoke('startConfigWifi', {
      'ssid': ssid,
      'password': password,
      'deviceSerial': deviceSerial,
      'verifyCode': verifyCode,
      'useAP': useAP,
      'timeout': timeout,
      'hotspotSsid': ?hotspotSsid,
      'hotspotPwd': ?hotspotPwd,
    });
    return _asMap(result);
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
