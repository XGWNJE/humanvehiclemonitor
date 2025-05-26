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
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay
import com.xgwnje.humanvehiclemonitor.composables.SettingsScreen
import com.xgwnje.humanvehiclemonitor.composables.StatusAndControlsView
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme
import org.tensorflow.lite.task.vision.detector.Detection

// 定义报警模式的枚举 - 重新添加到文件顶层
enum class AlarmMode {
    PERSON_ONLY,
    VEHICLE_ONLY,
    PERSON_AND_VEHICLE
}

// 定义目标标签 - 重新添加到文件顶层
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
    // onStart, onResume, onPause, onStop, onDestroy 保持不变
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
    var alarmMode by rememberSaveable { mutableStateOf(AlarmMode.PERSON_AND_VEHICLE) } // 现在可以正确引用 AlarmMode
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

    val cameraPermissionState: PermissionState =
        rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }

    var detectionResults by remember { mutableStateOf<List<Detection>?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 已停止") }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var lastUiUpdateTime by remember { mutableStateOf(0L) }

    var isPreviewEnabled by rememberSaveable { mutableStateOf(true) }
    var isMonitoringActive by rememberSaveable { mutableStateOf(false) }

    val continuouslyDetectedTargets = remember { mutableStateMapOf<String, Long>() }
    val alarmedTargetsCoolDown = remember { mutableStateMapOf<String, Long>() }
    val alarmCoolDownMs = 10000L
    val defaultModelName = "2.tflite"

    LaunchedEffect(currentThreshold.value, currentMaxResults.value, currentDelegate.value, currentDetectionIntervalMillis.value) {
        Log.i(mainScreenTag, "检测参数已更改或首次初始化参数观察。正在重置UI检测状态。")
        detectionResults = null
        imageWidthForOverlay = 0
        imageHeightForOverlay = 0
        inferenceTime = 0L
        detectionError = null
        Log.d(mainScreenTag, "UI检测状态已重置。")
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
                inferenceTime = 0L
            }

            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle?) {
                val currentTimeMs = SystemClock.uptimeMillis()
                if (resultBundle != null) {
                    imageWidthForOverlay = resultBundle.inputImageWidth
                    imageHeightForOverlay = resultBundle.inputImageHeight
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
                                val isPerson = PERSON_LABELS.contains(normalizedLabel) // 现在可以正确引用
                                val isVehicle = VEHICLE_LABELS.any { vehicleLabel -> normalizedLabel.contains(vehicleLabel) } // 现在可以正确引用
                                val shouldProcessThisTarget = when (alarmMode) { // 现在可以正确引用 AlarmMode
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
                                "人员" -> PERSON_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected == label } } // 现在可以正确引用
                                "车辆" -> VEHICLE_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected.contains(label) } } // 现在可以正确引用
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
                                (currentTimeMs - lastUiUpdateTime > 200L)
                        if (statusNeedsUpdate) {
                            currentStatusText = if (isMonitoringActive) "状态: 监控中..." else "状态: 预览中 (未监控)"
                            lastUiUpdateTime = currentTimeMs
                        }
                    } else {
                        lastUiUpdateTime = currentTimeMs
                    }
                } else {
                    detectionResults = null
                    if (detectionError == null) {
                        if (!currentStatusText.contains("无检测结果")) {
                            currentStatusText = if (isMonitoringActive) "状态: 无检测结果 (监控中)" else "状态: 无检测结果 (预览中)"
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
        Log.i(mainScreenTag, "ObjectDetectorHelper: 创建/重新创建实例。T: ${currentThreshold.value}, M: ${currentMaxResults.value}, D: ${currentDelegate.value}, I: ${currentDetectionIntervalMillis.value}ms")
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
        } else if (!cameraPermissionState.status.isGranted) {
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
                CameraView( // 假设 CameraView.kt 已经存在并且可以被引用
                    objectDetectorHelper = objectDetectorHelper,
                    isPreviewEnabled = isPreviewEnabled
                )
                if (isPreviewEnabled && imageWidthForOverlay > 0 && imageHeightForOverlay > 0) {
                    ResultsOverlay(
                        results = detectionResults,
                        imageHeight = imageHeightForOverlay,
                        imageWidth = imageWidthForOverlay,
                        personLabels = PERSON_LABELS, // 现在可以正确引用
                        vehicleLabels = VEHICLE_LABELS, // 现在可以正确引用
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
                if (!isPreviewEnabled) { detectionResults = null }
            },
            onToggleMonitoring = {
                isMonitoringActive = !isMonitoringActive
                if (isMonitoringActive) {
                    currentStatusText = "状态: 监控中..."
                    Log.i(mainScreenTag, "监控已启动。报警模式: $alarmMode, 持续时间: ${continuousDetectionDurationMsState.value} ms")
                } else {
                    currentStatusText = "状态: 已停止"
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
    Column(modifier = modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = "相机权限被拒绝。请在设置中启用。", textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) { Text("再次请求权限") }
    }
}


@Preview(showBackground = true, name = "Landscape MainScreen Preview", widthDp = 800, heightDp = 390)
@Composable
fun DefaultPreviewLandscape() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val landscapeConfiguration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfiguration) {
                val navController = rememberNavController()
                val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
                val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
                val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
                val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }
                val alarmMode = AlarmMode.PERSON_AND_VEHICLE // 现在可以正确引用
                val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

                MainScreenContent(
                    navController = navController,
                    currentThreshold = currentThreshold,
                    currentMaxResults = currentMaxResults,
                    currentDelegate = currentDelegate,
                    currentDetectionIntervalMillis = currentDetectionIntervalMillis,
                    alarmMode = alarmMode,
                    continuousDetectionDurationMsState = continuousDetectionDurationMsState,
                    onAlarmModeChange = {}
                )
            }
        }
    }
}
