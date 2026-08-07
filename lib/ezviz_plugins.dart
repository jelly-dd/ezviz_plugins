/// 萤石智能摄像能力插件 —— 功能 API 入口。
///
/// 用法示例：
/// ```dart
/// final ezviz = EzvizControl();
/// await ezviz.init();
/// final token = await ezviz.exchangeAccessTokenForTest(appKey: ..., appSecret: ...);
/// final devices = await ezviz.getDeviceList();
/// ```
library;

export 'src/ezviz_control.dart';
export 'src/ezviz_models.dart';
export 'src/ezviz_player.dart';
