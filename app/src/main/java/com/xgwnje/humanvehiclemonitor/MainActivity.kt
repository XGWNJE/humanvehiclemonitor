package com.xgwnje.humanvehiclemonitor

import android.Manifest
import android.content.res.Configuration
// import android.graphics.Color as AndroidColor // 已注释掉
// import android.media.MediaPlayer // 移除 MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.tensorflow.lite.task.vision.detector.Detection
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay
import com.xgwnje.humanvehiclemonitor.composables.SettingsDialog
import com.xgwnje.humanvehiclemonitor.composables.StatusAndControlsView
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme
import java.text.DecimalFormat
import kotlin.math.roundToLong

// 定义报警模式的枚举 (保持不变)
enum class AlarmMode {
    PERSON_ONLY,
    VEHICLE_ONLY,
    PERSON_AND_VEHICLE
}

// 定义目标标签 (保持不变)
val PERSON_LABELS = setOf("person")
val VEHICLE_LABELS = setOf("car", "motorcycle", "bicycle", "bus", "truck", "vehicle")

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity(TFLite)"
    }
    // private var mediaPlayer: MediaPlayer? = null // 移除 MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: Activity 创建。")

        // 移除 MediaPlayer 初始化
        // mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound) ...

        setContent {
            HumanVehicleMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Log.d(TAG, "setContent: HumanVehicleMonitorTheme 和 Surface 已应用。")
                    MainScreen() // 不再传递 onPlayAlarm
                }
            }
        }
    }

    // 移除 playAlarmSound 方法

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
        // 移除 mediaPlayer?.pause()
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: Activity 停止。")
        // 移除 mediaPlayer?.stop() 和 prepare()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Activity 销毁。")
        // 移除 mediaPlayer?.release()
        // mediaPlayer = null
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() { // 移除 onPlayAlarm 参数
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
    // 修改 continuousDetectionDurationMs 的声明方式
    val continuousDetectionDurationMsState = rememberSaveable { mutableLongStateOf(3000L) }

    val continuouslyDetectedTargets = remember { mutableStateMapOf<String, Long>() }
    val alarmedTargetsCoolDown = remember { mutableStateMapOf<String, Long>() }
    val alarmCoolDownMs = 10000L // 10秒冷却时间，避免状态文本频繁更新为报警状态

    val defaultModelName = "2.tflite"
    val currentThreshold = rememberSaveable { mutableFloatStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
    val currentMaxResults = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
    val currentDelegate = rememberSaveable { mutableIntStateOf(ObjectDetectorHelper.DELEGATE_CPU) }
    val currentDetectionIntervalMillis = rememberSaveable { mutableLongStateOf(ObjectDetectorHelper.DEFAULT_DETECTION_INTERVAL_MS) }

    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    val objectDetectorListener = remember {
        object : ObjectDetectorListener {
            override fun onError(error: String, errorCode: Int) {
                Log.e(mainScreenTag, "onError (Listener): 收到 ObjectDetectorHelper 错误: '$error', code: $errorCode.")
                currentStatusText = "状态: 错误 ($error)"
                detectionError = error
                detectionResults = null
                imageWidthForOverlay = 0
                imageHeightForOverlay = 0
            }

            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle?) {
                val currentTimeMs = SystemClock.uptimeMillis()

                if (resultBundle != null) {
                    var statusUpdatedByAlarm = false // 标记状态是否已被报警逻辑更新
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
                                    val targetCategoryForAlarm = if (isPerson) "人员" else if (isVehicle) "车辆" else normalizedLabel // 用于报警文本

                                    val detectionStartTime = continuouslyDetectedTargets.getOrPut(targetCategoryForAlarm) { currentTimeMs }
                                    val duration = currentTimeMs - detectionStartTime

                                    // 修复：使用 continuousDetectionDurationMsState.value
                                    if (duration >= continuousDetectionDurationMsState.value) {
                                        val lastAlarmTime = alarmedTargetsCoolDown[targetCategoryForAlarm] ?: 0L
                                        if (currentTimeMs - lastAlarmTime > alarmCoolDownMs) {
                                            val alarmMessage = "报警! 检测到${targetCategoryForAlarm}持续${duration / 1000}秒!"
                                            Log.i(mainScreenTag, alarmMessage)
                                            currentStatusText = alarmMessage // 更新状态文本以显示报警
                                            statusUpdatedByAlarm = true
                                            // onPlayAlarm() // 不再播放声音
                                            alarmedTargetsCoolDown[targetCategoryForAlarm] = currentTimeMs
                                            continuouslyDetectedTargets[targetCategoryForAlarm] = currentTimeMs
                                        }
                                    }
                                }
                            }
                        }
                        val labelsToRemove = continuouslyDetectedTargets.keys.filterNot { trackingKey ->
                            when(trackingKey) {
                                "人员" -> PERSON_LABELS.any { currentFrameDetectedLabels.contains(it) }
                                "车辆" -> VEHICLE_LABELS.any { vehicleLabel -> currentFrameDetectedLabels.any { it.contains(vehicleLabel)} }
                                else -> currentFrameDetectedLabels.contains(trackingKey)
                            }
                        }
                        labelsToRemove.forEach {
                            continuouslyDetectedTargets.remove(it)
                            alarmedTargetsCoolDown.remove(it)
                            Log.d(mainScreenTag, "目标类别 '$it' 从持续检测列表中移除。")
                        }
                    } else {
                        continuouslyDetectedTargets.clear()
                        alarmedTargetsCoolDown.clear()
                    }

                    if (!statusUpdatedByAlarm && (currentTimeMs - lastUiUpdateTime > 80L || detectionResults == null)) {
                        detectionResults = resultBundle.results
                        inferenceTime = resultBundle.inferenceTime
                        imageWidthForOverlay = resultBundle.inputImageWidth
                        imageHeightForOverlay = resultBundle.inputImageHeight
                        detectionError = null
                        currentStatusText = if (isMonitoringActive) "状态: 监控中..." else "状态: 预览中 (未监控)"
                        lastUiUpdateTime = currentTimeMs
                    } else if (statusUpdatedByAlarm) {
                        detectionResults = resultBundle.results
                        inferenceTime = resultBundle.inferenceTime
                        imageWidthForOverlay = resultBundle.inputImageWidth
                        imageHeightForOverlay = resultBundle.inputImageHeight
                        detectionError = null
                        lastUiUpdateTime = currentTimeMs
                    }


                } else { // resultBundle is null
                    if (detectionError == null) {
                        currentStatusText = if (isMonitoringActive) "状态: 无检测结果 (监控中)" else "状态: 无检测结果 (预览中)"
                    }
                    detectionResults = null
                    lastUiUpdateTime = currentTimeMs
                    if (isMonitoringActive) {
                        continuouslyDetectedTargets.clear()
                        alarmedTargetsCoolDown.clear()
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
        Log.i(mainScreenTag, "remember: 正在创建/重新创建 ObjectDetectorHelper (TFLite) 实例。 Threshold: ${currentThreshold.floatValue}, MaxResults: ${currentMaxResults.intValue}, Delegate: ${currentDelegate.intValue}, Interval: ${currentDetectionIntervalMillis.longValue}ms")
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
            // 修复：传递 MutableLongState 本身或其 .value，并确保 SettingsDialog 正确处理
            continuousDetectionDurationMsState = continuousDetectionDurationMsState, // 传递整个 State 对象
            onDismiss = { showSettingsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraView(
                modifier = Modifier.fillMaxSize(),
                objectDetectorHelper = objectDetectorHelper,
                isPreviewEnabled = isPreviewEnabled
            )
            if (isPreviewEnabled) {
                ResultsOverlay(
                    results = detectionResults,
                    imageHeight = imageHeightForOverlay,
                    imageWidth = imageWidthForOverlay,
                    modifier = Modifier.fillMaxSize()
                )
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
            onTogglePreview = { isPreviewEnabled = !isPreviewEnabled },
            onToggleMonitoring = {
                isMonitoringActive = !isMonitoringActive
                if (isMonitoringActive) {
                    currentStatusText = "状态: 监控中..."
                    Log.i(mainScreenTag, "监控已启动。报警模式: $alarmMode, 持续时间: ${continuousDetectionDurationMsState.value} ms") // 修复：使用 .value
                } else {
                    currentStatusText = "状态: 已停止"
                    continuouslyDetectedTargets.clear()
                    alarmedTargetsCoolDown.clear()
                    Log.i(mainScreenTag, "监控已停止。")
                }
            },
            onShowSettingsDialog = { showSettingsDialog = true },
            mainScreenTag = mainScreenTag
        )
    }
}

@Composable
fun CameraPermissionRationaleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要相机权限") },
        text = { Text("此应用需要相机访问权限以检测人和车辆。请授予权限。") },
        confirmButton = {
            Button(onClick = onConfirm) { Text("授予") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("拒绝") }
        }
    )
}

// SettingsDialog 现在应该接收 MutableLongState
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    currentAlarmMode: AlarmMode,
    onAlarmModeChange: (AlarmMode) -> Unit,
    continuousDetectionDurationMsState: MutableLongState, // 接收 MutableLongState
    onDismiss: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整参数和报警设置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                // ... (其他参数设置不变) ...
                Text("模型参数", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                Text("置信度阈值: ${df.format(currentThreshold.value)}") // 使用 .value for MutableFloatState if not using by
                Slider(
                    value = currentThreshold.value, // 使用 .value
                    onValueChange = { currentThreshold.value = it }, // 使用 .value
                    valueRange = 0.1f..0.9f,
                    steps = 79
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("最大结果数: ${currentMaxResults.value}") // 使用 .value for MutableIntState if not using by
                Slider(
                    value = currentMaxResults.value.toFloat(), // 使用 .value
                    onValueChange = { currentMaxResults.value = it.toInt() }, // 使用 .value
                    valueRange = 1f..10f,
                    steps = 8
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("推理代理:")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_CPU }, // 使用 .value
                        enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_CPU, // 使用 .value
                        modifier = Modifier.weight(1f)
                    ) { Text("CPU") }
                    Button(
                        onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_GPU }, // 使用 .value
                        enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_GPU, // 使用 .value
                        modifier = Modifier.weight(1f)
                    ) { Text("GPU") }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("模型识别间隔 (ms): ${currentDetectionIntervalMillis.value}") // 使用 .value
                Slider(
                    value = currentDetectionIntervalMillis.value.toFloat(), // 使用 .value
                    onValueChange = { currentDetectionIntervalMillis.value = it.roundToLong() }, // 使用 .value
                    valueRange = 0f..1000f,
                    steps = ((1000f - 0f) / 50f).toInt() - 1
                )
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("报警设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                Text("报警模式:")
                Column(Modifier.selectableGroup()) {
                    AlarmMode.values().forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (mode == currentAlarmMode),
                                    onClick = { onAlarmModeChange(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == currentAlarmMode),
                                onClick = null
                            )
                            Text(
                                text = when (mode) {
                                    AlarmMode.PERSON_ONLY -> "只报警人员"
                                    AlarmMode.VEHICLE_ONLY -> "只报警车辆"
                                    AlarmMode.PERSON_AND_VEHICLE -> "人员和车辆都报警"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("持续识别触发报警 (ms): ${continuousDetectionDurationMsState.value}") // 使用 .value
                Slider(
                    value = continuousDetectionDurationMsState.value.toFloat(), // 使用 .value
                    onValueChange = { continuousDetectionDurationMsState.value = it.roundToLong() }, // 使用 .value
                    valueRange = 500f..10000f,
                    steps = ((10000f - 500f) / 500f).toInt() - 1
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("注意: 更改参数后，模型和报警逻辑会更新。", fontSize = 12.sp, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
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
        Button(onClick = {
            cameraPermissionState.launchPermissionRequest()
        }) { Text("再次请求权限") }
    }
}

@Preview(showBackground = true, name = "Portrait Preview (TFLite)")
@Composable
fun DefaultPreviewPortrait() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MainScreen()
            }
        }
    }
}

@Preview(showBackground = true, name = "Landscape Preview (TFLite)", widthDp = 800, heightDp = 390)
@Composable
fun DefaultPreviewLandscape() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MainScreen()
            }
        }
    }
}
