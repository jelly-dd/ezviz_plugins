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
              },
            ];
          }
          return null;
        });

    final devices = await ezviz.getDeviceList();
    expect(devices, hasLength(1));
    expect(devices.first.deviceSerial, 'ABC123');
    expect(devices.first.online, true);
  });

  test('原生抛错时归一化为 EzvizException', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(code: 'ezviz_error', message: 'token 无效');
        });

    expect(() => ezviz.setAccessToken('bad'), throwsA(isA<EzvizException>()));
  });
}
