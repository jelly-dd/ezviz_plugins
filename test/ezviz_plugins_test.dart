import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ezviz_plugins/ezviz_plugins.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.example.matter/ezviz');
  final ezviz = EzvizControl();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('解析 service.ys7.com 回车分隔设备二维码', () {
    final info = ezviz.parseDeviceQrCode(
      'https://service.ys7.com/mobile/d/1618\r'
      'BK8898885\rVXQPOI\rCS-CP1-V105-1J4WF\r',
    );

    expect(info.source, 'https://service.ys7.com/mobile/d/1618');
    expect(info.deviceSerial, 'BK8898885');
    expect(info.verifyCode, 'VXQPOI');
    expect(info.deviceType, 'CS-CP1-V105-1J4WF');
  });

  test('解析 www.ezviz.com 回车分隔设备二维码', () {
    final info = ezviz.parseDeviceQrCode(
      'www.ezviz.com\rBK8898885\rVXQPOI\rCS-CP1-V105-1J4WF\r',
    );

    expect(info.source, 'www.ezviz.com');
    expect(info.deviceSerial, 'BK8898885');
    expect(info.verifyCode, 'VXQPOI');
    expect(info.deviceType, 'CS-CP1-V105-1J4WF');
  });

  test('拒绝只有说明书网址的二维码', () {
    expect(
      () => ezviz.parseDeviceQrCode('https://service.ys7.com/mobile/d/1618'),
      throwsA(
        isA<EzvizException>().having(
          (error) => error.code,
          'code',
          'invalid_device_qr',
        ),
      ),
    );
  });

  test('init 透传原生返回的状态 map', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'init') {
            return {'ok': true, 'sdkPresent': true, 'error': null};
          }
          return null;
        });

    final status = await ezviz.init();
    expect(status['ok'], true);
    expect(status['sdkPresent'], true);
  });

  test('getDeviceList 解析设备列表', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getDeviceList') {
            return [
              {
                'deviceSerial': 'ABC123',
                'deviceName': '客厅摄像头',
                'isOnline': true,
                'isEncrypted': true,
                'cameraNum': 1,
                'capabilities': {
                  'talk': 'fullDuplex',
                  'ptz': true,
                  'zoom': true,
                },
                'cameras': [
                  {
                    'deviceSerial': 'ABC123',
                    'cameraNo': 3,
                    'cameraName': '客厅通道',
                    'videoLevel': 2,
                    'videoQualities': [
                      {'name': '高清', 'videoLevel': 2, 'streamType': 1},
                    ],
                    'capabilities': {'talk': 'halfDuplex', 'ptz': true},
                  },
                ],
              },
            ];
          }
          return null;
        });

    final devices = await ezviz.getDeviceList();
    expect(devices, hasLength(1));
    expect(devices.first.deviceSerial, 'ABC123');
    expect(devices.first.online, true);
    expect(devices.first.encrypted, true);
    expect(devices.first.capabilities.talk, EzvizTalkCapability.fullDuplex);
    expect(devices.first.cameras.single.cameraNo, 3);
    expect(devices.first.cameras.single.capabilities.ptz, true);
    expect(
      devices.first.cameras.single.capabilities.talk,
      EzvizTalkCapability.halfDuplex,
    );
    expect(devices.first.cameras.single.videoQualities.single.name, '高清');
  });

  test('probeDeviceInfo 返回五状态模型和自动选择的配网信息', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'probeDeviceInfo');
          expect(call.arguments['deviceSerial'], 'ABC123');
          expect(call.arguments['verifyCode'], 'ABCDEF');
          return {
            'status': 'connectNetwork',
            'sdkErrorCode': 120023,
            'message': '设备不在线',
            'provisioning': {
              'method': 'ap',
              'supports5G': false,
              'requiresManualHotspotConnection': false,
              'hotspotSsid': 'EZVIZ_ABC123',
              'hotspotPassword': 'EZVIZ_ABCDEF',
            },
          };
        });

    final probe = await ezviz.probeDeviceInfo('ABC123', verifyCode: 'ABCDEF');

    expect(probe.status, EzvizDeviceStatus.connectNetwork);
    expect(probe.sdkErrorCode, 120023);
    expect(probe.provisioning?.method, EzvizProvisioningMethod.ap);
    expect(probe.provisioning?.hotspotSsid, 'EZVIZ_ABC123');
    expect(probe.provisioning?.requiresManualHotspotConnection, false);
  });

  test('startConfigWifi 不再由 App 传配网方式', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'startConfigWifi');
          expect(call.arguments, isNot(contains('useAP')));
          expect(call.arguments, isNot(contains('hotspotSsid')));
          return {'deviceSerial': 'ABC123', 'provisioningMethod': 'smart'};
        });

    final result = await ezviz.startConfigWifi(
      ssid: 'Home',
      password: 'password',
      deviceSerial: 'ABC123',
      verifyCode: 'ABCDEF',
    );

    expect(result.deviceSerial, 'ABC123');
    expect(result.method, EzvizProvisioningMethod.smart);
  });

  test('原生抛错时归一化为 EzvizException', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(code: 'ezviz_error', message: 'token 无效');
        });

    expect(() => ezviz.setAccessToken('bad'), throwsA(isA<EzvizException>()));
  });
}
