# ezviz_plugins

萤石智能摄像能力插件（仅 Android）。封装萤石 EZOpenSDK 的 token 管理、设备列表、绑定/解绑、WiFi 配网、云台控制和实时画面播放等能力，不含任何界面，UI 由宿主自行实现。

---

## 依赖引入

在宿主 App 的 `pubspec.yaml` 中以本地路径引入：

```yaml
dependencies:
  ezviz_plugins:
    path: ../ezviz_plugins
```

---

## Android 原生配置

### 1. AndroidManifest 配置 AppKey

在宿主 App 的 `AndroidManifest.xml` 的 `<application>` 节点内加入萤石开放平台申请到的 AppKey：

```xml
<meta-data
    android:name="EZVIZ_APP_KEY"
    android:value="你的AppKey" />
```

插件 `init()` 会自动从这里读取并调用 `EZOpenSDK.initLib`，取代原先在宿主 `Application.onCreate` 里的初始化代码。

### 2. 混淆规则

release 包需在宿主 App 的 `proguard-rules.pro` 加入：

```
-keep class com.videogo.** { *; }
-dontwarn com.videogo.**
-keep class com.ezviz.** { *; }
-dontwarn com.ezviz.**
-keep class com.squareup.okhttp3.** { *; }
-dontwarn com.squareup.okhttp3.**
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
```

---

## 使用

### 基本流程

```dart
import 'package:ezviz_plugins/ezviz_plugins.dart';

final ezviz = EzvizControl();

// 1. 初始化 SDK（幂等，取代宿主 Application.onCreate 里的 EZOpenSDK.initLib）
await ezviz.init();

// 2. 设置 accessToken（token 由你的后端签发）
await ezviz.setAccessToken('your_access_token');

// 3. 获取设备列表
final devices = await ezviz.getDeviceList();
print(devices.first.deviceName); // 客厅摄像头

// 4. 绑定新设备（verifyCode 为机身贴纸上的 6 位大写字母）
await ezviz.addDevice(
  deviceSerial: 'ABC123456',
  verifyCode: 'ABCDEF',
);

// 5. 云台控制（上下左右，0=开始 1=停止）
await ezviz.controlPtz(
  deviceSerial: 'ABC123456',
  direction: 2, // 左转
  action: 0,    // 开始
);
await ezviz.controlPtz(
  deviceSerial: 'ABC123456',
  direction: 2,
  action: 1,    // 停止
);

// 6. 解绑设备
await ezviz.deleteDevice('ABC123456');

// 7. 退出登录
await ezviz.logout();
```

### WiFi 配网

```dart
// AP 模式（推荐，兼容性好）：useAP=true
// EZ 模式（声波/SmartConfig）：useAP=false
final result = await ezviz.startConfigWifi(
  ssid: 'MyWifi',
  password: 'wifi_password',
  deviceSerial: 'ABC123456',
  verifyCode: 'ABCDEF',
  useAP: true,
);
print('配网成功：${result['deviceSerial']}');

// 中途取消配网
await ezviz.stopConfigWifi();
```

### 实时画面播放

在页面里嵌入 `EzvizPlayer` Widget：

```dart
import 'package:ezviz_plugins/ezviz_plugins.dart';

final _playerKey = GlobalKey<EzvizPlayerState>();

// 在 Widget tree 中使用
EzvizPlayer(
  key: _playerKey,
  deviceSerial: 'ABC123456',
  cameraNo: 1,
  verifyCode: 'ABCDEF', // 加密设备必传
  autoPlay: true,
  onPlaySuccess: () => print('播放成功'),
  onPlayFail: (code) => print('播放失败：$code'),
)

// 手动控制
await _playerKey.currentState?.openSound();
await _playerKey.currentState?.closeSound();
await _playerKey.currentState?.stopRealPlay();
await _playerKey.currentState?.startRealPlay();
```

### 错误处理

```dart
try {
  await ezviz.setAccessToken(token);
} on EzvizException catch (e) {
  print('失败 [${e.code}]: ${e.message}');
}
```

### ⚠️ 仅测试用：App 端直接换取 token

```dart
// 生产环境务必改为「后端签发」，AppSecret 不能出现在客户端
final token = await ezviz.exchangeAccessTokenForTest(
  appKey: 'your_app_key',
  appSecret: 'your_app_secret',
);
```

---

## API 速查

| 方法 | 说明 |
|---|---|
| `init()` | 初始化 SDK，幂等，返回 `{ok, sdkPresent, error}` |
| `checkInit()` | 查询当前初始化状态 |
| `setAccessToken(token)` | 把 accessToken 写入 SDK |
| `exchangeAccessTokenForTest({appKey, appSecret})` | ⚠️ 仅测试，端上换 token |
| `getDeviceList({page, size})` | 返回 `List<EzvizDevice>` |
| `probeDeviceInfo(deviceSerial)` | 查询单个设备信息，未找到返回 null |
| `addDevice({deviceSerial, verifyCode})` | 绑定设备 |
| `deleteDevice(deviceSerial)` | 解绑设备 |
| `controlPtz({deviceSerial, direction, action})` | 云台控制 |
| `startConfigWifi({ssid, password, deviceSerial, verifyCode, useAP})` | WiFi 配网 |
| `stopConfigWifi()` | 停止/取消配网 |
| `logout()` | 退出登录，清空本地 token |

**云台 direction**：0 上 / 1 下 / 2 左 / 3 右  
**云台 action**：0 开始 / 1 停止

---

## 主要数据模型

**`EzvizDevice`** — 设备基本信息：
- `deviceSerial` — 序列号，控制/删除操作的唯一标识
- `deviceName` — 设备名称
- `online` — 是否在线
- `deviceType` — 设备型号
- `cameraNum` — 通道数（摄像头数量）
- `defence` — 布防状态
- `isSupportSoundWave` — 是否支持声波（EZ）配网

**`EzvizPlayer`** — 实时画面 Widget（`StatefulWidget`）：
- 通过 `AndroidView` 嵌入原生 `EZPlayer` + `SurfaceView`
- 通过 `GlobalKey<EzvizPlayerState>` 调用 `startRealPlay`/`stopRealPlay`/`openSound`/`closeSound`
- `autoPlay: true` 时 Surface 就绪后自动开始播放

**`EzvizException`** — 统一异常：`code`（原生错误码）、`message`

