package com.xgwnje.humanvehiclemonitor // 确保这里的包名与您的项目一致

import android.Manifest
import android.content.res.Configuration
// import android.graphics.Color as AndroidColor // 已注释掉
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility // 新增导入
import androidx.compose.material.icons.filled.VisibilityOff // 新增导入
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// import androidx.compose.ui.graphics.Color // 已注释掉
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay // 假设这个文件在正确的包路径下
import com.xgwnje.humanvehiclemonitor.composables.StatusAndControlsView // 假设这个文件在正确的包路径下
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme
import java.text.DecimalFormat
import kotlin.math.roundToLong

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
    Log.i(mainScreenTag, "MainScreen: Composable 开始组合或重组。")

    val cameraPermissionState: PermissionState =
        rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by rememberSaveable { mutableStateOf(false) }

    var detectionResults by remember { mutableStateOf<List<Detection>?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 空闲") }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var lastUiUpdateTime by remember { mutableStateOf(0L) }

    // 新增：控制预览是否开启的状态
    var isPreviewEnabled by rememberSaveable { mutableStateOf(true) }

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
                val currentTime = SystemClock.uptimeMillis()
                val uiUpdateIntervalMs = 80L

                if (resultBundle != null) {
                    if (currentTime - lastUiUpdateTime > uiUpdateIntervalMs || detectionResults == null) {
                        detectionResults = resultBundle.results
                        inferenceTime = resultBundle.inferenceTime
                        imageWidthForOverlay = resultBundle.inputImageWidth
                        imageHeightForOverlay = resultBundle.inputImageHeight
                        currentStatusText = "状态: 监控中"
                        detectionError = null
                        lastUiUpdateTime = currentTime
                    }
                } else {
                    if (detectionError == null) {
                        currentStatusText = "状态: 无检测结果"
                    }
                    detectionResults = null
                    lastUiUpdateTime = currentTime
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
        ModelSettingsDialog(
            currentThreshold = currentThreshold,
            currentMaxResults = currentMaxResults,
            currentDelegate = currentDelegate,
            currentDetectionIntervalMillis = currentDetectionIntervalMillis,
            onDismiss = { showSettingsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraView(
                modifier = Modifier.fillMaxSize(),
                objectDetectorHelper = objectDetectorHelper,
                isPreviewEnabled = isPreviewEnabled // 传递预览状态
            )
            // 只有在预览启用时才显示检测结果覆盖层
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
            isPreviewEnabled = isPreviewEnabled, // 传递预览状态
            onTogglePreview = { isPreviewEnabled = !isPreviewEnabled }, // 切换预览状态的回调
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsDialog(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    onDismiss: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整模型参数") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text("置信度阈值: ${df.format(currentThreshold.floatValue)}")
                Slider(
                    value = currentThreshold.floatValue,
                    onValueChange = { currentThreshold.floatValue = it },
                    valueRange = 0.1f..0.9f,
                    steps = 79
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("最大结果数: ${currentMaxResults.intValue}")
                Slider(
                    value = currentMaxResults.intValue.toFloat(),
                    onValueChange = { currentMaxResults.intValue = it.toInt() },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("推理代理:")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_CPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_CPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("CPU") }
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_GPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_GPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("GPU") }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("识别间隔 (ms): ${currentDetectionIntervalMillis.longValue}")
                Slider(
                    value = currentDetectionIntervalMillis.longValue.toFloat(),
                    onValueChange = { currentDetectionIntervalMillis.longValue = it.roundToLong() },
                    valueRange = 0f..1000f,
                    steps = 99
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("注意: 更改参数后，模型会重新加载。", fontSize = 12.sp, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}


@OptIn(ExperimentalPermissionsApi::class) // 确保 PermissionDeniedContent 也有此注解
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
