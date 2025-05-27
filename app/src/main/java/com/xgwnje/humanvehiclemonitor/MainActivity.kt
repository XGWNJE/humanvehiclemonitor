// MainActivity.kt
package com.xgwnje.humanvehiclemonitor

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView // 新增: 用于获取当前 View 的 Display Rotation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.xgwnje.humanvehiclemonitor.composables.CameraPermissionRationaleDialog
import com.xgwnje.humanvehiclemonitor.composables.PermissionDeniedContent
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay
import com.xgwnje.humanvehiclemonitor.composables.SettingsScreen
import com.xgwnje.humanvehiclemonitor.composables.StatusAndControlsView
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme
import org.tensorflow.lite.task.vision.detector.Detection

// 定义报警模式的枚举
enum class AlarmMode {
    PERSON_ONLY,
    VEHICLE_ONLY,
    PERSON_AND_VEHICLE
}

// 定义目标标签
val PERSON_LABELS = setOf("person")
val VEHICLE_LABELS = setOf("car", "motorcycle", "bicycle", "bus", "truck", "vehicle")


// 定义导航路由
object AppDestinations {
    const val MAIN_SCREEN_ROUTE = "main"
    const val SETTINGS_SCREEN_ROUTE = "settings"
}

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity(TFLite)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: Activity 创建。")

        setContent {
            HumanVehicleMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Log.d(TAG, "setContent: HumanVehicleMonitorTheme 和 Surface 已应用。")
                    AppNavigation()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart: Activity 启动。")
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Activity 恢复。")
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause: Activity 暂停。")
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: Activity 停止。")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Activity 销毁。")
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val mainScreenTag = "AppNavigation(TFLite)"

    // 全局共享的设置状态
    val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
    val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
    val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
    val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }
    var alarmMode by rememberSaveable { mutableStateOf(AlarmMode.PERSON_AND_VEHICLE) }
    val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

    LaunchedEffect(
        currentThreshold.value,
        currentMaxResults.value,
        currentDelegate.value,
        currentDetectionIntervalMillis.value
    ) {
        Log.i(mainScreenTag, "全局参数已更改: T=${currentThreshold.value}, M=${currentMaxResults.value}, D=${currentDelegate.value}, I=${currentDetectionIntervalMillis.value}")
    }

    NavHost(navController = navController, startDestination = AppDestinations.MAIN_SCREEN_ROUTE) {
        composable(AppDestinations.MAIN_SCREEN_ROUTE) {
            MainScreenContent(
                navController = navController,
                currentThreshold = currentThreshold,
                currentMaxResults = currentMaxResults,
                currentDelegate = currentDelegate,
                currentDetectionIntervalMillis = currentDetectionIntervalMillis,
                alarmMode = alarmMode,
                continuousDetectionDurationMsState = continuousDetectionDurationMsState,
                onAlarmModeChange = { newMode -> alarmMode = newMode }
            )
        }
        composable(AppDestinations.SETTINGS_SCREEN_ROUTE) {
            SettingsScreen(
                currentThreshold = currentThreshold,
                currentMaxResults = currentMaxResults,
                currentDelegate = currentDelegate,
                currentDetectionIntervalMillis = currentDetectionIntervalMillis,
                currentAlarmMode = alarmMode,
                onAlarmModeChange = { newMode -> alarmMode = newMode },
                continuousDetectionDurationMsState = continuousDetectionDurationMsState,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreenContent(
    navController: NavController,
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    alarmMode: AlarmMode, // 改为 val，由 AppNavigation 管理
    continuousDetectionDurationMsState: MutableLongState, // 改为 val，由 AppNavigation 管理
    onAlarmModeChange: (AlarmMode) -> Unit // 回调以更新 AppNavigation 中的状态
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainScreenTag = "MainScreenContent(TFLite)"

    val cameraPermissionState: PermissionState =
        rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }

    // UI状态变量
    var detectionResults by remember { mutableStateOf<List<Detection>?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var sourceImageRotationForOverlay by remember { mutableIntStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 已停止") }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var lastUiUpdateTime by remember { mutableStateOf(0L) } // 用于控制状态文本更新频率

    // 控制预览和监控状态
    var isPreviewEnabled by rememberSaveable { mutableStateOf(true) }
    var isMonitoringActive by rememberSaveable { mutableStateOf(false) }

    // 报警逻辑相关状态
    val continuouslyDetectedTargets = remember { mutableStateMapOf<String, Long>() }
    val alarmedTargetsCoolDown = remember { mutableStateMapOf<String, Long>() }
    val alarmCoolDownMs = 10000L // 10秒冷却时间
    val defaultModelName = "2.tflite" // 确保此模型在 assets 文件夹中

    // 获取当前屏幕旋转状态，用于 LaunchedEffect 的 key
    val currentDisplayRotation = LocalView.current.display.rotation

    // 当检测参数（来自设置界面）改变时，重置UI检测状态
    LaunchedEffect(
        currentThreshold.value,
        currentMaxResults.value,
        currentDelegate.value,
        currentDetectionIntervalMillis.value
    ) {
        Log.i(mainScreenTag, "检测参数已更改。正在重置UI检测状态。")
        detectionResults = null
        imageWidthForOverlay = 0
        imageHeightForOverlay = 0
        sourceImageRotationForOverlay = 0
        inferenceTime = 0L
        detectionError = null // 参数变化时，清除之前的错误状态
        Log.d(mainScreenTag, "UI检测状态已重置 (因参数变更)。")
    }

    // 新增: 当屏幕旋转状态改变时，重置UI检测状态以避免绘制陈旧的识别框
    LaunchedEffect(currentDisplayRotation) {
        Log.i(mainScreenTag, "屏幕旋转状态改变为: $currentDisplayRotation. 重置UI检测状态。")
        detectionResults = null
        imageWidthForOverlay = 0
        imageHeightForOverlay = 0
        sourceImageRotationForOverlay = 0 // 确保这个也重置
        inferenceTime = 0L
        // detectionError = null; // 旋转本身不应视为错误，通常不清除错误信息
        if (isPreviewEnabled || isMonitoringActive) { // 仅在活动时更新提示
            currentStatusText = "状态: 调整方向中..."
        }
        Log.d(mainScreenTag, "UI检测状态已重置 (因屏幕旋转)。")
    }

    val objectDetectorListener = remember {
        object : ObjectDetectorListener {
            override fun onError(error: String, errorCode: Int) {
                Log.e(mainScreenTag, "onError (Listener): 收到 ObjectDetectorHelper 错误: '$error', code: $errorCode.")
                currentStatusText = "状态: 错误 ($error)" // 显示错误信息
                detectionError = error
                // 发生错误时，清除检测结果以避免绘制旧框
                detectionResults = null
                imageWidthForOverlay = 0
                imageHeightForOverlay = 0
                sourceImageRotationForOverlay = 0
                inferenceTime = 0L
            }

            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle?) {
                val currentTimeMs = SystemClock.uptimeMillis()
                if (resultBundle != null) {
                    // 收到有效结果，更新所有相关状态
                    imageWidthForOverlay = resultBundle.inputImageWidth
                    imageHeightForOverlay = resultBundle.inputImageHeight
                    sourceImageRotationForOverlay = resultBundle.sourceImageRotationDegrees
                    inferenceTime = resultBundle.inferenceTime
                    detectionResults = resultBundle.results
                    detectionError = null // 清除之前的错误状态

                    var statusUpdatedByAlarm = false
                    if (isMonitoringActive) {
                        val currentFrameDetectedLabels = mutableSetOf<String>()
                        resultBundle.results.forEach { detection ->
                            detection.categories.firstOrNull()?.label?.let { label ->
                                val normalizedLabel = label.lowercase().trim()
                                currentFrameDetectedLabels.add(normalizedLabel)
                                val isPerson = PERSON_LABELS.contains(normalizedLabel)
                                val isVehicle = VEHICLE_LABELS.any { vehicleLabel -> normalizedLabel.contains(vehicleLabel) }

                                val shouldProcessThisTarget = when (alarmMode) {
                                    AlarmMode.PERSON_ONLY -> isPerson
                                    AlarmMode.VEHICLE_ONLY -> isVehicle
                                    AlarmMode.PERSON_AND_VEHICLE -> isPerson || isVehicle
                                }

                                if (shouldProcessThisTarget) {
                                    val targetCategoryForAlarm = if (isPerson) "人员" else if (isVehicle) "车辆" else normalizedLabel
                                    val detectionStartTime = continuouslyDetectedTargets.getOrPut(targetCategoryForAlarm) { currentTimeMs }
                                    val duration = currentTimeMs - detectionStartTime

                                    if (duration >= continuousDetectionDurationMsState.value) {
                                        val lastAlarmTime = alarmedTargetsCoolDown[targetCategoryForAlarm] ?: 0L
                                        if (currentTimeMs - lastAlarmTime > alarmCoolDownMs) {
                                            val alarmMessage = "报警! 检测到${targetCategoryForAlarm}持续${duration / 1000}秒!"
                                            Log.i(mainScreenTag, alarmMessage)
                                            currentStatusText = alarmMessage
                                            statusUpdatedByAlarm = true
                                            alarmedTargetsCoolDown[targetCategoryForAlarm] = currentTimeMs
                                            continuouslyDetectedTargets[targetCategoryForAlarm] = currentTimeMs // 重置开始时间
                                        }
                                    }
                                }
                            }
                        }
                        // 移除视野中已消失的目标
                        val labelsToRemove = continuouslyDetectedTargets.keys.filterNot { trackingKey ->
                            when(trackingKey) {
                                "人员" -> PERSON_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected == label } }
                                "车辆" -> VEHICLE_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected.contains(label)} }
                                else -> currentFrameDetectedLabels.contains(trackingKey)
                            }
                        }
                        labelsToRemove.forEach { continuouslyDetectedTargets.remove(it) }
                    } else { // 如果未在监控状态，清除持续检测目标
                        continuouslyDetectedTargets.clear()
                    }

                    // 更新状态文本（如果未被报警信息覆盖）
                    if (!statusUpdatedByAlarm) {
                        val statusNeedsUpdate = currentStatusText.contains("无检测结果") ||
                                currentStatusText.startsWith("状态: 已停止") ||
                                currentStatusText.startsWith("状态: 错误") ||
                                currentStatusText.startsWith("状态: 调整方向中...") || // 从这个状态恢复
                                (currentTimeMs - lastUiUpdateTime > 300L) // 避免过于频繁的状态更新
                        if (statusNeedsUpdate) {
                            currentStatusText = if (isMonitoringActive) "状态: 监控中..." else "状态: 预览中 (未监控)"
                            lastUiUpdateTime = currentTimeMs
                        }
                    } else {
                        lastUiUpdateTime = currentTimeMs // 如果是报警更新，也记录UI更新时间
                    }

                } else { // resultBundle is null
                    // detectionResults = null; // 已在 LaunchedEffect 中或错误时清除
                    if (detectionError == null) { // 仅在没有错误时，才考虑更新为“无检测结果”
                        // 避免在 “已停止” 或 “错误” 或 “调整方向中” 时被迅速覆盖
                        if (!currentStatusText.startsWith("状态: 已停止") &&
                            !currentStatusText.startsWith("状态: 错误") &&
                            !currentStatusText.startsWith("状态: 调整方向中...")) {
                            if (isMonitoringActive) {
                                currentStatusText = "状态: 无检测结果 (监控中)"
                            } else if (isPreviewEnabled) { // 只有预览开启且未监控时显示预览无结果
                                currentStatusText = "状态: 无检测结果 (预览中)"
                            }
                            lastUiUpdateTime = currentTimeMs
                        }
                    }
                    if (isMonitoringActive) { // 如果正在监控但无结果，清除持续检测
                        continuouslyDetectedTargets.clear()
                    }
                }
            }
        }
    }

    val objectDetectorHelper = remember(
        context,
        currentThreshold.value,
        currentMaxResults.value,
        currentDelegate.value,
        currentDetectionIntervalMillis.value,
        defaultModelName // modelName 作为 key 之一
    ) {
        Log.i(mainScreenTag, "ObjectDetectorHelper: 创建/重新创建实例。T: ${currentThreshold.value}, M: ${currentMaxResults.value}, D: ${currentDelegate.value}, I: ${currentDetectionIntervalMillis.value}ms, Model: $defaultModelName")
        ObjectDetectorHelper(
            context = context,
            objectDetectorListener = objectDetectorListener,
            threshold = currentThreshold.value,
            maxResults = currentMaxResults.value,
            currentDelegate = currentDelegate.value,
            modelName = defaultModelName,
            detectionIntervalMillis = currentDetectionIntervalMillis.value
        )
    }

    DisposableEffect(lifecycleOwner, objectDetectorHelper) {
        Log.d(mainScreenTag, "DisposableEffect: 将 ObjectDetectorHelper 添加为生命周期观察者。 Helper hash: ${objectDetectorHelper.hashCode()}")
        lifecycleOwner.lifecycle.addObserver(objectDetectorHelper)
        onDispose {
            Log.d(mainScreenTag, "DisposableEffect (onDispose): 从生命周期移除 ObjectDetectorHelper 并调用 clearObjectDetector。 Helper hash: ${objectDetectorHelper.hashCode()}")
            lifecycleOwner.lifecycle.removeObserver(objectDetectorHelper)
            objectDetectorHelper.clearObjectDetector()
        }
    }

    LaunchedEffect(cameraPermissionState.status) {
        if (!cameraPermissionState.status.isGranted && cameraPermissionState.status.shouldShowRationale) {
            showRationaleDialog = true
        } else if (!cameraPermissionState.status.isGranted && !showRationaleDialog) { // 避免在对话框显示时重复请求
            Log.d(mainScreenTag, "相机权限未授予，请求权限。Rationale: ${cameraPermissionState.status.shouldShowRationale}")
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (showRationaleDialog) {
        CameraPermissionRationaleDialog(
            onDismiss = { showRationaleDialog = false },
            onConfirm = {
                showRationaleDialog = false
                cameraPermissionState.launchPermissionRequest()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CameraView(
                    objectDetectorHelper = objectDetectorHelper,
                    isPreviewEnabled = isPreviewEnabled
                )
                // 只有在预览启用、有有效图像尺寸且有检测结果时才显示叠加层
                if (isPreviewEnabled && imageWidthForOverlay > 0 && imageHeightForOverlay > 0 && detectionResults != null) {
                    ResultsOverlay(
                        results = detectionResults,
                        imageHeightFromModel = imageHeightForOverlay,
                        imageWidthFromModel = imageWidthForOverlay,
                        sourceImageRotationDegrees = sourceImageRotationForOverlay,
                        personLabels = PERSON_LABELS,
                        vehicleLabels = VEHICLE_LABELS,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            PermissionDeniedContent(
                cameraPermissionState = cameraPermissionState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        StatusAndControlsView(
            detectionError = detectionError,
            currentStatusText = currentStatusText,
            inferenceTime = inferenceTime,
            currentThresholdValue = currentThreshold.value,
            currentMaxResultsValue = currentMaxResults.value,
            currentDelegateValue = currentDelegate.value,
            currentDetectionIntervalMillisValue = currentDetectionIntervalMillis.value,
            isPreviewEnabled = isPreviewEnabled,
            isMonitoringActive = isMonitoringActive,
            onTogglePreview = {
                isPreviewEnabled = !isPreviewEnabled
                if (!isPreviewEnabled) {
                    detectionResults = null
                    imageWidthForOverlay = 0
                    imageHeightForOverlay = 0
                    sourceImageRotationForOverlay = 0
                    inferenceTime = 0L
                    currentStatusText = if (isMonitoringActive) "状态: 监控中 (预览关闭)" else "状态: 已停止"
                } else { // 预览开启
                    // 如果之前是“已停止”，且未在监控，则变为“预览中”
                    if (currentStatusText == "状态: 已停止" && !isMonitoringActive) {
                        currentStatusText = "状态: 预览中 (未监控)"
                    } else if (isMonitoringActive) {
                        currentStatusText = "状态: 监控中..."
                    } else {
                        currentStatusText = "状态: 预览中 (未监控)"
                    }
                }
            },
            onToggleMonitoring = {
                isMonitoringActive = !isMonitoringActive
                if (isMonitoringActive) {
                    currentStatusText = if (isPreviewEnabled) "状态: 监控中..." else "状态: 监控中 (预览关闭)"
                    Log.i(mainScreenTag, "监控已启动。报警模式: $alarmMode, 持续时间: ${continuousDetectionDurationMsState.value} ms")
                } else { // 停止监控
                    currentStatusText = if (isPreviewEnabled) "状态: 预览中 (未监控)" else "状态: 已停止"
                    continuouslyDetectedTargets.clear()
                    alarmedTargetsCoolDown.clear()
                    Log.i(mainScreenTag, "监控已停止。")
                }
            },
            onNavigateToSettings = {
                navController.navigate(AppDestinations.SETTINGS_SCREEN_ROUTE)
            },
            mainScreenTag = mainScreenTag,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// CameraPermissionRationaleDialog 和 PermissionDeniedContent Composable 已在 composables/PermissionViews.kt 中定义
// 如果它们之前是在 MainActivity.kt 中，请确保它们在那里或者从正确的文件导入
// 为保持此文件简洁，此处不再重复定义，假设它们已在别处或将被正确处理。
// 如果它们原来就在MainActivity.kt, 则应该保留它们：
/*
@Composable
fun CameraPermissionRationaleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要相机权限") },
        text = { Text("此应用需要相机访问权限以检测人和车辆。请授予权限。") },
        confirmButton = { Button(onClick = onConfirm) { Text("授予") } },
        dismissButton = { Button(onClick = onDismiss) { Text("拒绝") } }
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionDeniedContent(cameraPermissionState: PermissionState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "相机权限被拒绝。请在设置中启用。",
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) { Text("再次请求权限") }
    }
}
*/


@Preview(showBackground = true, name = "Landscape MainScreen Preview", widthDp = 800, heightDp = 390)
@Composable
fun DefaultPreviewLandscape() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 创建一个模拟的横屏配置
            val landscapeConfiguration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfiguration) {
                val navController = rememberNavController()
                // 为预览提供可变的 MuableState 实例
                val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
                val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
                val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
                val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }
                var alarmMode = AlarmMode.PERSON_AND_VEHICLE // 在预览中用 var 模拟
                val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

                MainScreenContent(
                    navController = navController,
                    currentThreshold = currentThreshold,
                    currentMaxResults = currentMaxResults,
                    currentDelegate = currentDelegate,
                    currentDetectionIntervalMillis = currentDetectionIntervalMillis,
                    alarmMode = alarmMode,
                    continuousDetectionDurationMsState = continuousDetectionDurationMsState,
                    onAlarmModeChange = { newMode -> alarmMode = newMode } // 模拟回调
                )
            }
        }
    }
}