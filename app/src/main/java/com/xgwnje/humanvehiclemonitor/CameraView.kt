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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    objectDetectorHelper: ObjectDetectorHelper,
    isPreviewEnabled: Boolean
) {
    val cameraViewTag = "CameraView(TFLite)"

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember {
        Log.d(cameraViewTag, "remember: 获取 ProcessCameraProvider 实例。")
        ProcessCameraProvider.getInstance(context)
    }

    val imageAnalysisExecutor: ExecutorService = remember {
        Log.d(cameraViewTag, "remember: 创建 ImageAnalysis 的单线程执行器。")
        Executors.newSingleThreadExecutor()
    }

    val localView = LocalView.current
    val currentDisplayRotationKey = localView.display.rotation

    Log.i(cameraViewTag, "CameraView: Composable 开始/重组。isPreviewEnabled: $isPreviewEnabled, currentDisplayRotationKey: $currentDisplayRotationKey")

    val previewView = remember {
        Log.d(cameraViewTag, "remember: 创建 PreviewView 实例。")
        PreviewView(context).apply {
            this.scaleType = PreviewView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    BoxWithConstraints(modifier = modifier) { // Line ~70, BoxWithConstraints scope starts
        val density = LocalDensity.current
        // 这些是从 BoxWithConstraints 作用域获取的 Dp 值
        val containerMaxWidthDp = maxWidth
        val containerMaxHeightDp = maxHeight

        Log.d(cameraViewTag, "BoxWithConstraints: Container Dims (Dp): ${containerMaxWidthDp}x${containerMaxHeightDp}")

        val maxWidthPx = with(density) { containerMaxWidthDp.toPx() }
        val maxHeightPx = with(density) { containerMaxHeightDp.toPx() }

        val targetAspectRatio = 4.0f / 3.0f // 修复: 定义 targetAspectRatio

        var previewWidthPx: Float
        var previewHeightPx: Float

        // 根据容器尺寸和目标宽高比计算预览实际像素尺寸
        if (maxWidthPx / maxHeightPx > targetAspectRatio) {
            previewHeightPx = maxHeightPx
            previewWidthPx = previewHeightPx * targetAspectRatio
        } else {
            previewWidthPx = maxWidthPx
            previewHeightPx = previewWidthPx / targetAspectRatio
        }

        val previewWidthDp = with(density) { previewWidthPx.toDp() }
        val previewHeightDp = with(density) { previewHeightPx.toDp() }

        // 下面这行日志大约在第89行，关于冗余花括号的警告可以暂时忽略，不影响功能
        Log.d(cameraViewTag, "计算得到的 PreviewView 尺寸: ${previewWidthDp}dp x ${previewHeightDp}dp (基于容器 Dp: ${containerMaxWidthDp} x ${containerMaxHeightDp})")

        LaunchedEffect(
            cameraProviderFuture,
            objectDetectorHelper,
            isPreviewEnabled,
            currentDisplayRotationKey
        ) {
            val targetRotationForUseCase = currentDisplayRotationKey
            Log.i(cameraViewTag, "LaunchedEffect: RE-RUNNING. isPreviewEnabled: $isPreviewEnabled, targetRotationForUseCase: $targetRotationForUseCase")

            val cameraProvider: ProcessCameraProvider
            try {
                cameraProvider = withContext(Dispatchers.IO) {
                    cameraProviderFuture.get()
                }
                Log.d(cameraViewTag, "LaunchedEffect: ProcessCameraProvider 获取成功。")
            } catch (exc: Exception) {
                Log.e(cameraViewTag, "LaunchedEffect: 获取 ProcessCameraProvider 失败。", exc)
                withContext(Dispatchers.Main) {
                    objectDetectorHelper.objectDetectorListener?.onError(
                        "获取相机Provider失败: ${exc.localizedMessage ?: "未知错误"}",
                        ObjectDetectorHelper.OTHER_ERROR
                    )
                }
                return@LaunchedEffect
            }

            try {
                cameraProvider.unbindAll()
                Log.i(cameraViewTag, "LaunchedEffect: 已调用 cameraProvider.unbindAll()。")

                val useCasesToBind = mutableListOf<UseCase>()

                if (isPreviewEnabled) {
                    val preview = Preview.Builder()
                        // 下面这行 setTargetAspectRatio 方法被标记为 deprecated (弃用)，但不影响编译和当前功能
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Line ~126
                        .setTargetRotation(targetRotationForUseCase)
                        .build()
                        .also {
                            Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。目标旋转: $targetRotationForUseCase。")
                            it.setSurfaceProvider(previewView.surfaceProvider)
                            Log.d(cameraViewTag, "LaunchedEffect: SurfaceProvider 已设置到 Preview UseCase。")
                        }
                    useCasesToBind.add(preview)
                } else {
                    Log.i(cameraViewTag, "LaunchedEffect: 预览已禁用，不创建或绑定 Preview UseCase。")
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    // 下面这行 setTargetAspectRatio 方法被标记为 deprecated (弃用)，但不影响编译和当前功能
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Line ~140
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(targetRotationForUseCase)
                    .build()
                    .also {
                        Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。目标旋转: $targetRotationForUseCase。")
                        it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                            if (!objectDetectorHelper.isClosed()) {
                                Log.d(cameraViewTag, "ImageAnalysis - imageProxy rotation: ${imageProxy.imageInfo.rotationDegrees}")
                                objectDetectorHelper.detectLivestreamFrame(imageProxy)
                            } else {
                                imageProxy.close()
                            }
                        }
                        Log.d(cameraViewTag, "LaunchedEffect: Analyzer 已设置到 ImageAnalysis UseCase。")
                    }
                useCasesToBind.add(imageAnalyzer)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                Log.d(cameraViewTag, "LaunchedEffect: CameraSelector 设置为后置摄像头。")

                if (useCasesToBind.isNotEmpty()) {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, cameraSelector, *useCasesToBind.toTypedArray()
                    )
                    Log.i(cameraViewTag, "LaunchedEffect: Camera UseCases (${useCasesToBind.joinToString { it::class.java.simpleName }}) 已成功绑定到生命周期。")
                } else {
                    Log.w(cameraViewTag, "LaunchedEffect: 没有 UseCase 需要绑定。")
                }

            } catch (exc: Exception) {
                Log.e(cameraViewTag, "LaunchedEffect: Use case 绑定失败。", exc)
                withContext(Dispatchers.Main) {
                    objectDetectorHelper.objectDetectorListener?.onError(
                        "相机绑定失败: ${exc.localizedMessage ?: "未知相机错误"}",
                        ObjectDetectorHelper.OTHER_ERROR
                    )
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                Log.i(cameraViewTag, "DisposableEffect (onDispose): CameraView 正在销毁。准备关闭 imageAnalysisExecutor。")
                imageAnalysisExecutor.shutdown()
                try {
                    if (!imageAnalysisExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                        Log.w(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 在等待200ms后未终止，强制关闭。")
                        imageAnalysisExecutor.shutdownNow()
                    }
                    Log.i(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 已关闭。")
                } catch (e: InterruptedException) {
                    Log.w(cameraViewTag, "DisposableEffect (onDispose): 等待 imageAnalysisExecutor 终止时被中断。", e)
                    Thread.currentThread().interrupt()
                }
                Log.i(cameraViewTag, "DisposableEffect (onDispose): 清理完成。")
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