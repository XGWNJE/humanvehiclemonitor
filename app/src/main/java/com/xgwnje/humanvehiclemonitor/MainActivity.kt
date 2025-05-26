// MainActivity.kt
package com.xgwnje.humanvehiclemonitor

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider // 修改点: 添加导入
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay
import com.xgwnje.humanvehiclemonitor.composables.StatusAndControlsView
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme
import java.text.DecimalFormat
import kotlin.math.roundToLong
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

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity(TFLite)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: Activity 创建。屏幕方向固定为横向。")

        setContent {
            HumanVehicleMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Log.d(TAG, "setContent: HumanVehicleMonitorTheme 和 Surface 已应用。")
                    MainScreen()
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainScreenTag = "MainScreen(TFLite)"

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

    var alarmMode by rememberSaveable { mutableStateOf(AlarmMode.PERSON_AND_VEHICLE) }
    val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

    val continuouslyDetectedTargets = remember { mutableStateMapOf<String, Long>() }
    val alarmedTargetsCoolDown = remember { mutableStateMapOf<String, Long>() }
    val alarmCoolDownMs = 10000L

    val defaultModelName = "2.tflite"
    val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
    val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
    val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
    val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }

    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentThreshold.floatValue, currentMaxResults.intValue, currentDelegate.intValue, currentDetectionIntervalMillis.longValue) {
        Log.i(mainScreenTag, "检测参数已更改或首次初始化参数观察。正在重置UI检测状态。")
        detectionResults = null
        imageWidthForOverlay = 0
        imageHeightForOverlay = 0
        inferenceTime = 0L
        detectionError = null
        Log.d(mainScreenTag, "UI检测状态已重置。imageWidthForOverlay: $imageWidthForOverlay")
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
                                "车辆" -> VEHICLE_LABELS.any { label -> currentFrameDetectedLabels.any { detected -> detected.contains(label) } }
                                else -> currentFrameDetectedLabels.contains(trackingKey)
                            }
                        }
                        labelsToRemove.forEach {
                            continuouslyDetectedTargets.remove(it)
                            Log.d(mainScreenTag, "目标类别 '$it' 从持续检测列表中移除，因当前帧未检测到。")
                        }
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
                    if (isMonitoringActive) {
                        continuouslyDetectedTargets.clear()
                    }
                }
            }
        }
    }

    val objectDetectorHelper = remember(
        context,
        currentThreshold.floatValue,
        currentMaxResults.intValue,
        currentDelegate.intValue,
        currentDetectionIntervalMillis.longValue,
        defaultModelName
    ) {
        Log.i(mainScreenTag, "ObjectDetectorHelper: 创建/重新创建实例。Threshold: ${currentThreshold.floatValue}, MaxResults: ${currentMaxResults.intValue}, Delegate: ${currentDelegate.intValue}, Interval: ${currentDetectionIntervalMillis.longValue}ms")
        ObjectDetectorHelper(
            context = context,
            objectDetectorListener = objectDetectorListener,
            threshold = currentThreshold.floatValue,
            maxResults = currentMaxResults.intValue,
            currentDelegate = currentDelegate.intValue,
            modelName = defaultModelName,
            detectionIntervalMillis = currentDetectionIntervalMillis.longValue
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

    if (showSettingsDialog) {
        SettingsDialog(
            currentThreshold = currentThreshold,
            currentMaxResults = currentMaxResults,
            currentDelegate = currentDelegate,
            currentDetectionIntervalMillis = currentDetectionIntervalMillis,
            currentAlarmMode = alarmMode,
            onAlarmModeChange = { newMode -> alarmMode = newMode },
            continuousDetectionDurationMsState = continuousDetectionDurationMsState,
            onDismiss = { showSettingsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraView(
                objectDetectorHelper = objectDetectorHelper,
                isPreviewEnabled = isPreviewEnabled
                // Modifier for CameraView is handled internally by its BoxWithConstraints
            )
            if (isPreviewEnabled && imageWidthForOverlay > 0 && imageHeightForOverlay > 0) {
                ResultsOverlay(
                    results = detectionResults,
                    imageHeight = imageHeightForOverlay,
                    imageWidth = imageWidthForOverlay,
                    personLabels = PERSON_LABELS,
                    vehicleLabels = VEHICLE_LABELS,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isPreviewEnabled) {
                Log.d(mainScreenTag, "预览已启用，但叠加层尺寸无效或结果为空，不绘制叠加层。W:$imageWidthForOverlay H:$imageHeightForOverlay Results Null: ${detectionResults == null}")
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
            currentThresholdValue = currentThreshold.floatValue,
            currentMaxResultsValue = currentMaxResults.intValue,
            currentDelegateValue = currentDelegate.intValue,
            currentDetectionIntervalMillisValue = currentDetectionIntervalMillis.longValue,
            isPreviewEnabled = isPreviewEnabled,
            isMonitoringActive = isMonitoringActive,
            onTogglePreview = {
                isPreviewEnabled = !isPreviewEnabled
                Log.i(mainScreenTag, "预览切换为: $isPreviewEnabled")
                if (!isPreviewEnabled) {
                    detectionResults = null
                }
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
            onShowSettingsDialog = { showSettingsDialog = true },
            mainScreenTag = mainScreenTag,
            modifier = Modifier.fillMaxSize() // StatusAndControlsView fills the screen to handle its own padding and alignment
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    currentAlarmMode: AlarmMode,
    onAlarmModeChange: (AlarmMode) -> Unit,
    continuousDetectionDurationMsState: MutableLongState,
    onDismiss: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整参数和报警设置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text("模型参数", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Text("置信度阈值: ${df.format(currentThreshold.value)}")
                Slider(value = currentThreshold.value, onValueChange = { currentThreshold.value = it }, valueRange = 0.1f..0.9f, steps = 79)
                Spacer(modifier = Modifier.height(16.dp))
                Text("最大结果数: ${currentMaxResults.value}")
                Slider(value = currentMaxResults.value.toFloat(), onValueChange = { currentMaxResults.value = it.toInt() }, valueRange = 1f..10f, steps = 8)
                Spacer(modifier = Modifier.height(16.dp))
                Text("推理代理:")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Button(onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_CPU }, enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_CPU, modifier = Modifier.weight(1f)) { Text("CPU") }
                    Button(onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_GPU }, enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_GPU, modifier = Modifier.weight(1f)) { Text("GPU") }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("模型识别间隔 (ms): ${currentDetectionIntervalMillis.value}")
                Slider(value = currentDetectionIntervalMillis.value.toFloat(), onValueChange = { currentDetectionIntervalMillis.value = it.roundToLong() }, valueRange = 0f..1000f, steps = ((1000f - 0f) / 50f).toInt() - 1)
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))
                Text("报警设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Text("报警模式:")
                Column(Modifier.selectableGroup()) {
                    AlarmMode.values().forEach { mode ->
                        Row(Modifier.fillMaxWidth().height(48.dp).selectable(selected = (mode == currentAlarmMode), onClick = { onAlarmModeChange(mode) }, role = Role.RadioButton).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = (mode == currentAlarmMode), onClick = null)
                            Text(text = when (mode) {
                                AlarmMode.PERSON_ONLY -> "只报警人员"
                                AlarmMode.VEHICLE_ONLY -> "只报警车辆"
                                AlarmMode.PERSON_AND_VEHICLE -> "人员和车辆都报警"
                            }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("持续识别触发报警 (ms): ${continuousDetectionDurationMsState.value}")
                Slider(value = continuousDetectionDurationMsState.value.toFloat(), onValueChange = { continuousDetectionDurationMsState.value = it.roundToLong() }, valueRange = 500f..10000f, steps = ((10000f - 500f) / 500f).toInt() - 1)
                Spacer(modifier = Modifier.height(8.dp))
                Text("注意: 更改参数后，模型和报警逻辑会更新。", fontSize = 12.sp, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } }
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

@Preview(showBackground = true, name = "Landscape Preview (TFLite)", widthDp = 800, heightDp = 390)
@Composable
fun DefaultPreviewLandscape() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val landscapeConfiguration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides landscapeConfiguration) { // Fixed: Added import
                MainScreen()
            }
        }
    }
}