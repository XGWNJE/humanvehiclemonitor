package com.xgwnje.humanvehiclemonitor

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
    isPreviewEnabled: Boolean // 新增参数控制预览是否启用
) {
    val cameraViewTag = "CameraView(TFLite)"
    Log.i(cameraViewTag, "CameraView: Composable 开始组合或重组。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}")

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

    val previewView = remember {
        Log.d(cameraViewTag, "remember: 创建 PreviewView 实例。")
        PreviewView(context).apply {
            this.scaleType = PreviewView.ScaleType.FIT_CENTER
            Log.d(cameraViewTag, "PreviewView 实例已创建。ScaleType: ${this.scaleType}")
        }
    }

    // 将 isPreviewEnabled 添加到 LaunchedEffect 的 key 中，以便在它变化时重新绑定相机
    LaunchedEffect(cameraProviderFuture, objectDetectorHelper, isPreviewEnabled) {
        Log.i(cameraViewTag, "LaunchedEffect: 开始执行相机绑定逻辑。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}")

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
            cameraProvider.unbindAll() // 先解绑所有用例
            Log.i(cameraViewTag, "LaunchedEffect: 已调用 cameraProvider.unbindAll()。")

            val useCasesToBind = mutableListOf<UseCase>()

            // 根据 isPreviewEnabled 决定是否创建和绑定 Preview UseCase
            if (isPreviewEnabled) {
                val preview = Preview.Builder()
                    .build()
                    .also {
                        Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。")
                        it.setSurfaceProvider(previewView.surfaceProvider)
                        Log.d(cameraViewTag, "LaunchedEffect: SurfaceProvider 已设置到 Preview UseCase。")
                    }
                useCasesToBind.add(preview)
            } else {
                Log.i(cameraViewTag, "LaunchedEffect: 预览已禁用，不创建或绑定 Preview UseCase。")
                // 可选：如果 PreviewView 仍然连接着旧的 SurfaceProvider，可以尝试清除它
                // previewView.surfaceProvider = null // 但这通常由 CameraX 在解绑时处理
            }

            // ImageAnalysis UseCase 始终创建（假设即使预览关闭也需要后台检测）
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。")
                    it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                        if (!objectDetectorHelper.isClosed()) {
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
                Log.w(cameraViewTag, "LaunchedEffect: 没有 UseCase 需要绑定（预览已禁用且未配置其他必须的UseCase）。")
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
                if (!imageAnalysisExecutor.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                    Log.w(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 在等待50ms后未终止，强制关闭。")
                    imageAnalysisExecutor.shutdownNow()
                }
                Log.i(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 已关闭。")
            } catch (e: InterruptedException) {
                Log.w(cameraViewTag, "DisposableEffect (onDispose): 等待 imageAnalysisExecutor 终止时被中断。", e)
                Thread.currentThread().interrupt()
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            // 当 isPreviewEnabled 变为 false 时，PreviewView 可能仍然显示最后一帧。
            // CameraX 在解绑 Preview UseCase 后，通常会停止向其 SurfaceProvider 提供新的帧。
            // 如果需要明确清空 PreviewView，可能需要更复杂的处理，例如用占位符视图替换。
            // 目前，我们依赖 CameraX 的行为。
            Log.d(cameraViewTag, "AndroidView update. isPreviewEnabled: $isPreviewEnabled. PreviewView visibility: ${view.visibility}")
        }
    )
}
