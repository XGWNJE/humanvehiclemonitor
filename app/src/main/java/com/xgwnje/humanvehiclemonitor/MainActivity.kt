package com.xgwnje.humanvehiclemonitor // 确保这里的包名与您的项目一致

import android.Manifest
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
//import androidx.compose.ui.draw.clip // 未使用的导入，可以移除
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import com.xgwnje.humanvehiclemonitor.composables.ResultsOverlay
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorListener
import com.xgwnje.humanvehiclemonitor.ui.theme.HumanVehicleMonitorTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity" // 为 MainActivity 定义 TAG
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: Activity 创建。") // 中文日志：Activity 创建
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT


        setContent {
            HumanVehicleMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Log.d(TAG, "setContent: HumanVehicleMonitorTheme 和 Surface 已应用。") // 中文日志
                    MainScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "onStart: Activity 启动。") // 中文日志
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Activity 恢复。") // 中文日志
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause: Activity 暂停。") // 中文日志
    }

    override fun onStop() {
        super.onStop()
        Log.i(TAG, "onStop: Activity 停止。") // 中文日志
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Activity 销毁。") // 中文日志
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainScreenTag = "MainScreen"
    Log.i(mainScreenTag, "MainScreen: Composable 开始组合或重组。") // 中文日志

    val cameraPermissionState: PermissionState =
        rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by remember { mutableStateOf(false) }

    var detectionResults by remember { mutableStateOf<ObjectDetectionResult?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 空闲") } // 中文状态
    var detectionError by remember { mutableStateOf<String?>(null) }
    var lastUiUpdateTime by remember { mutableStateOf(0L) } // 声明并初始化 lastUiUpdateTime

    val defaultModelName = "efficientdet_lite0.tflite"
    val currentThreshold = remember { mutableStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) } // 示例，实际值应来自您的配置
    val currentMaxResults = remember { mutableStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) } // 示例
    val currentDelegate = remember { mutableStateOf(ObjectDetectorHelper.DELEGATE_CPU) } // 示例


    val objectDetectorListener = remember {
        object : ObjectDetectorListener {
            override fun onError(error: String, errorCode: Int) {
                Log.e(mainScreenTag, "onError (Listener): 收到 ObjectDetectorHelper 错误: '$error', code: $errorCode.") // 中文日志
                currentStatusText = "状态: 错误 ($error)" // 中文状态
                detectionError = error
            }

            override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
                Log.v(mainScreenTag, "onResults (Listener): 收到新结果。图像 ${resultBundle.inputImageWidth}x${resultBundle.inputImageHeight}, 推理 ${resultBundle.inferenceTime}ms, 检测数: ${resultBundle.results.firstOrNull()?.detections()?.size ?: 0}") // 中文日志 (Verbose)
                val currentTime = SystemClock.uptimeMillis()
                val uiUpdateIntervalMs = 80L // UI 更新节流间隔

                // *** 关键修复：修正UI更新条件并更新 lastUiUpdateTime ***
                if (currentTime - lastUiUpdateTime > uiUpdateIntervalMs || detectionResults == null) {
                    detectionResults = resultBundle.results.firstOrNull()
                    inferenceTime = resultBundle.inferenceTime
                    imageWidthForOverlay = resultBundle.inputImageWidth
                    imageHeightForOverlay = resultBundle.inputImageHeight
                    currentStatusText = "状态: 监控中" // 中文状态
                    detectionError = null // 清除之前的错误
                    lastUiUpdateTime = currentTime // 正确更新 lastUiUpdateTime
                } else {
                    Log.v(mainScreenTag, "因节流跳过UI更新。间隔: ${currentTime - lastUiUpdateTime}ms") // 中文日志
                }
            }
        }
    }
    val objectDetectorHelper = remember(context, currentThreshold.value, currentMaxResults.value, currentDelegate.value, defaultModelName) { // 添加依赖项
        Log.i(mainScreenTag, "remember: 正在创建/重新创建 ObjectDetectorHelper 实例。") // 中文日志
        ObjectDetectorHelper(
            context = context,
            objectDetectorListener = objectDetectorListener,
            threshold = currentThreshold.value,
            maxResults = currentMaxResults.value,
            currentDelegate = currentDelegate.value,
            modelName = defaultModelName,
            runningMode = RunningMode.LIVE_STREAM
        )
    }
    DisposableEffect(lifecycleOwner, objectDetectorHelper) {
        lifecycleOwner.lifecycle.addObserver(objectDetectorHelper)
        onDispose {
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
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text("需要相机权限") }, // 文本：需要相机权限
            text = { Text("此应用需要相机访问权限以检测人和车辆。请授予权限。") }, // 文本：此应用需要相机访问权限以检测人和车辆。请授予权限。
            confirmButton = {
                Button(onClick = {
                    showRationaleDialog = false
                    cameraPermissionState.launchPermissionRequest()
                }) { Text("授予") } // 文本：授予
            },
            dismissButton = {
                Button(onClick = { showRationaleDialog = false }) { Text("拒绝") } // 文本：拒绝
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) { // 根 Box，允许 CameraView 铺满
        if (cameraPermissionState.status.isGranted) {
            CameraView(
                modifier = Modifier.fillMaxSize(),
                objectDetectorHelper = objectDetectorHelper
            )
            ResultsOverlay(
                results = detectionResults,
                imageHeight = imageHeightForOverlay,
                imageWidth = imageWidthForOverlay,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            PermissionDeniedContent(
                cameraPermissionState = cameraPermissionState,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // UI 控制元素层
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 状态文本的统一样式
        val statusTextModifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp) // 给一点垂直间距
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), // 半透明背景
                shape = RoundedCornerShape(8.dp) // 圆角
            )
            .padding(horizontal = 12.dp, vertical = 8.dp) // 文本内边距

        if (isLandscape) {
            // 横屏 UI
            Row(
                modifier = Modifier
                    .fillMaxSize() // 填满以利用 Alignment
                    .windowInsetsPadding(WindowInsets.safeDrawing) // 应用安全区域内边距
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp), // 应用内边距，底部多一点给按钮留空间
                verticalAlignment = Alignment.Bottom // 将整个控制面板区域对齐到底部
            ) {
                // 左侧控制面板
                Column(
                    modifier = Modifier
                        .weight(0.4f) // 调整权重，给控制面板更多空间或根据内容调整
                        .fillMaxHeight(), // 仍然填满高度，但父Row已对齐到底部
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom // 将按钮和文本推到底部
                ) {
                    Text(
                        text = if (detectionError != null) "错误: $detectionError" else "$currentStatusText (${inferenceTime}ms)",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp), // 调整字体
                        textAlign = TextAlign.Center,
                        modifier = statusTextModifier.padding(bottom = 8.dp), // 状态文本和按钮之间的间距
                        color = if (detectionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { Log.d(mainScreenTag, "按钮点击: 切换预览") /* TODO */ },
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("切换预览", fontSize = 14.sp) } // 文本：切换预览
                    Button(
                        onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控") /* TODO */ },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("开始监控", fontSize = 14.sp) } // 文本：开始监控
                }
                Spacer(modifier = Modifier.weight(0.6f)) // 右侧空白区域
            }
        } else {
            // 竖屏 UI
            Column(
                modifier = Modifier
                    .fillMaxSize() // 填满以利用 Alignment
                    .windowInsetsPadding(WindowInsets.safeDrawing) // 应用安全区域内边距
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 状态文本放顶部
                Text(
                    text = if (detectionError != null) "错误: $detectionError" else "$currentStatusText (${inferenceTime}ms)",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp), // 调整字体
                    textAlign = TextAlign.Center,
                    modifier = statusTextModifier.align(Alignment.CenterHorizontally), // 确保在Column中也居中
                    color = if (detectionError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f)) // 将按钮推到底部
                // 按钮放底部
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally) // 按钮间距并居中
                ) {
                    Button(
                        onClick = { Log.d(mainScreenTag, "按钮点击: 切换预览") /* TODO */ },
                        modifier = Modifier.weight(1f).height(52.dp), // 让按钮等宽并有足够高度
                        shape = RoundedCornerShape(12.dp) // 圆角按钮
                    ) { Text("切换预览", fontSize = 16.sp) } // 文本：切换预览
                    Button(
                        onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控") /* TODO */ },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("开始监控", fontSize = 16.sp) } // 文本：开始监控
                }
            }
        }
    }
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
            text = "相机权限被拒绝。请在设置中启用。", // 文本：相机权限被拒绝。请在设置中启用。
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = {
            cameraPermissionState.launchPermissionRequest()
        }) { Text("再次请求权限") } // 文本：再次请求权限
    }
}


@Preview(showBackground = true, name = "Portrait Preview")
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

@Preview(showBackground = true, name = "Landscape Preview", widthDp = 800, heightDp = 390) // 调整预览尺寸以更好模拟横屏
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
