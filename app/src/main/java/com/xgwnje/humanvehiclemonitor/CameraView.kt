// CameraView.kt
package com.xgwnje.humanvehiclemonitor

import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
// import androidx.compose.ui.unit.dp // dp is used via LocalDensity.toDp()
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
// import kotlin.math.roundToInt // Not strictly needed if not rounding to Int for Size

@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    objectDetectorHelper: ObjectDetectorHelper,
    isPreviewEnabled: Boolean
) {
    val cameraViewTag = "CameraView(TFLite)"
    Log.i(cameraViewTag, "CameraView: Composable 开始组合或重组。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}") // 中文日志

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember {
        Log.d(cameraViewTag, "remember: 获取 ProcessCameraProvider 实例。") // 中文日志
        ProcessCameraProvider.getInstance(context)
    }

    val imageAnalysisExecutor: ExecutorService = remember {
        Log.d(cameraViewTag, "remember: 创建 ImageAnalysis 的单线程执行器。") // 中文日志
        Executors.newSingleThreadExecutor()
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }

        val targetAspectRatio = 4.0f / 3.0f
        var previewWidthPx: Float
        var previewHeightPx: Float

        if (maxWidthPx / maxHeightPx > targetAspectRatio) {
            previewHeightPx = maxHeightPx
            previewWidthPx = previewHeightPx * targetAspectRatio
        } else {
            previewWidthPx = maxWidthPx
            previewHeightPx = previewWidthPx / targetAspectRatio
        }

        val previewWidthDp = with(density) { previewWidthPx.toDp() }
        val previewHeightDp = with(density) { previewHeightPx.toDp() }

        Log.d(cameraViewTag, "计算得到的 PreviewView 尺寸: ${previewWidthDp}dp x ${previewHeightDp}dp (基于容器: ${maxWidth} x ${maxHeight})") // 中文日志

        val previewView = remember {
            Log.d(cameraViewTag, "remember: 创建 PreviewView 实例。") // 中文日志
            PreviewView(context).apply {
                this.scaleType = PreviewView.ScaleType.FIT_CENTER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }

        LaunchedEffect(cameraProviderFuture, objectDetectorHelper, isPreviewEnabled, maxWidth, maxHeight) {
            Log.i(cameraViewTag, "LaunchedEffect: 开始执行相机绑定逻辑。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}, Container: ${maxWidth}x${maxHeight}") // 中文日志

            val cameraProvider: ProcessCameraProvider
            try {
                cameraProvider = withContext(Dispatchers.IO) {
                    cameraProviderFuture.get()
                }
                Log.d(cameraViewTag, "LaunchedEffect: ProcessCameraProvider 获取成功。") // 中文日志
            } catch (exc: Exception) {
                Log.e(cameraViewTag, "LaunchedEffect: 获取 ProcessCameraProvider 失败。", exc) // 中文日志
                withContext(Dispatchers.Main) {
                    objectDetectorHelper.objectDetectorListener?.onError(
                        "获取相机Provider失败: ${exc.localizedMessage ?: "未知错误"}", // 中文日志
                        ObjectDetectorHelper.OTHER_ERROR
                    )
                }
                return@LaunchedEffect
            }

            try {
                cameraProvider.unbindAll() // 清理之前的绑定
                Log.i(cameraViewTag, "LaunchedEffect: 已调用 cameraProvider.unbindAll()。") // 中文日志

                val useCasesToBind = mutableListOf<UseCase>()

                if (isPreviewEnabled) {
                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also {
                            Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。目标宽高比 4:3。") // 中文日志
                            it.setSurfaceProvider(previewView.surfaceProvider)
                            Log.d(cameraViewTag, "LaunchedEffect: SurfaceProvider 已设置到 Preview UseCase。") // 中文日志
                        }
                    useCasesToBind.add(preview)
                } else {
                    Log.i(cameraViewTag, "LaunchedEffect: 预览已禁用，不创建或绑定 Preview UseCase。") // 中文日志
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。目标宽高比 4:3。") // 中文日志
                        it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                            if (!objectDetectorHelper.isClosed()) {
                                objectDetectorHelper.detectLivestreamFrame(imageProxy)
                            } else {
                                imageProxy.close()
                            }
                        }
                        Log.d(cameraViewTag, "LaunchedEffect: Analyzer 已设置到 ImageAnalysis UseCase。") // 中文日志
                    }
                useCasesToBind.add(imageAnalyzer)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                Log.d(cameraViewTag, "LaunchedEffect: CameraSelector 设置为后置摄像头。") // 中文日志

                if (useCasesToBind.isNotEmpty()) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, *useCasesToBind.toTypedArray()
                    )
                    Log.i(cameraViewTag, "LaunchedEffect: Camera UseCases (${useCasesToBind.joinToString { it::class.java.simpleName }}) 已成功绑定到生命周期。") // 中文日志
                } else {
                    Log.w(cameraViewTag, "LaunchedEffect: 没有 UseCase 需要绑定。") // 中文日志
                }

            } catch (exc: Exception) {
                Log.e(cameraViewTag, "LaunchedEffect: Use case 绑定失败。", exc) // 中文日志
                withContext(Dispatchers.Main) {
                    objectDetectorHelper.objectDetectorListener?.onError(
                        "相机绑定失败: ${exc.localizedMessage ?: "未知相机错误"}", // 中文日志
                        ObjectDetectorHelper.OTHER_ERROR
                    )
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                Log.i(cameraViewTag, "DisposableEffect (onDispose): CameraView 正在销毁。准备关闭 imageAnalysisExecutor。") // 中文日志
                imageAnalysisExecutor.shutdown()
                try {
                    if (!imageAnalysisExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)) { // 稍微增加等待时间
                        Log.w(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 在等待200ms后未终止，强制关闭。") // 中文日志
                        imageAnalysisExecutor.shutdownNow()
                    }
                    Log.i(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 已关闭。") // 中文日志
                } catch (e: InterruptedException) {
                    Log.w(cameraViewTag, "DisposableEffect (onDispose): 等待 imageAnalysisExecutor 终止时被中断。", e) // 中文日志
                    Thread.currentThread().interrupt()
                }
                // 修改点: 移除了 cameraProviderFuture.getNow(null)?.unbindAll()
                // CameraX 会通过 LifecycleOwner 处理解绑。
                // 主要的解绑逻辑在 LaunchedEffect 开始时执行。
                Log.i(cameraViewTag, "DisposableEffect (onDispose): 清理完成。") // 中文日志
            }
        }

        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .size(width = previewWidthDp, height = previewHeightDp)
                .align(Alignment.Center),
            update = { /* view -> Log.d(cameraViewTag, "AndroidView update.") */ }
        )
    }
}