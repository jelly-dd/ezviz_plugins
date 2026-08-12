package com.example.ezviz_plugins

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.ezviz.sdk.configwifi.EZConfigWifiErrorEnum
import com.ezviz.sdk.configwifi.EZConfigWifiInfoEnum
import com.videogo.openapi.EZConstants
import com.videogo.openapi.EZOpenSDK
import com.videogo.openapi.EZOpenSDKListener
import com.videogo.openapi.bean.EZCameraInfo
import com.videogo.openapi.bean.EZAlarmInfo
import com.videogo.openapi.bean.EZDeviceInfo
import com.videogo.openapi.bean.EZDeviceRecordFile
import com.videogo.openapi.bean.EZSubDeviceInfo
import com.videogo.exception.BaseException
import com.videogo.stream.EZDeviceStreamDownload
import com.videogo.wificonfig.APWifiConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.Calendar

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
    MethodCallHandler,
    PluginRegistry.RequestPermissionsResultListener {

    private lateinit var channel: MethodChannel
    private var appContext: android.content.Context? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null

    private var initialized = false
    private var initError: String? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val deviceRecordCache = ConcurrentHashMap<String, EZDeviceRecordFile>()
    private val activeDownloads = ConcurrentHashMap<String, EZDeviceStreamDownload>()

    private var pendingConfigResult: Result? = null

    // 配网绑定轮询状态：收到 code=54(WiFi已发给设备) 后主动轮询 addDevice，
    // 不依赖 SDK 的 code=60(平台注册) 回调（该回调在网络切换场景下不可靠）。
    private val configDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private val configInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private var bindPollStarted = false
    private var activeProvisioningMethod: String? = null
    private var pendingWifiPermissionAction: (() -> Unit)? = null
    private var pendingAudioPermissionResult: Result? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)

        binding.platformViewRegistry.registerViewFactory(
            PLAYER_VIEW_TYPE,
            EzvizPlayerViewFactory(binding.binaryMessenger),
        )
        binding.platformViewRegistry.registerViewFactory(
            PLAYBACK_VIEW_TYPE,
            EzvizPlaybackViewFactory(binding.binaryMessenger),
        )
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        configDone.set(true)
        configInProgress.set(false)
        stopNativeConfig()
        releaseNetworkBinding()
        pendingWifiPermissionAction = null
        pendingAudioPermissionResult?.error("plugin_detached", "插件已卸载", null)
        pendingAudioPermissionResult = null
        pendingConfigResult?.error("config_cancelled", "插件已卸载", null)
        pendingConfigResult = null
        io.shutdown()
        activeDownloads.values.forEach { it.stop() }
        activeDownloads.clear()
        deviceRecordCache.clear()
        appContext = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(this)
        activityBinding = null
        activity = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            val pendingResult = pendingAudioPermissionResult
            pendingAudioPermissionResult = null
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                pendingResult?.success(true)
            } else {
                pendingResult?.error("permission_denied", "对讲需要麦克风权限", null)
            }
            return true
        }
        if (requestCode != WIFI_PERMISSION_REQUEST_CODE) return false
        val action = pendingWifiPermissionAction
        pendingWifiPermissionAction = null
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            failConfig("permission_denied", "自动连接设备热点需要 WiFi 扫描和定位权限")
        } else if (action != null && configInProgress.get() && !configDone.get()) {
            action()
        }
        return true
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "init" -> init(result)
            "checkInit" -> result.success(buildStatus())
            "setAccessToken" -> setAccessToken(call, result)
            "getDeviceList" -> getDeviceList(call, result)
            "getAlarmList" -> getAlarmList(call, result)
            "getUnreadAlarmCount" -> getUnreadAlarmCount(call, result)
            "markAlarmsRead" -> markAlarmsRead(call, result)
            "deleteAlarms" -> deleteAlarms(call, result)
            "probeDeviceInfo" -> probeDeviceInfo(call, result)
            "addDevice" -> addDevice(call, result)
            "deleteDevice" -> deleteDevice(call, result)
            "controlPtz" -> controlPtz(call, result)
            "setVideoLevel" -> setVideoLevel(call, result)
            "setDefence" -> setDefence(call, result)
            "flipVideo" -> flipVideo(call, result)
            "getUpgradeStatus" -> getUpgradeStatus(call, result)
            "upgradeDevice" -> upgradeDevice(call, result)
            "getStorageStatus" -> getStorageStatus(call, result)
            "formatStorage" -> formatStorage(call, result)
            "searchDeviceRecords" -> searchDeviceRecords(call, result)
            "downloadDeviceRecord" -> downloadDeviceRecord(call, result)
            "requestAudioPermission" -> requestAudioPermission(result)
            "getCurrentWifiSsid" -> getCurrentWifiSsid(result)
            "startConfigWifi" -> startConfigWifi(call, result)
            "stopConfigWifi" -> stopConfigWifi(result)
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

    private fun getAlarmList(call: MethodCall, result: Result) {
        val deviceSerial = call.argument<String>("deviceSerial")?.takeIf { it.isNotEmpty() }
        val page = (call.argument<Number>("page"))?.toInt() ?: 0
        val size = (call.argument<Number>("size"))?.toInt() ?: 20
        val beginTime = (call.argument<Number>("beginTime"))?.toLong()?.let(::calendarAt)
        val endTime = (call.argument<Number>("endTime"))?.toLong()?.let(::calendarAt)
        async(result) {
            val alarms: List<EZAlarmInfo> = EZOpenSDK.getInstance().getAlarmList(
                deviceSerial,
                page,
                size.coerceIn(1, 20),
                beginTime,
                endTime,
            ) ?: emptyList()
            alarms.map(::alarmToMap)
        }
    }

    private fun getUnreadAlarmCount(call: MethodCall, result: Result) {
        val deviceSerial = call.argument<String>("deviceSerial")?.takeIf { it.isNotEmpty() }
        async(result) {
            EZOpenSDK.getInstance().getUnreadMessageCount(
                deviceSerial,
                EZConstants.EZMessageType.EZMessageTypeAlarm,
            )
        }
    }

    private fun markAlarmsRead(call: MethodCall, result: Result) {
        val alarmIds = call.argument<List<String>>("alarmIds").orEmpty()
        if (alarmIds.isEmpty()) {
            result.error("bad_args", "alarmIds 不能为空", null)
            return
        }
        async(result) {
            check(
                EZOpenSDK.getInstance().setAlarmStatus(
                    alarmIds,
                    EZConstants.EZAlarmStatus.EZAlarmStatusRead,
                ),
            ) { "告警标记已读未成功" }
            true
        }
    }

    private fun deleteAlarms(call: MethodCall, result: Result) {
        val alarmIds = call.argument<List<String>>("alarmIds").orEmpty()
        if (alarmIds.isEmpty()) {
            result.error("bad_args", "alarmIds 不能为空", null)
            return
        }
        async(result) {
            check(EZOpenSDK.getInstance().deleteAlarm(alarmIds)) { "删除告警未成功" }
            true
        }
    }

    private fun probeDeviceInfo(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val verifyCode = call.argument<String>("verifyCode") ?: ""
        val deviceType = call.argument<String>("deviceType") ?: ""
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            probeDevice(serial, verifyCode, deviceType).toMap()
        }
    }

    private data class DeviceProbe(
        val status: String,
        val sdkErrorCode: Int?,
        val message: String?,
        val provisioningMethod: String?,
        val supports5G: Boolean,
        val hotspotSsid: String?,
        val hotspotPassword: String?,
    ) {
        fun toMap(): Map<String, Any?> {
            val provisioning = provisioningMethod?.let {
                mapOf(
                    "method" to it,
                    "supports5G" to supports5G,
                    "requiresManualHotspotConnection" to false,
                    "hotspotSsid" to hotspotSsid,
                    "hotspotPassword" to hotspotPassword,
                )
            }
            return mapOf(
                "status" to status,
                "sdkErrorCode" to sdkErrorCode,
                "message" to message,
                "provisioning" to provisioning,
            )
        }
    }

    private fun probeDevice(
        deviceSerial: String,
        verifyCode: String,
        deviceType: String,
    ): DeviceProbe {
        val result = EZOpenSDK.getInstance().probeDeviceInfo(deviceSerial, deviceType)
        if (result == null) {
            return DeviceProbe(STATUS_RETRY, null, "设备探测无返回结果", null, false, null, null)
        }

        val probe = result.ezProbeDeviceInfo
        val error = result.baseException
        val errorCode = error?.errorCode
        val status = classifyProbeStatus(errorCode)
        val method = if (status == STATUS_CONNECT_NETWORK) {
            selectProvisioningMethod(
                supportAP = probe?.supportAP ?: 0,
                supportWifi = probe?.supportWifi ?: 0,
                supportSoundWave = probe?.supportSoundWave ?: 0,
            )
        } else {
            null
        }
        val hotspotPrefix = when (probe?.deviceHotSpot) {
            1 -> "SoftAP"
            2 -> "CAMGO"
            else -> "EZVIZ"
        }
        return DeviceProbe(
            status = status,
            sdkErrorCode = errorCode,
            message = error?.message,
            provisioningMethod = method,
            supports5G = probe?.isSupport5GWiFi ?: false,
            hotspotSsid = if (method == METHOD_AP) "${hotspotPrefix}_$deviceSerial" else null,
            hotspotPassword = if (method == METHOD_AP && verifyCode.isNotEmpty()) {
                "${hotspotPrefix}_$verifyCode"
            } else {
                null
            },
        )
    }

    private fun addDevice(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val verifyCode = call.argument<String>("verifyCode")
        if (serial.isNullOrEmpty() || verifyCode.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial/verifyCode 不能为空", null)
            return
        }
        async(result) {
            try {
                EZOpenSDK.getInstance().addDevice(serial, verifyCode)
                true
            } catch (e: BaseException) {
                if (e.errorCode == 120020) true else throw e
            }
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
                3 -> EZConstants.EZPTZCommand.EZPTZCommandRight
                4 -> EZConstants.EZPTZCommand.EZPTZCommandZoomIn
                5 -> EZConstants.EZPTZCommand.EZPTZCommandZoomOut
                6 -> EZConstants.EZPTZCommand.EZPTZCommandFocusNear
                7 -> EZConstants.EZPTZCommand.EZPTZCommandFocusFar
                else -> throw IllegalArgumentException("不支持的云台方向: $direction")
            }
            val act = when (action) {
                0 -> EZConstants.EZPTZAction.EZPTZActionSTART
                1 -> EZConstants.EZPTZAction.EZPTZActionSTOP
                else -> throw IllegalArgumentException("不支持的云台动作: $action")
            }
            check(EZOpenSDK.getInstance().controlPTZ(serial, channelNo, cmd, act, speed)) {
                "云台控制未成功"
            }
            true
        }
    }

    private fun setVideoLevel(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val channelNo = (call.argument<Number>("channelNo"))?.toInt() ?: 1
        val videoLevel = (call.argument<Number>("videoLevel"))?.toInt()
        if (serial.isNullOrEmpty() || videoLevel == null) {
            result.error("bad_args", "deviceSerial/videoLevel 不能为空", null)
            return
        }
        async(result) {
            check(EZOpenSDK.getInstance().setVideoLevel(serial, channelNo, videoLevel)) {
                "清晰度设置未成功"
            }
            true
        }
    }

    private fun setDefence(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val status = (call.argument<Number>("status"))?.toInt()
        if (serial.isNullOrEmpty() || status == null) {
            result.error("bad_args", "deviceSerial/status 不能为空", null)
            return
        }
        val defenceStatus = when (status) {
            0 -> EZConstants.EZDefenceStatus.EZDefence_IPC_CLOSE
            1 -> EZConstants.EZDefenceStatus.EZDefence_IPC_OPEN
            8 -> EZConstants.EZDefenceStatus.EZDefence_ALARMHOST_ATHOME
            16 -> EZConstants.EZDefenceStatus.EZDefence_ALARMHOST_OUTER
            else -> {
                result.error("bad_args", "不支持的布防状态: $status", null)
                return
            }
        }
        async(result) {
            check(EZOpenSDK.getInstance().setDefence(serial, defenceStatus)) {
                "布防设置未成功"
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun flipVideo(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val channelNo = (call.argument<Number>("channelNo"))?.toInt() ?: 1
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            EZOpenSDK.getInstance().controlVideoFlip(
                serial,
                channelNo,
                EZConstants.EZPTZDisplayCommand.EZPTZDisplayCommandFlip,
            )
            true
        }
    }

    private fun getUpgradeStatus(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            val status = EZOpenSDK.getInstance().getDeviceUpgradeStatus(serial)
            mapOf(
                "status" to status.upgradeStatus,
                "progress" to status.upgradeProgress,
            )
        }
    }

    private fun upgradeDevice(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            EZOpenSDK.getInstance().upgradeDevice(serial)
            true
        }
    }

    private fun getStorageStatus(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        if (serial.isNullOrEmpty()) {
            result.error("bad_args", "deviceSerial 不能为空", null)
            return
        }
        async(result) {
            EZOpenSDK.getInstance().getStorageStatus(serial).orEmpty().map { storage ->
                mapOf(
                    "index" to storage.index,
                    "name" to storage.name,
                    "status" to storage.status,
                    "formatRate" to storage.formatRate,
                )
            }
        }
    }

    private fun formatStorage(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val index = (call.argument<Number>("index"))?.toInt()
        if (serial.isNullOrEmpty() || index == null) {
            result.error("bad_args", "deviceSerial/index 不能为空", null)
            return
        }
        async(result) {
            check(EZOpenSDK.getInstance().formatStorage(serial, index)) {
                "存储格式化命令未成功"
            }
            true
        }
    }

    private fun searchDeviceRecords(call: MethodCall, result: Result) {
        val serial = call.argument<String>("deviceSerial")
        val channelNo = (call.argument<Number>("channelNo"))?.toInt() ?: 1
        val startTime = (call.argument<Number>("startTime"))?.toLong()
        val endTime = (call.argument<Number>("endTime"))?.toLong()
        if (serial.isNullOrEmpty() || startTime == null || endTime == null || startTime >= endTime) {
            result.error("bad_args", "deviceSerial/startTime/endTime 参数无效", null)
            return
        }
        async(result) {
            val records = EZOpenSDK.getInstance().searchRecordFileFromDevice(
                serial,
                channelNo,
                calendarAt(startTime),
                calendarAt(endTime),
            ).orEmpty()
            records.mapNotNull { record ->
                val start = record.startTime?.timeInMillis ?: return@mapNotNull null
                val end = record.stopTime?.timeInMillis ?: return@mapNotNull null
                val recordId = "$serial:$channelNo:$start:$end"
                deviceRecordCache[recordId] = record
                mapOf(
                    "recordId" to recordId,
                    "startTime" to start,
                    "endTime" to end,
                )
            }.sortedBy { (it["startTime"] as Long) }
        }
    }

    private fun downloadDeviceRecord(call: MethodCall, result: Result) {
        val recordId = call.argument<String>("recordId")
        val verifyCode = call.argument<String>("verifyCode").orEmpty()
        val record = recordId?.let(deviceRecordCache::get)
        val parts = recordId?.split(':')
        val serial = parts?.getOrNull(0)
        val channelNo = parts?.getOrNull(1)?.toIntOrNull()
        val context = appContext
        if (recordId.isNullOrEmpty() || record == null || serial.isNullOrEmpty() ||
            channelNo == null || context == null
        ) {
            result.error("record_expired", "录像记录已失效，请重新查询录像列表", null)
            return
        }
        if (activeDownloads.containsKey(recordId)) {
            result.error("download_in_progress", "该录像正在下载", null)
            return
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val folder = File(root, "ezviz/downloads").apply { mkdirs() }
        val outputFile = File(folder, "device_${System.currentTimeMillis()}.mp4")
        val downloader = try {
            EZDeviceStreamDownload(
                outputFile.absolutePath,
                serial,
                channelNo,
                record,
            )
        } catch (error: Throwable) {
            result.error("download_failed", error.message ?: error.toString(), null)
            return
        }
        val completed = AtomicBoolean(false)
        fun fail(code: String, message: String) {
            if (!completed.compareAndSet(false, true)) return
            activeDownloads.remove(recordId)
            main.post { result.error(code, message, null) }
        }
        downloader.setStreamDownloadCallback(
            object : EZOpenSDKListener.EZStreamDownloadCallbackEx() {
                override fun onDownloadingSize(downloadSize: Long) = Unit

                override fun onSuccess(filepath: String?) {
                    if (!completed.compareAndSet(false, true)) return
                    activeDownloads.remove(recordId)
                    main.post { result.success(filepath ?: outputFile.absolutePath) }
                }

                override fun onError(code: EZOpenSDKListener.EZStreamDownloadError?) {
                    fail("download_failed", code?.name ?: "录像下载失败")
                }

                override fun onErrorCode(code: Int) {
                    fail("download_failed", "录像下载失败，错误码: $code")
                }
            },
        )
        if (verifyCode.isNotEmpty()) {
            downloader.setSecretKey(verifyCode)
        }
        activeDownloads[recordId] = downloader
        io.execute {
            try {
                downloader.start()
            } catch (error: Throwable) {
                fail("download_failed", error.message ?: error.toString())
            }
        }
    }

    private fun calendarAt(timeInMillis: Long): Calendar =
        Calendar.getInstance().apply { this.timeInMillis = timeInMillis }

    private fun requestAudioPermission(result: Result) {
        val currentActivity = activity
        if (currentActivity == null) {
            result.error("no_activity", "申请麦克风权限需要前台 Activity", null)
            return
        }
        if (currentActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }
        if (pendingAudioPermissionResult != null) {
            result.error("permission_pending", "麦克风权限申请正在进行", null)
            return
        }
        pendingAudioPermissionResult = result
        currentActivity.requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            AUDIO_PERMISSION_REQUEST_CODE,
        )
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
        val deviceType = call.argument<String>("deviceType") ?: ""
        if (ssid.isNullOrEmpty() || deviceSerial.isNullOrEmpty() || verifyCode.isNullOrEmpty()) {
            result.error("bad_args", "ssid/deviceSerial/verifyCode 不能为空", null)
            return
        }
        if (activity == null) {
            result.error("no_context", "配网需要 Activity 上下文", null)
            return
        }
        if (!configInProgress.compareAndSet(false, true)) {
            result.error("config_in_progress", "已有设备正在配网", null)
            return
        }

        configDone.set(false)
        bindPollStarted = false
        pendingConfigResult = result
        io.execute {
            try {
                val probe = probeDevice(deviceSerial, verifyCode, deviceType)
                if (probe.status != STATUS_CONNECT_NETWORK) {
                    main.post {
                        if (!configInProgress.get()) return@post
                        configInProgress.set(false)
                        pendingConfigResult = null
                        result.error(
                            "device_state_changed",
                            "设备当前状态为 ${probe.status}，无需或无法配网",
                            probe.toMap(),
                        )
                    }
                    return@execute
                }
                val method = probe.provisioningMethod
                if (method == null) {
                    main.post {
                        if (!configInProgress.get()) return@post
                        configInProgress.set(false)
                        pendingConfigResult = null
                        result.error("unsupported_provisioning", "设备没有可用的配网方式", probe.toMap())
                    }
                    return@execute
                }
                main.post {
                    if (!configInProgress.get() || configDone.get()) return@post
                    activeProvisioningMethod = method
                    startSelectedConfig(
                        method = method,
                        ssid = ssid,
                        password = password,
                        deviceSerial = deviceSerial,
                        verifyCode = verifyCode,
                        hotspotSsid = probe.hotspotSsid,
                        hotspotPassword = probe.hotspotPassword,
                    )
                }
            } catch (e: Throwable) {
                main.post {
                    if (!configInProgress.get()) return@post
                    configInProgress.set(false)
                    pendingConfigResult = null
                    result.error("ezviz_error", e.message ?: e.toString(), null)
                }
            }
        }
    }

    private fun startSelectedConfig(
        method: String,
        ssid: String,
        password: String,
        deviceSerial: String,
        verifyCode: String,
        hotspotSsid: String?,
        hotspotPassword: String?,
    ) {
        try {
            if (method == METHOD_AP) {
                runWithWifiScanPermissions {
                    try {
                        startApConfig(
                            ssid,
                            password,
                            deviceSerial,
                            verifyCode,
                            hotspotSsid,
                            hotspotPassword,
                        )
                    } catch (e: Throwable) {
                        failConfig("ezviz_error", e.message ?: e.toString())
                    }
                }
            } else {
                startMixedConfig(method, ssid, password, deviceSerial, verifyCode)
            }
        } catch (e: Throwable) {
            failConfig("ezviz_error", e.message ?: e.toString())
        }
    }

    private fun runWithWifiScanPermissions(action: () -> Unit) {
        val currentActivity = activity
        if (currentActivity == null) {
            failConfig("no_context", "自动连接设备热点需要 Activity 上下文")
            return
        }
        val requiredPermissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val missingPermissions = requiredPermissions.filter {
            currentActivity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isEmpty()) {
            action()
            return
        }
        pendingWifiPermissionAction = action
        currentActivity.requestPermissions(
            missingPermissions.toTypedArray(),
            WIFI_PERMISSION_REQUEST_CODE,
        )
    }

    private fun startApConfig(
        ssid: String,
        password: String,
        deviceSerial: String,
        verifyCode: String,
        hotspotSsid: String?,
        hotspotPassword: String?,
    ) {
        val callback = object : APWifiConfig.APConfigCallback() {
            override fun onSuccess() {
                releaseNetworkBinding()
                main.post { startBindPollingOnce(deviceSerial, verifyCode) }
            }

            override fun onInfo(code: Int, message: String?) {
                if (code == EZConfigWifiInfoEnum.CONNECTED_TO_PLATFORM.code) {
                    main.post { startBindPollingOnce(deviceSerial, verifyCode) }
                }
            }

            override fun OnError(code: Int) {
                if (configDone.get() || code == EZConfigWifiErrorEnum.PHONE_NOT_CONNECTED_TO_TARGET_WIFI.code) {
                    return
                }
                val errorCode = when (code) {
                    EZConfigWifiErrorEnum.CONFIG_TIMEOUT.code -> "timeout"
                    EZConfigWifiErrorEnum.USER_REFUSED_CONNECTION_REQUEST.code -> "user_refused"
                    else -> "config_error"
                }
                val errorName = EZConfigWifiErrorEnum.values().find { it.code == code }?.name ?: "UNKNOWN"
                main.post { failConfig(errorCode, "AP 配网失败: $errorName (code=$code)", code) }
            }
        }
        EZOpenSDK.getInstance().startAPConfigWifiWithSsid(
            ssid,
            password,
            deviceSerial,
            verifyCode,
            hotspotSsid,
            hotspotPassword,
            true,
            callback,
        )
    }

    private fun startMixedConfig(
        method: String,
        ssid: String,
        password: String,
        deviceSerial: String,
        verifyCode: String,
    ) {
        val mode = when (method) {
            METHOD_SMART_AND_SOUND_WAVE ->
                EZConstants.EZWiFiConfigMode.EZWiFiConfigSmart or
                    EZConstants.EZWiFiConfigMode.EZWiFiConfigWave
            METHOD_SMART -> EZConstants.EZWiFiConfigMode.EZWiFiConfigSmart
            METHOD_SOUND_WAVE -> EZConstants.EZWiFiConfigMode.EZWiFiConfigWave
            else -> throw IllegalArgumentException("未知配网方式: $method")
        }
        val callback = object : EZOpenSDKListener.EZStartConfigWifiCallback() {
            override fun onStartConfigWifiCallback(
                serial: String?,
                status: EZConstants.EZWifiConfigStatus?,
            ) {
                when (status) {
                    EZConstants.EZWifiConfigStatus.DEVICE_PLATFORM_REGISTED ->
                        main.post { startBindPollingOnce(deviceSerial, verifyCode) }
                    EZConstants.EZWifiConfigStatus.TIME_OUT ->
                        main.post { failConfig("timeout", "配网超时，请检查 WiFi 信息后重试") }
                    else -> Unit
                }
            }
        }
        val ctx = activity?.applicationContext ?: appContext
            ?: throw IllegalStateException("配网上下文不可用")
        EZOpenSDK.getInstance().startConfigWifi(ctx, deviceSerial, ssid, password, mode, callback)
    }

    private fun startBindPollingOnce(deviceSerial: String, verifyCode: String) {
        if (bindPollStarted || configDone.get()) return
        bindPollStarted = true
        startBindPolling(deviceSerial, verifyCode)
    }

    private fun stopConfigWifi(result: Result) {
        val pending = pendingConfigResult
        configDone.set(true)
        configInProgress.set(false)
        bindPollStarted = false
        pendingWifiPermissionAction = null
        stopNativeConfig()
        releaseNetworkBinding()
        pendingConfigResult = null
        activeProvisioningMethod = null
        pending?.error("config_cancelled", "配网已取消", null)
        result.success(true)
    }

    private fun deviceToMap(d: EZDeviceInfo): Map<String, Any?> {
        val cameraInfos: List<EZCameraInfo> = when {
            !d.cameraInfoList.isNullOrEmpty() -> d.cameraInfoList
            !d.subDeviceInfoList.isNullOrEmpty() -> d.subDeviceInfoList
            else -> emptyList()
        }
        val cameras = if (cameraInfos.isNotEmpty()) {
            cameraInfos.map { cameraToMap(d, it) }
        } else {
            (1..d.cameraNum).map { cameraNo ->
                mapOf(
                    "deviceSerial" to d.deviceSerial,
                    "cameraNo" to cameraNo,
                    "cameraName" to "通道 $cameraNo",
                    "cameraCover" to null,
                    "isShared" to false,
                    "permission" to 0,
                    "videoLevel" to null,
                    "videoQualities" to emptyList<Map<String, Any?>>(),
                    "capabilities" to capabilitiesToMap(d, null),
                )
            }
        }
        return mapOf(
            "deviceSerial" to d.deviceSerial,
            "deviceName" to d.deviceName,
            "isOnline" to (d.status == 1),
            "isEncrypted" to (d.isEncrypt == 1),
            "deviceType" to d.deviceType,
            "category" to d.category,
            "cameraNum" to d.cameraNum,
            "cameras" to cameras,
            "capabilities" to capabilitiesToMap(d, null),
            "defence" to d.defence,
            "isSupportSoundWave" to d.isSupportSoundWave(),
        )
    }

    private fun alarmToMap(alarm: EZAlarmInfo): Map<String, Any?> = mapOf(
        "alarmId" to alarm.alarmId,
        "alarmName" to alarm.alarmName,
        "deviceSerial" to alarm.deviceSerial,
        "deviceName" to alarm.deviceName,
        "cameraNo" to alarm.cameraNo,
        "alarmType" to alarm.alarmType,
        "alarmPicUrl" to alarm.alarmPicUrl,
        "alarmStartTime" to alarm.alarmStartTime,
        "isRead" to (alarm.isRead == 1),
        "isEncrypted" to (alarm.isEncrypt == 1),
        "crypt" to alarm.crypt,
        "checksum" to alarm.checksum,
        "preTime" to alarm.preTime,
        "delayTime" to alarm.delayTime,
        "recordState" to alarm.recState,
        "category" to alarm.category,
    )

    private fun cameraToMap(device: EZDeviceInfo, camera: EZCameraInfo): Map<String, Any?> {
        return mapOf(
            "deviceSerial" to (camera.deviceSerial ?: device.deviceSerial),
            "cameraNo" to camera.cameraNo,
            "cameraName" to camera.cameraName,
            "cameraCover" to camera.cameraCover,
            "isSubDevice" to (camera is EZSubDeviceInfo),
            "isShared" to (camera.isShared == 1),
            "permission" to camera.permission,
            "videoLevel" to camera.videoLevel?.videoLevel,
            "videoQualities" to camera.videoQualityInfos.orEmpty().map { quality ->
                mapOf(
                    "name" to quality.videoQualityName,
                    "videoLevel" to quality.videoLevel,
                    "streamType" to quality.streamType,
                )
            },
            "capabilities" to capabilitiesToMap(device, camera),
        )
    }

    private fun capabilitiesToMap(
        device: EZDeviceInfo,
        camera: EZCameraInfo?,
    ): Map<String, Any?> {
        val subDevice = camera as? EZSubDeviceInfo
        val talk = subDevice?.isSupportTalk() ?: device.isSupportTalk()
        val useDeviceOnlyCapabilities = subDevice == null
        return mapOf(
            "talk" to when (talk) {
                EZConstants.EZTalkbackCapability.EZTalkbackFullDuplex -> "fullDuplex"
                EZConstants.EZTalkbackCapability.EZTalkbackHalfDuplex -> "halfDuplex"
                else -> "none"
            },
            "ptz" to (subDevice?.isSupportPTZ() ?: device.isSupportPTZ()),
            "zoom" to (subDevice?.isSupportZoom() ?: device.isSupportZoom()),
            "defence" to (useDeviceOnlyCapabilities && device.isSupportDefence()),
            "defencePlan" to (useDeviceOnlyCapabilities && device.isSupportDefencePlan()),
            "upgrade" to (useDeviceOnlyCapabilities && device.isSupportUpgrade()),
            "mirrorCenter" to (subDevice?.isSupportMirrorCenter() ?: device.isSupportMirrorCenter()),
            "audioOnOff" to (subDevice?.isSupportAudioOnOff() ?: device.isSupportAudioOnOff()),
            "soundWave" to (subDevice?.isSupportSoundWave() ?: device.isSupportSoundWave()),
            "ptzFocus" to (useDeviceOnlyCapabilities && device.isSupportPTZFocus()),
            "playbackRate" to (subDevice?.isSupportPlaybackRate() ?: device.isSupportPlaybackRate()),
            "directInnerRelaySpeed" to
                (subDevice?.isSupportDirectInnerRelaySpeed() ?: device.isSupportDirectInnerRelaySpeed()),
            "sdRecordDownload" to
                (subDevice?.isSupportSDRecordDownload() ?: device.isSupportSDRecordDownload()),
            "sdCover" to (subDevice?.isSupportSdCover() ?: device.isSupportSdCover()),
            "multiChannel" to (subDevice?.isSupportMultiChannel() ?: device.isSupportMultiChannel()),
            "autoVideoLevel" to
                (subDevice?.isSupportDeviceAutoVideolevel() ?: device.isSupportDeviceAutoVideolevel()),
            "videoMeeting" to (useDeviceOnlyCapabilities && device.isSupportVideoMeeting()),
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
        } catch (e: BaseException) {
            android.util.Log.e("EZConfigWifiCallback", "addDevice error: ${e.errorCode}", e)
            if (e.errorCode == 120020) {
                isAddSuccess = true
                android.util.Log.i("EZConfigWifiCallback", "Device already added to current account, treat as success")
            }
        } catch (e: Throwable) {
            android.util.Log.e("EZConfigWifiCallback", "addDevice exception", e)
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

    private fun stopNativeConfig() {
        try {
            EZOpenSDK.getInstance().stopAPConfigWifiWithSsid()
        } catch (_: Throwable) {
        }
        try {
            EZOpenSDK.getInstance().stopConfigWiFi()
        } catch (_: Throwable) {
        }
    }

    private fun failConfig(code: String, message: String, sdkCode: Int? = null) {
        if (!configDone.compareAndSet(false, true)) return
        bindPollStarted = false
        configInProgress.set(false)
        pendingWifiPermissionAction = null
        stopNativeConfig()
        releaseNetworkBinding()
        pendingConfigResult?.error(code, message, sdkCode?.let { mapOf("code" to it) })
        pendingConfigResult = null
        activeProvisioningMethod = null
    }

    /**
     * 完成配网流程：获取设备名称并返回结果给 Dart 层（需要在主线程调用）。
     */
    private fun finishConfig(bindSuccess: Boolean, deviceSerial: String) {
        if (!bindSuccess) {
            failConfig("bind_error", "设备已配网但绑定失败，请稍后重试")
            return
        }
        if (!configDone.compareAndSet(false, true)) return
        bindPollStarted = false
        configInProgress.set(false)
        pendingWifiPermissionAction = null
        stopNativeConfig()
        releaseNetworkBinding()
        val method = activeProvisioningMethod
        if (bindSuccess) {
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
                        mapOf(
                            "deviceSerial" to deviceSerial,
                            "deviceName" to finalName,
                            "provisioningMethod" to method,
                        )
                    )
                    pendingConfigResult = null
                    activeProvisioningMethod = null
                }
            }
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
                main.post { failConfig("timeout", "配网超时，请检查 WiFi 信息后重试") }
                return
            }
            
            io.execute {
                android.util.Log.i("EZConfigWifiCallback", "startBindPolling: attempt #$attempt at ${elapsed}ms")
                val added = tryBindDevice(deviceSerial, verifyCode)
                
                if (added) {
                    android.util.Log.i("EZConfigWifiCallback", "startBindPolling: bind success at attempt #$attempt")
                    main.post { finishConfig(true, deviceSerial) }
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
        private const val WIFI_PERMISSION_REQUEST_CODE = 0xE217
        private const val AUDIO_PERMISSION_REQUEST_CODE = 0xE218
        internal const val PLAYER_VIEW_TYPE = "ezviz_player_view"
        internal const val PLAYBACK_VIEW_TYPE = "ezviz_playback_view"

        private const val STATUS_RETRY = "retry"
        private const val STATUS_ADD = "add"
        private const val STATUS_CONNECT_NETWORK = "connectNetwork"
        private const val STATUS_ALREADY_ADDED = "alreadyAdded"
        private const val STATUS_ADDED_BY_OTHER_ACCOUNT = "addedByOtherAccount"

        private const val METHOD_AP = "ap"
        private const val METHOD_SMART_AND_SOUND_WAVE = "smartAndSoundWave"
        private const val METHOD_SMART = "smart"
        private const val METHOD_SOUND_WAVE = "soundWave"

        internal fun classifyProbeStatus(errorCode: Int?): String = when (errorCode) {
            null, 120021 -> STATUS_ADD
            120023, 120002, 120029 -> STATUS_CONNECT_NETWORK
            120020 -> STATUS_ALREADY_ADDED
            120022, 120024 -> STATUS_ADDED_BY_OTHER_ACCOUNT
            else -> STATUS_RETRY
        }

        internal fun selectProvisioningMethod(
            supportAP: Int,
            supportWifi: Int,
            supportSoundWave: Int,
        ): String? {
            if (supportAP == 2) return METHOD_AP
            val supportsSmart = supportWifi == 3
            val supportsSoundWave = supportSoundWave == 1
            return when {
                supportsSmart && supportsSoundWave -> METHOD_SMART_AND_SOUND_WAVE
                supportsSmart -> METHOD_SMART
                supportsSoundWave -> METHOD_SOUND_WAVE
                else -> null
            }
        }
    }
}
