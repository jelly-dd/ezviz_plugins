package com.example.ezviz_plugins

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ezviz.sdk.configwifi.EZConfigWifiErrorEnum
import com.ezviz.sdk.configwifi.EZConfigWifiInfoEnum
import com.ezviz.sdk.configwifi.EZWiFiConfig
import com.ezviz.sdk.configwifi.common.EZConfigWifiCallback
import com.videogo.openapi.EZConstants
import com.videogo.openapi.EZOpenSDK
import com.videogo.openapi.bean.EZDeviceInfo
import com.videogo.wificonfig.APWifiConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.Executors

/**
 * 萤石能力插件的 Android 原生入口。
 *
 * 通过 `com.example.matter/ezviz` MethodChannel 暴露萤石 EZOpenSDK 的
 * token 管理 / 设备列表 / 配网 / 云台控制 / 解绑等能力。
 * 同时注册 `ezviz_player_view` PlatformView，供 Flutter 端嵌入实时画面。
 *
 * 萤石大量接口是「同步阻塞 + 抛异常」的网络调用，统一丢后台线程执行，
 * 再切回主线程回调 Flutter，避免 ANR。
 */
class EzvizPluginsPlugin :
    FlutterPlugin,
    ActivityAware,
    MethodCallHandler {

    private lateinit var channel: MethodChannel
    private var appContext: android.content.Context? = null
    private var activity: Activity? = null

    private var initialized = false
    private var initError: String? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var pendingConfigResult: Result? = null

    // 配网绑定轮询状态：收到 code=54(WiFi已发给设备) 后主动轮询 addDevice，
    // 不依赖 SDK 的 code=60(平台注册) 回调（该回调在网络切换场景下不可靠）。
    private val configDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private var bindPollStarted = false

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        binding.platformViewRegistry.registerViewFactory(
            PLAYER_VIEW_TYPE,
            EzvizPlayerViewFactory(binding.binaryMessenger),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        pendingConfigResult?.error("config_cancelled", "插件已卸载", null)
        pendingConfigResult = null
        io.shutdown()
        appContext = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> init(result)
            "checkInit" -> result.success(buildStatus())
            "setAccessToken" -> setAccessToken(call, result)
            "getDeviceList" -> getDeviceList(call, result)
            "probeDeviceInfo" -> probeDeviceInfo(call, result)
            "addDevice" -> addDevice(call, result)
            "deleteDevice" -> deleteDevice(call, result)
            "controlPtz" -> controlPtz(call, result)
            "getCurrentWifiSsid" -> getCurrentWifiSsid(result)
            "startConfigWifi" -> startConfigWifi(call, result)
            "logout" -> logout(result)
            else -> result.notImplemented()
        }
    }

    /**
     * 初始化萤石 SDK（幂等）。取代宿主 Application.onCreate 里的 EZOpenSDK.initLib。
     * 需要在 AndroidManifest 里配置 EZVIZ_APP_KEY meta-data。
     */
    private fun init(result: Result) {
        if (initialized) {
            result.success(buildStatus())
            return
        }
        val ctx = appContext
        if (ctx == null) {
            result.error("no_context", "插件未 attach，appContext 为空", null)
            return
        }
        val appKey = ctx.packageManager
            .getApplicationInfo(ctx.packageName, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.getString("EZVIZ_APP_KEY") ?: ""
        try {
            EZOpenSDK.initLib(ctx.applicationContext as Application, appKey)
            initialized = true
            initError = null
        } catch (e: Throwable) {
            initialized = false
            initError = e.message ?: e.toString()
        }
        result.success(buildStatus())
    }

    private fun buildStatus(): Map<String, Any?> {
        val sdkPresent = try {
            Class.forName("com.videogo.openapi.EZOpenSDK")
            true
        } catch (e: Throwable) {
            false
        }
        return mapOf("ok" to initialized, "sdkPresent" to sdkPresent, "error" to initError)
    }

    private fun setAccessToken(call: MethodCall, result: Result) {
        val token = call.argument<String>("accessToken")
        if (token.isNullOrEmpty()) {
            result.error("bad_args", "accessToken 不能为空", null)
            return
        }
        try {
            EZOpenSDK.getInstance().setAccessToken(token)
            result.success(true)
        } catch (e: Throwable) {
            result.error("ezviz_error", e.message ?: e.toString(), null)
        }
    }

    private fun getDeviceList(call: MethodCall, result: Result) {
        val page = (call.argument<Number>("page"))?.toInt() ?: 0
        val size = (call.argument<Number>("size"))?.toInt() ?: 20
        async(result) {
            val list: List<EZDeviceInfo> =
                EZOpenSDK.getInstance().getDeviceList(page, size) ?: emptyList()
            list.map { deviceToMap(it) }
        }
    }

    private fun probeDeviceInfo(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val deviceType = call.argument<String>("deviceType") ?: ""
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            // probeDeviceInfo 可查询未绑定设备的能力集（含热点前缀）
            val probeResult = EZOpenSDK.getInstance().probeDeviceInfo(serial, deviceType)
            val probe = probeResult?.getEZProbeDeviceInfo()
            val hotspotPrefix = when (probe?.deviceHotSpot) {
                1 -> "SoftAP"
                2 -> "CAMGO"
                else -> "EZVIZ"
            }
            mapOf(
                "hotspotPrefix" to hotspotPrefix,
                "supportAP" to (probe?.supportAP ?: 0),
                "supportWifi" to (probe?.supportWifi ?: 0),
                "support5G" to (probe?.isSupport5GWiFi ?: false),
            )
        }
    }

    private fun addDevice(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val verifyCode = call.argument<String>("verifyCode")
        if (serial.isNullOrEmpty() || verifyCode.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial/verifyCode 不能为空", null)
            return
        }
        async(result) {
            EZOpenSDK.getInstance().addDevice(serial, verifyCode)
            true
        }
    }

    private fun deleteDevice(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            EZOpenSDK.getInstance().deleteDevice(serial)
            true
        }
    }

    private fun controlPtz(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val channelNo = (call.argument<Number>("channelNo"))?.toInt() ?: 1
        val direction = (call.argument<Number>("direction"))?.toInt() ?: 0
        val action = (call.argument<Number>("action"))?.toInt() ?: 0
        val speed = (call.argument<Number>("speed"))?.toInt() ?: 1
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            val cmd = when (direction) {
                0 -> EZConstants.EZPTZCommand.EZPTZCommandUp
                1 -> EZConstants.EZPTZCommand.EZPTZCommandDown
                2 -> EZConstants.EZPTZCommand.EZPTZCommandLeft
                else -> EZConstants.EZPTZCommand.EZPTZCommandRight
            }
            val act = if (action == 0) {
                EZConstants.EZPTZAction.EZPTZActionSTART
            } else {
                EZConstants.EZPTZAction.EZPTZActionSTOP
            }
            EZOpenSDK.getInstance().controlPTZ(serial, channelNo, cmd, act, speed)
            true
        }
    }

    private fun getCurrentWifiSsid(result: Result) {
        try {
            val ctx = activity ?: appContext
            if (ctx == null) {
                result.error("no_context", "无法读取WiFi信息，Activity/appContext 为空", null)
                return
            }
            // 使用萤石 SDK 自带的 WiFiUtils（优先方式）
            var ssid = com.ezviz.sdk.configwifi.WiFiUtils.getCurrentWifiSsid(ctx)
            // 如果为空或无效，fallback 到 BaseUtil（萤石 SDK 内部工具类）
            if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>" || ssid == "\"\"") {
                ssid = com.hikvision.wifi.configuration.BaseUtil.getWifiSSID(ctx)
            }
            // 返回清理后的 SSID（去掉引号）
            val cleanSsid = ssid?.trim()?.removeSurrounding("\"") ?: ""
            result.success(cleanSsid)
        } catch (e: Throwable) {
            result.error("ezviz_error", e.message ?: e.toString(), null)
        }
    }

    private fun logout(result: Result) {
        async(result) {
            EZOpenSDK.getInstance().logout()
            true
        }
    }

    private fun startConfigWifi(call: MethodCall, result: Result) {
        val ssid = call.argument<String>("ssid")
        val password = call.argument<String>("password") ?: ""
        val deviceSerial = call.argument<String>("deviceSerial")
        val verifyCode = call.argument<String>("verifyCode")
        val useAP = call.argument<Boolean>("useAP") ?: true
        // 设备热点信息（由调用方根据 probeDeviceInfo 得到的 hotspotPrefix 构造，可为 null 使用默认值）
        val hotspotSsid = call.argument<String>("hotspotSsid")
        val hotspotPwd = call.argument<String>("hotspotPwd")
        if (ssid.isNullOrEmpty() || deviceSerial.isNullOrEmpty() || verifyCode.isNullOrEmpty()) {
            result.error("bad_args", "ssid/deviceSerial/verifyCode 不能为空", null)
            return
        }
        if (activity == null) {
            result.error("no_context", "配网需要 Activity 上下文", null)
            return
        }
        
        // 重置配网状态
        configDone.set(false)
        bindPollStarted = false
        pendingConfigResult = result

        val apCallback = object : APWifiConfig.APConfigCallback() {
            override fun onSuccess() {
                android.util.Log.i("EZConfigWifiCallback", "AP onSuccess: WiFi info sent to device (code=54)")
                // WiFi信息已发给设备，立即解除热点网络绑定，确保后续 addDevice 轮询能走路由器网络
                releaseNetworkBinding()
                android.util.Log.i("EZConfigWifiCallback", "AP: Starting manual bind polling (more reliable than waiting for code=60)")
                // 不再依赖 SDK 的 code=60 回调（网络切换场景下不可靠），主动轮询绑定
                if (!bindPollStarted) {
                    bindPollStarted = true
                    startBindPolling(deviceSerial, verifyCode)
                }
            }

            override fun onInfo(code: Int, message: String?) {
                // SDK 的 code=60 (CONNECTED_TO_PLATFORM) 回调在网络切换场景下不可靠，
                // 已改用 onSuccess 后主动轮询 addDevice，此回调仅用于日志记录
                android.util.Log.i("EZConfigWifiCallback", "AP onInfo: code=$code, message=$message (ignored, using polling instead)")
            }

            override fun OnError(code: Int) {
                android.util.Log.e("EZConfigWifiCallback", "AP OnError: code=$code")
                android.util.Log.e("EZConfigWifiCallback", "AP OnError: known codes - PHONE_NOT_CONNECTED=${EZConfigWifiErrorEnum.PHONE_NOT_CONNECTED_TO_TARGET_WIFI.code}, TIMEOUT=${EZConfigWifiErrorEnum.CONFIG_TIMEOUT.code}, USER_REFUSED=${EZConfigWifiErrorEnum.USER_REFUSED_CONNECTION_REQUEST.code}")
                
                // 检查轮询是否已完成
                if (configDone.get()) {
                    android.util.Log.i("EZConfigWifiCallback", "AP OnError: bind polling already completed, ignoring error code=$code")
                    return
                }
                
                // Demo: 111（手机WiFi变化）忽略，不做处理
                if (code == EZConfigWifiErrorEnum.PHONE_NOT_CONNECTED_TO_TARGET_WIFI.code) {
                    android.util.Log.i("EZConfigWifiCallback", "Ignore error 111: phone WiFi changed during config (expected behavior)")
                    return
                }
                // Demo: 15（90秒超时）→ onTimeout → failedToConfig（跳转手动连接页面重试）
                // 我们返回 timeout 让 Dart 层引导用户重新连接热点重试
                if (code == EZConfigWifiErrorEnum.CONFIG_TIMEOUT.code) {
                    android.util.Log.w("EZConfigWifiCallback", "Config timeout after 90s, stopping polling")
                    configDone.set(true)
                    bindPollStarted = false
                    main.post {
                        EZOpenSDK.getInstance().stopAPConfigWifiWithSsid()
                        releaseNetworkBinding()
                        pendingConfigResult?.error("timeout", "配网超时（90秒），请确认：1.手机已连接设备热点 2.路由器WiFi密码正确", mapOf("code" to 15))
                        pendingConfigResult = null
                    }
                    return
                }
                // Demo: USER_REFUSED 用户拒绝连接设备热点（Android 10+）
                if (code == EZConfigWifiErrorEnum.USER_REFUSED_CONNECTION_REQUEST.code) {
                    android.util.Log.e("EZConfigWifiCallback", "User refused to connect to device hotspot (Android 10+)")
                    configDone.set(true)
                    bindPollStarted = false
                    main.post {
                        EZOpenSDK.getInstance().stopAPConfigWifiWithSsid()
                        releaseNetworkBinding()
                        pendingConfigResult?.error("user_refused", "用户拒绝连接设备热点", mapOf("code" to code))
                        pendingConfigResult = null
                    }
                    return
                }
                // Demo: 其他错误仅记录，我们统一报错给 Dart 层
                configDone.set(true)
                bindPollStarted = false
                main.post {
                    EZOpenSDK.getInstance().stopAPConfigWifiWithSsid()
                    val errorMsg = EZConfigWifiErrorEnum.values().find { it.code == code }?.name ?: "UNKNOWN"
                    pendingConfigResult?.error("config_error", "配网失败: $errorMsg (code=$code)", mapOf("code" to code))
                    pendingConfigResult = null
                }
            }
        }

        val ezCallback = object : EZConfigWifiCallback() {
            override fun onInfo(code: Int, message: String?) {
                super.onInfo(code, message)
                android.util.Log.i("EZConfigWifiCallback", "code is $code, description is $message")
                // Demo: 收到 CONNECTED_TO_PLATFORM 才说明设备已注册到平台，此时才绑定
                if (code == EZConfigWifiInfoEnum.CONNECTED_TO_PLATFORM.code) {
                    android.util.Log.i("EZConfigWifiCallback", "EZ: device registered to platform, binding...")
                    // Demo AddDeviceToAccountActivity.tryToAddDevice: 子线程立即 addDevice
                    io.execute {
                        val added = tryBindDevice(deviceSerial, verifyCode)
                        main.post {
                            stopEzSmartConfig()
                            finishConfig(added, deviceSerial)
                        }
                    }
                }
            }

            override fun onError(code: Int, message: String?) {
                super.onError(code, message)
                android.util.Log.e("EZConfigWifiCallback", "EZ onError: code=$code, message=$message")
                main.post {
                    stopEzSmartConfig()
                    val errorMsg = message ?: "EZ配网失败 (code=$code)"
                    pendingConfigResult?.error("config_error", errorMsg, mapOf("code" to code))
                    pendingConfigResult = null
                }
            }
        }

        main.post {
            try {
                if (useAP) {
                    // 使用官方推荐的 startAPConfigWifiWithSsid（支持传入设备热点名/密码）
                    // hotspotSsid/hotspotPwd 为空时 SDK 会自动用 EZVIZ_序列号 作为热点名
                    // isAutoConnect=false：引导用户手动连接设备热点（对齐 Demo ManualConnectDeviceHotspotActivity）
                    // Dart 层需在 startConfigWifi 前校验手机已连上设备热点（getCurrentWifiSsid）
                    EZOpenSDK.getInstance().startAPConfigWifiWithSsid(
                        ssid, password,
                        deviceSerial, verifyCode,
                        hotspotSsid, hotspotPwd,
                        false, // isAutoConnect=false，用户手动连接热点
                        apCallback,
                    )
                } else {
                    val ctx = activity?.applicationContext ?: appContext!!
                    val wifiConfig = EZWiFiConfig.getInstance(ctx)
                    wifiConfig.setParams(ssid, password, deviceSerial)
                    wifiConfig.startSmartConfig()
                    wifiConfig.startSADPSearchResult(object : com.ezviz.sdk.configwifi.EZWiFiConfigApi.SadpDeviceFoundListener {
                        override fun onDeviceFound(deviceSerial: String?) {
                            android.util.Log.i("EZConfigWifiCallback", "EZ SADP onDeviceFound: $deviceSerial")
                        }
                    })
                    // EZ 模式成功/失败通过 ezCallback 的 onInfo/onError 回调
                    wifiConfig.startAPConfigSearchResult(ezCallback)
                }
            } catch (e: Throwable) {
                android.util.Log.e("EzvizPluginsPlugin", "startConfigWifi exception", e)
                pendingConfigResult?.error("ezviz_error", e.message ?: e.toString(), null)
                pendingConfigResult = null
            }
        }
    }

    private fun deviceToMap(d: EZDeviceInfo): Map<String, Any?> {
        return mapOf(
            "deviceSerial" to d.deviceSerial,
            "deviceName" to d.deviceName,
            "isOnline" to (d.status == 1),
            "deviceType" to d.deviceType,
            "cameraNum" to d.cameraNum,
            "defence" to d.defence,
            "isSupportSoundWave" to d.isSupportSoundWave(),
        )
    }

    private fun <T> async(result: Result, work: () -> T) {
        io.execute {
            try {
                val value = work()
                main.post { result.success(value) }
            } catch (e: Throwable) {
                main.post { result.error("ezviz_error", e.message ?: e.toString(), null) }
            }
        }
    }

    /**
     * Demo AddDeviceToAccountActivity.tryToAddDevice: 尝试绑定设备，容错处理错误码 20020/120020。
     * 必须在后台线程调用（阻塞网络请求）。
     */
    private fun tryBindDevice(deviceSerial: String, verifyCode: String): Boolean {
        var isAddSuccess = false
        try {
            EZOpenSDK.getInstance().addDevice(deviceSerial, verifyCode)
            isAddSuccess = true
            android.util.Log.i("EZConfigWifiCallback", "addDevice success")
        } catch (e: Throwable) {
            val errMsg = e.message ?: ""
            android.util.Log.e("EZConfigWifiCallback", "addDevice exception: $errMsg", e)
            // Demo: 仅"设备已被当前账号添加"(错误码 20020/120020) 视为成功
            if (errMsg.contains("20020") || errMsg.contains("120020")) {
                isAddSuccess = true
                android.util.Log.i("EZConfigWifiCallback", "Device already added to current account, treat as success")
            }
        }
        return isAddSuccess
    }

    /**
     * 解除进程网络绑定，恢复系统默认路由。
     * AP 配网期间 SDK 会将进程绑定到设备热点 Network，配网结束后必须解绑，
     * 否则断开热点、重连路由器后所有网络请求仍走已失效的 Network 导致无网络。
     */
    private fun releaseNetworkBinding() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ctx = appContext ?: return
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.bindProcessToNetwork(null)
            android.util.Log.i("EzvizPluginsPlugin", "releaseNetworkBinding: process network binding released")
        }
    }

    /**
     * 停止 EZ 模式配网（需要在主线程调用）。
     */
    private fun stopEzSmartConfig() {
        val ctx = activity?.applicationContext ?: appContext ?: return
        val wifiConfig = EZWiFiConfig.getInstance(ctx)
        wifiConfig.stopSmartConfig()
    }

    /**
     * 完成配网流程：获取设备名称并返回结果给 Dart 层（需要在主线程调用）。
     */
    private fun finishConfig(bindSuccess: Boolean, deviceSerial: String) {
        if (bindSuccess) {
            // Demo: 绑定成功后获取设备名称
            io.execute {
                var deviceName = ""
                try {
                    val deviceInfo = EZOpenSDK.getInstance().getDeviceInfo(deviceSerial)
                    deviceName = deviceInfo?.deviceName ?: ""
                } catch (e: Throwable) {
                    android.util.Log.w("EZConfigWifiCallback", "Failed to get device name: ${e.message}")
                }
                val finalName = deviceName
                main.post {
                    pendingConfigResult?.success(
                        mapOf("deviceSerial" to deviceSerial, "deviceName" to finalName)
                    )
                    pendingConfigResult = null
                }
            }
        } else {
            pendingConfigResult?.error("bind_error", "设备已配网但绑定失败，请稍后在设备列表手动绑定", null)
            pendingConfigResult = null
        }
    }

    /**
     * 主动轮询绑定设备（对齐 Demo AddDeviceToAccountActivity.tryToAddDevice）。
     * 
     * AP 配网收到 code=54 (onSuccess) 后，SDK 内部会轮询平台查询设备注册状态 (code=60)，
     * 但该轮询在手机网络切换场景下不可靠（手机从设备热点切回路由器时经常查不到）。
     * 
     * 改用主动轮询 addDevice：设备一旦注册上平台，addDevice 就会成功（或返回 20020/120020 已添加），
     * 不依赖 SDK 的 code=60 回调，比 Demo 的被动等待更可靠。
     * 
     * 轮询策略：前 30 秒每 2 秒一次（设备通常 10-20 秒内上线），之后每 5 秒一次，总计 90 秒超时。
     */
    private fun startBindPolling(deviceSerial: String, verifyCode: String) {
        android.util.Log.i("EZConfigWifiCallback", "startBindPolling: start polling addDevice for $deviceSerial")
        val startTime = System.currentTimeMillis()
        val maxDuration = 90_000L // 90秒总超时
        
        fun poll(attempt: Int) {
            if (configDone.get()) {
                android.util.Log.i("EZConfigWifiCallback", "startBindPolling: config already done, stop polling")
                return
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > maxDuration) {
                android.util.Log.w("EZConfigWifiCallback", "startBindPolling: exceeded 90s, giving up")
                return // SDK 的 OnError(code=15) 会处理超时
            }
            
            io.execute {
                android.util.Log.i("EZConfigWifiCallback", "startBindPolling: attempt #$attempt at ${elapsed}ms")
                val added = tryBindDevice(deviceSerial, verifyCode)
                
                if (added) {
                    android.util.Log.i("EZConfigWifiCallback", "startBindPolling: bind success at attempt #$attempt")
                    if (configDone.compareAndSet(false, true)) {
                        bindPollStarted = false
                        main.post {
                            EZOpenSDK.getInstance().stopAPConfigWifiWithSsid()
                            finishConfig(true, deviceSerial)
                        }
                    }
                } else {
                    // 绑定失败，继续轮询
                    if (!configDone.get()) {
                        val nextDelay = if (elapsed < 30_000) 2000L else 5000L
                        android.util.Log.i("EZConfigWifiCallback", "startBindPolling: bind failed, retry in ${nextDelay}ms")
                        main.postDelayed({ poll(attempt + 1) }, nextDelay)
                    }
                }
            }
        }
        
        // 首次立即尝试（设备可能已经上线）
        poll(1)
    }

    companion object {
        private const val CHANNEL_NAME = "com.example.matter/ezviz"
        internal const val PLAYER_VIEW_TYPE = "ezviz_player_view"
    }
}

