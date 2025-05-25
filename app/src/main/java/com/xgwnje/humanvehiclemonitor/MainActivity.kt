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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
        WindowCompat.setDecorFitsSystemWindows(window, false) // 关键：允许内容绘制到系统栏后面
        // 以下两行在 HumanVehicleMonitorTheme 中也做了，但在这里设置可以确保启动时即生效
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT


        setContent {
            HumanVehicleMonitorTheme { // Theme 内部会处理状态栏和导航栏图标颜色
                Surface(
                    modifier = Modifier.fillMaxSize(), // Surface 作为根，填满整个屏幕
                    color = MaterialTheme.colorScheme.background // 背景色
                ) {
                    Log.d(TAG, "setContent: HumanVehicleMonitorTheme 和 Surface 已应用。") // 中文日志
                    MainScreen()
                }
            }
        }
    }

    // onStart, onResume, onPause, onStop, onDestroy 方法保持不变
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
    val mainScreenTag = "MainScreen"
    Log.i(mainScreenTag, "MainScreen: Composable 开始组合或重组。")

    val cameraPermissionState: PermissionState =
        rememberPermissionState(Manifest.permission.CAMERA)
    var showRationaleDialog by remember { mutableStateOf(false) }
    Log.d(mainScreenTag, "相机权限状态初始值: isGranted=${cameraPermissionState.status.isGranted}, shouldShowRationale=${cameraPermissionState.status.shouldShowRationale}")

    var detectionResults by remember { mutableStateOf<ObjectDetectionResult?>(null) }
    var inferenceTime by remember { mutableStateOf(0L) }
    var imageWidthForOverlay by remember { mutableStateOf(0) }
    var imageHeightForOverlay by remember { mutableStateOf(0) }
    var currentStatusText by remember { mutableStateOf("状态: 空闲") } // 中文状态
    var detectionError by remember { mutableStateOf<String?>(null) }

    var lastUiUpdateTime by remember { mutableStateOf(0L) }
    val uiUpdateIntervalMs = 80L // UI 更新节流间隔

    val defaultModelName = "efficientdet_lite0.tflite" // 确保此模型在 assets 文件夹中
    val currentThreshold = remember { mutableStateOf(ObjectDetectorHelper.DEFAULT_THRESHOLD) }
    val currentMaxResults = remember { mutableStateOf(ObjectDetectorHelper.DEFAULT_MAX_RESULTS) }
    val currentDelegate = remember { mutableStateOf(ObjectDetectorHelper.DELEGATE_CPU) }

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
                if (currentTime - lastUiUpdateTime > uiUpdateIntervalMs || detectionResults == null) {
                    Log.d(mainScreenTag, "onResults (Listener): 准备更新UI。上次更新时间: $lastUiUpdateTime, 当前时间: $currentTime") // 中文日志
                    detectionResults = resultBundle.results.firstOrNull()
                    inferenceTime = resultBundle.inferenceTime
                    imageWidthForOverlay = resultBundle.inputImageWidth
                    imageHeightForOverlay = resultBundle.inputImageHeight
                    currentStatusText = "状态: 监控中" // 中文状态
                    detectionError = null // 清除之前的错误
                    lastUiUpdateTime = currentTime
                } else {
                    Log.v(mainScreenTag, "因节流跳过UI更新。当前时间: $currentTime, 上次更新: $lastUiUpdateTime, 间隔: ${currentTime - lastUiUpdateTime}ms") // 中文日志 (Verbose)
                }
            }
        }
    }

    val objectDetectorHelper = remember(context, currentThreshold.value, currentMaxResults.value, currentDelegate.value, defaultModelName) {
        Log.i(mainScreenTag, "remember: 正在创建/重新创建 ObjectDetectorHelper 实例。依赖项: threshold=${currentThreshold.value}, maxResults=${currentMaxResults.value}, delegate=${currentDelegate.value}, model=$defaultModelName") // 中文日志
        ObjectDetectorHelper(
            context = context,
            objectDetectorListener = objectDetectorListener,
            threshold = currentThreshold.value,
            maxResults = currentMaxResults.value,
            currentDelegate = currentDelegate.value,
            modelName = defaultModelName,
            runningMode = RunningMode.LIVE_STREAM
        ).also {
            Log.i(mainScreenTag, "remember: ObjectDetectorHelper 实例已成功创建。实例哈希: ${it.hashCode()}") // 中文日志
        }
    }

    DisposableEffect(lifecycleOwner, objectDetectorHelper) {
        Log.i(mainScreenTag, "DisposableEffect: 开始执行。ObjectDetectorHelper 实例哈希: ${objectDetectorHelper.hashCode()}, isClosed: ${objectDetectorHelper.isClosed()}") // 中文日志
        lifecycleOwner.lifecycle.addObserver(objectDetectorHelper)
        Log.i(mainScreenTag, "DisposableEffect: ObjectDetectorHelper 观察者已添加。") // 中文日志
        onDispose {
            Log.i(mainScreenTag, "DisposableEffect (onDispose): 开始清理。准备移除观察者并清理 ObjectDetectorHelper。实例哈希: ${objectDetectorHelper.hashCode()}, isClosed before clear: ${objectDetectorHelper.isClosed()}") // 中文日志
            lifecycleOwner.lifecycle.removeObserver(objectDetectorHelper)
            Log.i(mainScreenTag, "DisposableEffect (onDispose): ObjectDetectorHelper 观察者已移除。") // 中文日志
            objectDetectorHelper.clearObjectDetector()
            Log.i(mainScreenTag, "DisposableEffect (onDispose): objectDetectorHelper.clearObjectDetector() 已调用。isClosed after clear: ${objectDetectorHelper.isClosed()}") // 中文日志
        }
    }

    LaunchedEffect(cameraPermissionState.status) {
        Log.d(mainScreenTag, "LaunchedEffect (Permission): 相机权限状态变更: ${cameraPermissionState.status}") // 中文日志
        if (!cameraPermissionState.status.isGranted && cameraPermissionState.status.shouldShowRationale) {
            Log.i(mainScreenTag, "LaunchedEffect (Permission): 需要显示权限请求理由对话框。") // 中文日志
            showRationaleDialog = true
        } else if (!cameraPermissionState.status.isGranted) {
            // 如果权限未授予且不需要显示理由（例如，用户之前选择了“不再询问”），
            // 或者这是第一次请求权限。
            Log.i(mainScreenTag, "LaunchedEffect (Permission): 权限未授予，直接请求权限。") // 中文日志
            cameraPermissionState.launchPermissionRequest()
        } else {
            Log.i(mainScreenTag, "LaunchedEffect (Permission): 相机权限已授予。") // 中文日志
            // 权限已授予，可以执行需要权限的操作，例如初始化相机
        }
    }

    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = {
                Log.d(mainScreenTag, "权限理由对话框: 用户关闭。") // 中文日志
                showRationaleDialog = false
            },
            title = { Text("需要相机权限") }, // 文本：需要相机权限
            text = { Text("此应用需要相机访问权限以检测人和车辆。请授予权限。") }, // 文本：此应用需要相机访问权限以检测人和车辆。请授予权限。
            confirmButton = {
                Button(onClick = {
                    Log.d(mainScreenTag, "权限理由对话框: 用户点击授予。") // 中文日志
                    showRationaleDialog = false
                    cameraPermissionState.launchPermissionRequest()
                }) { Text("授予") } // 文本：授予
            },
            dismissButton = {
                Button(onClick = {
                    Log.d(mainScreenTag, "权限理由对话框: 用户点击拒绝。") // 中文日志
                    showRationaleDialog = false
                }) { Text("拒绝") } // 文本：拒绝
            }
        )
    }

    // Box 作为根布局，让 CameraView 能够铺满整个屏幕（包括副控区域）
    // 这个 Box 的背景将是其父 Surface 的背景色
    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            Log.d(mainScreenTag, "相机权限已授予，显示 CameraView。") // 中文日志
            CameraView(
                modifier = Modifier.fillMaxSize(), // CameraView 填满整个 Box
                objectDetectorHelper = objectDetectorHelper
            )
            // ResultsOverlay 应该在 CameraView 之上，并且也应该能够利用整个屏幕空间，
            // 但其内部绘制的内容（检测框、文本）需要考虑安全区域。
            // ResultsOverlay 内部的 BoxWithConstraints 会处理其内容的缩放和定位。
            ResultsOverlay(
                results = detectionResults,
                imageHeight = imageHeightForOverlay,
                imageWidth = imageWidthForOverlay,
                modifier = Modifier.fillMaxSize() // ResultsOverlay 也填满整个 Box
            )
        } else {
            // 如果权限未授予，在屏幕中央显示提示信息
            PermissionDeniedContent(
                cameraPermissionState = cameraPermissionState,
                modifier = Modifier.align(Alignment.Center) // 居中显示
            )
        }

        // UI 控制元素（状态文本、按钮）应该避开系统栏和副控区域
        // 使用 WindowInsets.safeDrawing 来确保它们在安全区域内。
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.d(mainScreenTag, "当前屏幕方向: ${if (isLandscape) "横屏" else "竖屏"}") // 中文日志

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize() // 填满父 Box，使其可以对齐到边缘
                    .windowInsetsPadding(WindowInsets.safeDrawing) // 应用安全区域内边距到整个 Row
                    .padding(horizontal = 16.dp, vertical = 8.dp), // 在安全区域基础上再添加额外的应用内边距
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column( // 控制面板
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(0.3f) // 使用 weight 而不是 fillMaxWidth(0.3f) 以便在 Row 中正确分配空间
                        .padding(end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceAround
                ) {
                    Text(
                        text = if (detectionError != null) "错误: $detectionError" else "$currentStatusText ($inferenceTime ms)",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp),
                        color = if (detectionError != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(onClick = { Log.d(mainScreenTag, "按钮点击: 切换预览") /* TODO */ }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("切换预览") } // 文本：切换预览
                        Button(onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控") /* TODO */ }, modifier = Modifier.fillMaxWidth()) { Text("开始监控") } // 文本：开始监控
                    }
                }
                Spacer(modifier = Modifier.weight(0.7f)) // 占位符，将控制面板推到左边，因为相机视图在底层
            }
        } else { // Portrait
            Column(
                modifier = Modifier
                    .fillMaxSize() // 填满父 Box
                    .windowInsetsPadding(WindowInsets.safeDrawing) // 应用安全区域内边距到整个 Column
                    .padding(16.dp), // 在安全区域基础上再添加额外的应用内边距
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (detectionError != null) "错误: $detectionError" else "$currentStatusText ($inferenceTime ms)",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), // 调整边距
                    textAlign = TextAlign.Center,
                    color = if (detectionError != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                )
                // Spacer 将按钮推到底部，相机视图在底层会占据中间空间
                Spacer(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { Log.d(mainScreenTag, "按钮点击: 切换预览") /* TODO */ }) { Text("切换预览") } // 文本：切换预览
                    Button(onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控") /* TODO */ }) { Text("开始监控") } // 文本：开始监控
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionDeniedContent(cameraPermissionState: PermissionState, modifier: Modifier = Modifier) {
    val mainScreenTag = "MainScreen" // 复用TAG
    Log.d(mainScreenTag, "PermissionDeniedContent: 显示权限拒绝内容。") // 中文日志
    Column(
        modifier = modifier.padding(16.dp), // 应用传入的 modifier，并添加一些内边距
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "相机权限被拒绝。请在设置中启用。", // 文本：相机权限被拒绝。请在设置中启用。
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = {
            Log.d(mainScreenTag, "PermissionDeniedContent: 用户点击再次请求权限。") // 中文日志
            cameraPermissionState.launchPermissionRequest()
        }) { Text("再次请求权限") } // 文本：再次请求权限
    }
}


@Preview(showBackground = true, name = "Portrait Preview")
@Composable
fun DefaultPreviewPortrait() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 模拟竖屏配置
            val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MainScreen()
            }
        }
    }
}

@Preview(showBackground = true, name = "Landscape Preview", widthDp = 720, heightDp = 360)
@Composable
fun DefaultPreviewLandscape() {
    HumanVehicleMonitorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 模拟横屏配置
            val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                MainScreen()
            }
        }
    }
}
