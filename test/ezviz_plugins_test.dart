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

  test('设备控制方法透传通道参数并解析升级和存储状态', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'getUpgradeStatus' => {'status': 0, 'progress': 42},
            'getStorageStatus' => [
              {'index': 2, 'name': 'SD 卡', 'status': 3, 'formatRate': 65},
            ],
            _ => true,
          };
        });

    await ezviz.controlPtz(
      deviceSerial: 'ABC123',
      channelNo: 3,
      direction: 4,
      action: 0,
      speed: 2,
    );
    await ezviz.setVideoLevel(
      deviceSerial: 'ABC123',
      channelNo: 3,
      videoLevel: 2,
    );
    await ezviz.setDefence(deviceSerial: 'ABC123', status: 1);
    await ezviz.flipVideo(deviceSerial: 'ABC123', channelNo: 3);
    final upgrade = await ezviz.getUpgradeStatus('ABC123');
    await ezviz.upgradeDevice('ABC123');
    final storage = await ezviz.getStorageStatus('ABC123');
    await ezviz.formatStorage(deviceSerial: 'ABC123', index: 2);

    expect(calls.map((call) => call.method), [
      'controlPtz',
      'setVideoLevel',
      'setDefence',
      'flipVideo',
      'getUpgradeStatus',
      'upgradeDevice',
      'getStorageStatus',
      'formatStorage',
    ]);
    expect(calls.first.arguments, {
      'deviceSerial': 'ABC123',
      'channelNo': 3,
      'direction': 4,
      'action': 0,
      'speed': 2,
    });
    expect(upgrade.status, 0);
    expect(upgrade.progress, 42);
    expect(storage.single.index, 2);
    expect(storage.single.name, 'SD 卡');
    expect(storage.single.status, 3);
    expect(storage.single.formatRate, 65);
  });

  test('查询并下载 SD 卡录像使用稳定的 recordId', () async {
    final start = DateTime(2026, 8, 11);
    final end = DateTime(2026, 8, 12);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (call.method == 'searchDeviceRecords') {
            return [
              {
                'recordId': 'ABC123:3:1000:61000',
                'startTime': 1000,
                'endTime': 61000,
              },
            ];
          }
          if (call.method == 'downloadDeviceRecord') {
            return '/movies/device.mp4';
          }
          return null;
        });

    final records = await ezviz.searchDeviceRecords(
      deviceSerial: 'ABC123',
      channelNo: 3,
      startTime: start,
      endTime: end,
    );
    final path = await ezviz.downloadDeviceRecord(
      record: records.single,
      verifyCode: 'ABCDEF',
    );

    expect(calls.first.method, 'searchDeviceRecords');
    expect(calls.first.arguments, {
      'deviceSerial': 'ABC123',
      'channelNo': 3,
      'startTime': start.millisecondsSinceEpoch,
      'endTime': end.millisecondsSinceEpoch,
    });
    expect(records.single.duration, const Duration(minutes: 1));
    expect(calls.last.arguments, {
      'recordId': 'ABC123:3:1000:61000',
      'verifyCode': 'ABCDEF',
    });
    expect(path, '/movies/device.mp4');
  });

  test('告警列表和消息操作通过插件黑盒接口完成', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return switch (call.method) {
            'getAlarmList' => [
              {
                'alarmId': 'alarm-1',
                'alarmName': '人体检测',
                'deviceSerial': 'ABC123',
                'deviceName': '客厅',
                'cameraNo': 2,
                'alarmType': 10000,
                'alarmPicUrl': 'https://example.com/alarm.jpg',
                'alarmStartTime': '2026-08-12 10:30:00',
                'isRead': false,
                'isEncrypted': true,
                'crypt': 1,
                'preTime': 5,
                'delayTime': 15,
                'recordState': 4,
                'customerType': 'person',
                'customerInfo': '{"message":"检测到有人进入"}',
              },
            ],
            'getUnreadAlarmCount' => 3,
            _ => true,
          };
        });

    final alarms = await ezviz.getAlarmList(
      deviceSerial: 'ABC123',
      page: 0,
      size: 20,
    );
    final unread = await ezviz.getUnreadAlarmCount(deviceSerial: 'ABC123');
    await ezviz.markAlarmsRead([alarms.single.alarmId]);
    await ezviz.deleteAlarms([alarms.single.alarmId]);

    expect(alarms.single.alarmName, '人体检测');
    expect(alarms.single.cameraNo, 2);
    expect(alarms.single.encrypted, true);
    expect(alarms.single.hasRecord, true);
    expect(alarms.single.customerType, 'person');
    expect(alarms.single.customerInfoJson?['message'], '检测到有人进入');
    expect(alarms.single.detailMessage, '检测到有人进入');
    expect(unread, 3);
    expect(calls.map((call) => call.method), [
      'getAlarmList',
      'getUnreadAlarmCount',
      'markAlarmsRead',
      'deleteAlarms',
    ]);
    expect(calls[2].arguments, {
      'alarmIds': ['alarm-1'],
    });
  });

  test('加密告警图片通过插件解密加载', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'loadAlarmImage');
          expect(call.arguments, {
            'alarmPicUrl': 'https://example.com/alarm.jpg',
            'encrypted': true,
            'crypt': 1,
            'checksum': null,
            'verifyCode': 'ABCDEF',
          });
          return Uint8List.fromList([1, 2, 3]);
        });
    final alarm = EzvizAlarm.fromMap({
      'alarmPicUrl': 'https://example.com/alarm.jpg',
      'isEncrypted': true,
      'crypt': 1,
    });

    final bytes = await ezviz.loadAlarmImage(alarm, verifyCode: 'ABCDEF');

    expect(bytes, Uint8List.fromList([1, 2, 3]));
  });

  test('设备序列号形式的 customerInfo 不作为告警详情展示', () {
    final alarm = EzvizAlarm.fromMap({
      'alarmId': 'alarm-1',
      'alarmName': '人体检测',
      'deviceSerial': 'ABC123456',
      'customerInfo': 'ABC123456',
    });

    expect(alarm.readableDetailMessage, isNull);
    expect(alarm.detailMessage, '人体检测');
  });

  test('原生抛错时归一化为 EzvizException', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(code: 'ezviz_error', message: 'token 无效');
        });

    expect(() => ezviz.setAccessToken('bad'), throwsA(isA<EzvizException>()));
  });
}
