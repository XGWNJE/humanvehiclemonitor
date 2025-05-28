// MainActivity.kt
package com.xgwnje.humanvehiclemonitor

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface // 新增导入
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
import androidx.compose.ui.platform.LocalDensity // 新增导入
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp // 新增导入
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

// ... (enum AlarmMode, val PERSON_LABELS, val VEHICLE_LABELS, object AppDestinations, class MainActivity 保持不变) ...
enum class AlarmMode {
    PERSON_ONLY,
    VEHICLE_ONLY,
    PERSON_AND_VEHICLE
}

val PERSON_LABELS = setOf("person")
val VEHICLE_LABELS = setOf("car", "motorcycle", "bicycle", "bus", "truck", "vehicle")

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
    alarmMode: AlarmMode,
    continuousDetectionDurationMsState: MutableLongState,
    onAlarmModeChange: (AlarmMode) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainScreenTag = "MainScreenContent(TFLite)"

    val cameraPermissionState: PermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }

    var detectionResults by remember { mutableStateOf<List<Detection>?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var sourceImageRotationForOverlay by remember { mutableIntStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 已停止") }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var lastUiUpdateTime by remember { mutableStateOf(0L) }

    var isPreviewEnabled by rememberSaveable { mutableStateOf(true) }
    var isMonitoringActive by rememberSaveable { mutableStateOf(false) }

    val continuouslyDetectedTargets = remember { mutableStateMapOf<String, Long>() }
    val alarmedTargetsCoolDown = remember { mutableStateMapOf<String, Long>() }
    val alarmCoolDownMs = 10000L
    val defaultModelName = "2.tflite"

    val currentDisplayRotation = LocalView.current.display.rotation

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
        detectionError = null
        Log.d(mainScreenTag, "UI检测状态已重置 (因参数变更)。")
    }

    LaunchedEffect(currentDisplayRotation) {
        Log.i(mainScreenTag, "屏幕旋转状态改变为: $currentDisplayRotation. 重置UI检测状态。")
        detectionResults = null
        imageWidthForOverlay = 0
        imageHeightForOverlay = 0
        sourceImageRotationForOverlay = 0
        inferenceTime = 0L
        if (isPreviewEnabled || isMonitoringActive) {
            currentStatusText = "状态: 调整方向中..."
        }
        Log.d(mainScreenTag, "UI检测状态已重置 (因屏幕旋转)。")
    }

    val objectDetectorListener = remember {
        object : ObjectDetectorListener {
            override fun onError(error: String, errorCode: Int) {
                Log.e(mainScreenTag, "onError (Listener): 收到 ObjectDetectorHelper 错误: '$error', code: $errorCode.")
                currentStatusText = "状态: 错误 ($error)"
                detectionError = error
                detectionResults = null
                imageWidthForOverlay = 0
                imageHeightForOverlay = 0
                sourceImageRotationForOverlay = 0
                inferenceTime = 0L
            }

            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle?) {
                val currentTimeMs = SystemClock.uptimeMillis()
                if (resultBundle != null) {
                    imageWidthForOverlay = resultBundle.inputImageWidth
                    imageHeightForOverlay = resultBundle.inputImageHeight
                    sourceImageRotationForOverlay = resultBundle.sourceImageRotationDegrees
                    inferenceTime = resultBundle.inferenceTime
                    detectionResults = resultBundle.results
                    detectionError = null
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
                                            continuouslyDetectedTargets[targetCategoryForAlarm] = currentTimeMs
                                        }
                                    }
                                }
                            }
                        }
                        val labelsToRemove = continuouslyDetectedTargets.keys.filterNot { trackingKey ->
                            when(trackingKey) {
                                "人员" -> PERSON_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected == label } }
                                "车辆" -> VEHICLE_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected.contains(label)} }
                                else -> currentFrameDetectedLabels.contains(trackingKey)
                            }
                        }
                        labelsToRemove.forEach { continuouslyDetectedTargets.remove(it) }
                    } else {
                        continuouslyDetectedTargets.clear()
                    }

                    if (!statusUpdatedByAlarm) {
                        val statusNeedsUpdate = currentStatusText.contains("无检测结果") ||
                                currentStatusText.startsWith("状态: 已停止") ||
                                currentStatusText.startsWith("状态: 错误") ||
                                currentStatusText.startsWith("状态: 调整方向中...") ||
                                (currentTimeMs - lastUiUpdateTime > 300L)
                        if (statusNeedsUpdate) {
                            currentStatusText = if (isMonitoringActive) "状态: 监控中..." else "状态: 预览中 (未监控)"
                            lastUiUpdateTime = currentTimeMs
                        }
                    } else {
                        lastUiUpdateTime = currentTimeMs
                    }
                } else {
                    if (detectionError == null) {
                        if (!currentStatusText.startsWith("状态: 已停止") &&
                            !currentStatusText.startsWith("状态: 错误") &&
                            !currentStatusText.startsWith("状态: 调整方向中...")) {
                            if (isMonitoringActive) {
                                currentStatusText = "状态: 无检测结果 (监控中)"
                            } else if (isPreviewEnabled) {
                                currentStatusText = "状态: 无检测结果 (预览中)"
                            }
                            lastUiUpdateTime = currentTimeMs
                        }
                    }
                    if (isMonitoringActive) { continuouslyDetectedTargets.clear() }
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
        defaultModelName
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

    DisposableEffect(lifecycleOwner, objectDetectorHelper) { /* ... (不变) ... */
        Log.d(mainScreenTag, "DisposableEffect: 将 ObjectDetectorHelper 添加为生命周期观察者。 Helper hash: ${objectDetectorHelper.hashCode()}")
        lifecycleOwner.lifecycle.addObserver(objectDetectorHelper)
        onDispose {
            Log.d(mainScreenTag, "DisposableEffect (onDispose): 从生命周期移除 ObjectDetectorHelper 并调用 clearObjectDetector。 Helper hash: ${objectDetectorHelper.hashCode()}")
            lifecycleOwner.lifecycle.removeObserver(objectDetectorHelper)
            objectDetectorHelper.clearObjectDetector()
        }
    }

    LaunchedEffect(cameraPermissionState.status) { /* ... (不变) ... */
        if (!cameraPermissionState.status.isGranted && cameraPermissionState.status.shouldShowRationale) {
            showRationaleDialog = true
        } else if (!cameraPermissionState.status.isGranted && !showRationaleDialog) {
            Log.d(mainScreenTag, "相机权限未授予，请求权限。Rationale: ${cameraPermissionState.status.shouldShowRationale}")
            cameraPermissionState.launchPermissionRequest()
        }
    }

    if (showRationaleDialog) { /* ... (不变) ... */
        CameraPermissionRationaleDialog(
            onDismiss = { showRationaleDialog = false },
            onConfirm = {
                showRationaleDialog = false
                cameraPermissionState.launchPermissionRequest()
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) { // 最外层全屏Box
        if (cameraPermissionState.status.isGranted) {
            // 这个 BoxWithConstraints 用于计算 CameraView 和 ResultsOverlay 共同的尺寸和位置
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(), // 它将填充最外层的Box
                contentAlignment = Alignment.Center // 使其内容（CameraView和ResultsOverlay）居中
            ) {
                // 从这里的约束（this.maxWidth, this.maxHeight）计算尺寸
                val density = LocalDensity.current
                // currentDisplayRotation 来自 MainScreenContent 的顶层
                val targetAspectRatio = when (currentDisplayRotation) {
                    Surface.ROTATION_0, Surface.ROTATION_180 -> 3.0f / 4.0f // 竖屏内容 W/H
                    else -> 4.0f / 3.0f // 横屏内容 W/H
                }

                val calculatedWidthDp: Dp
                val calculatedHeightDp: Dp

                // 使用 BoxWithConstraints 提供的 maxWidth 和 maxHeight (它们是Dp值)
                if (maxWidth / maxHeight > targetAspectRatio) { // 容器比内容更“宽”
                    calculatedHeightDp = maxHeight
                    calculatedWidthDp = calculatedHeightDp * targetAspectRatio
                } else { // 容器比内容更“高”或等宽
                    calculatedWidthDp = maxWidth
                    calculatedHeightDp = calculatedWidthDp / targetAspectRatio
                }
                Log.d(mainScreenTag, "统一计算尺寸: ${calculatedWidthDp}w x ${calculatedHeightDp}h, for rotation: $currentDisplayRotation")


                // CameraView 和 ResultsOverlay 都使用这个共同计算的尺寸
                // 并且因为它们是 BoxWithConstraints 的直接子项且 BoxWithConstraints 有 contentAlignment = Alignment.Center,
                // 它们会自动居中。
                CameraView(
                    modifier = Modifier.size(width = calculatedWidthDp, height = calculatedHeightDp),
                    objectDetectorHelper = objectDetectorHelper,
                    isPreviewEnabled = isPreviewEnabled
                )

                if (isPreviewEnabled && imageWidthForOverlay > 0 && imageHeightForOverlay > 0 && detectionResults != null) {
                    ResultsOverlay(
                        modifier = Modifier.size(width = calculatedWidthDp, height = calculatedHeightDp),
                        results = detectionResults,
                        imageHeightFromModel = imageHeightForOverlay,
                        imageWidthFromModel = imageWidthForOverlay,
                        sourceImageRotationDegrees = sourceImageRotationForOverlay,
                        personLabels = PERSON_LABELS,
                        vehicleLabels = VEHICLE_LABELS
                    )
                }
            }
        } else {
            PermissionDeniedContent(
                cameraPermissionState = cameraPermissionState,
                modifier = Modifier.align(Alignment.Center) // 这个 align 是相对于最外层 Box
            )
        }

        StatusAndControlsView( // StatusAndControlsView 通常是全屏覆盖的，或者有自己的对齐方式
            detectionError = detectionError,
            currentStatusText = currentStatusText,
            // ... (其他参数不变)
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
                } else {
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
                } else {
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


@Preview(showBackground = true, name = "Landscape MainScreen Preview", widthDp = 800, heightDp = 390)
@Composable
fun DefaultPreviewLandscape() {
    // ... (预览代码不变) ...
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val landscapeConfiguration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfiguration) {
                val navController = rememberNavController()
                val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
                val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
                val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
                val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }
                var alarmMode = AlarmMode.PERSON_AND_VEHICLE
                val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

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
        }
    }
}