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
final probe = await ezviz.probeDeviceInfo(
  'ABC123456',
  verifyCode: 'ABCDEF',
);

switch (probe.status) {
  case EzvizDeviceStatus.retry:
    // 网络或服务异常，展示重试入口。
    break;
  case EzvizDeviceStatus.add:
    await ezviz.addDevice(
      deviceSerial: 'ABC123456',
      verifyCode: 'ABCDEF',
    );
    break;
  case EzvizDeviceStatus.connectNetwork:
    final result = await ezviz.startConfigWifi(
      ssid: 'MyWifi',
      password: 'wifi_password',
      deviceSerial: 'ABC123456',
      verifyCode: 'ABCDEF',
    );
    print('配网成功：${result.deviceSerial}，方式：${result.method.name}');
    break;
  case EzvizDeviceStatus.alreadyAdded:
    // 已在当前账号，无需处理。
    break;
  case EzvizDeviceStatus.addedByOtherAccount:
    // 提示设备已被其他账号添加。
    break;
}

// 中途取消配网
await ezviz.stopConfigWifi();
```

插件按 `AP > Smart+声波 > Smart > 声波` 自动选择配网方式。App 不传模式、
不解析 SDK 错误码，也不拼接设备热点信息。

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
| `probeDeviceInfo(deviceSerial, {verifyCode, deviceType})` | 返回五状态 `EzvizProbeResult` |
| `addDevice({deviceSerial, verifyCode})` | 绑定设备 |
| `deleteDevice(deviceSerial)` | 解绑设备 |
| `controlPtz({deviceSerial, direction, action})` | 云台控制 |
| `requestAudioPermission()` | 请求对讲所需的麦克风权限 |
| `startConfigWifi({ssid, password, deviceSerial, verifyCode, deviceType})` | 自动选择方式并完成配网、绑定 |
| `stopConfigWifi()` | 停止/取消配网 |
| `logout()` | 退出登录，清空本地 token |

**云台 direction**：0 上 / 1 下 / 2 左 / 3 右  
**云台 action**：0 开始 / 1 停止

---

## 主要数据模型

**`EzvizProbeResult`** — 黑盒设备探测结果：
- `status` — `retry` / `add` / `connectNetwork` / `alreadyAdded` / `addedByOtherAccount`
- `sdkErrorCode`、`message` — 日志和重试提示信息
- `provisioning` — 仅 `connectNetwork` 状态提供，由插件选定配网方式

**`EzvizProvisioningInfo`** — App 配网前需要的信息：
- `method` — `ap` / `smartAndSoundWave` / `smart` / `soundWave`
- `supports5G` — 设备是否支持 5 GHz WiFi
- `requiresManualHotspotConnection` — Android 当前固定为 `false`，热点由插件自动连接
- `hotspotSsid`、`hotspotPassword` — 插件根据设备能力生成的 AP 热点信息

**`EzvizConfigResult`** — 配网并绑定成功后的设备序列号、名称和实际配网方式

**`EzvizDevice`** — 设备基本信息：
- `deviceSerial` — 序列号，控制/删除操作的唯一标识
- `deviceName` — 设备名称
- `online` — 是否在线
- `encrypted` — 实时视频是否需要设备验证码
- `deviceType` — 设备型号
- `category` — SDK 返回的产品分类原值，区分摄像机、网关等产品族
- `cameraNum` — 通道数（摄像头数量）
- `cameras` — SDK 返回的通道列表，播放和控制使用其中的 `cameraNo`
- `capabilities` — 设备级标准能力集
- `defence` — 布防状态
- `isSupportSoundWave` — 是否支持声波（EZ）配网

**`EzvizCamera`** — 可独立播放和控制的通道：
- `cameraNo`、`cameraName` — SDK 返回的真实通道号和名称
- `videoLevel`、`videoQualities` — 当前及可用清晰度
- `shared`、`permission` — 分享与通道权限信息
- `isSubDevice` — 是否为网关/NVR 下的子设备；子设备对讲使用不同的 SDK 参数
- `capabilities` — 通道实际能力，控制按钮应优先读取这里

**`EzvizCapabilities`** — SDK 标准能力集：
- `talk` — `none` / `halfDuplex` / `fullDuplex`
- `ptz`、`zoom`、`ptzFocus` — 云台和变焦能力
- `defence`、`defencePlan`、`upgrade` — 设备管理能力
- `audioOnOff`、`soundWave`、`videoMeeting` — 音频及通话能力
- `playbackRate`、`sdRecordDownload`、`sdCover` — 回放能力
- `multiChannel`、`autoVideoLevel` — 多镜头和自动清晰度能力

**`EzvizPlayer`** — 实时画面 Widget（`StatefulWidget`）：
- 通过 `AndroidView` 嵌入原生 `EZPlayer` + `SurfaceView`
- 通过 `GlobalKey<EzvizPlayerState>` 调用 `startRealPlay`/`stopRealPlay`/`openSound`/`closeSound`/`startVoiceTalk`/`stopVoiceTalk`
- `autoPlay: true` 时 Surface 就绪后自动开始播放

对讲调用顺序为：先调用 `requestAudioPermission()`，再调用 `startVoiceTalk()`；
对讲开始时插件会关闭播放声音，停止时调用 `stopVoiceTalk()`。

## 智能家居扩展

`EZOpenSDK` 的摄像机、NVR 和网关/子设备能力可以从 `EZDeviceInfo.category`、
`deviceType`、通道对象和 `EzvizCapabilities` 判断。不同产品不要在 App 里根据名称硬编码，
建议按以下层次扩展：

1. 插件返回原始 `category`、`deviceType`、子设备标识和能力集。
2. 插件按能力封装统一命令，例如 `startVoiceTalk`、`stopVoiceTalk`、`controlPtz`。
3. App 只根据能力显示按钮，并使用统一命令。
4. 传感器、门锁、灯、开关等非视频设备使用独立的产品适配器，不要强行复用 `EZPlayer`。

当前 EZOpenSDK API 主要覆盖视频设备、通道、回放、云台、对讲和布防；
具体智能家居品类的属性读取和控制需要萤石 IoT/云端对应 API。接入新产品时应新增适配器，
而不是把所有型号塞进 `EzvizCapabilities`。

**`EzvizException`** — 统一异常：`code`（原生错误码）、`message`
