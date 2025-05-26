// CameraView.kt
package com.xgwnje.humanvehiclemonitor

import android.util.Log
import androidx.camera.core.AspectRatio // 新增导入
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
    isPreviewEnabled: Boolean
) {
    val cameraViewTag = "CameraView(TFLite)"
    Log.i(cameraViewTag, "CameraView: Composable 开始组合或重组。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}") // 中文日志: Composable 开始组合或重组。

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember {
        Log.d(cameraViewTag, "remember: 获取 ProcessCameraProvider 实例。") // 中文日志: 获取 ProcessCameraProvider 实例。
        ProcessCameraProvider.getInstance(context)
    }

    val imageAnalysisExecutor: ExecutorService = remember {
        Log.d(cameraViewTag, "remember: 创建 ImageAnalysis 的单线程执行器。") // 中文日志: 创建 ImageAnalysis 的单线程执行器。
        Executors.newSingleThreadExecutor()
    }

    val previewView = remember {
        Log.d(cameraViewTag, "remember: 创建 PreviewView 实例。") // 中文日志: 创建 PreviewView 实例。
        PreviewView(context).apply {
            this.scaleType = PreviewView.ScaleType.FIT_CENTER
            Log.d(cameraViewTag, "PreviewView 实例已创建。ScaleType: ${this.scaleType}") // 中文日志: PreviewView 实例已创建。
        }
    }

    LaunchedEffect(cameraProviderFuture, objectDetectorHelper, isPreviewEnabled) {
        Log.i(cameraViewTag, "LaunchedEffect: 开始执行相机绑定逻辑。isPreviewEnabled: $isPreviewEnabled, Helper hash: ${objectDetectorHelper.hashCode()}") // 中文日志: 开始执行相机绑定逻辑。

        val cameraProvider: ProcessCameraProvider
        try {
            cameraProvider = withContext(Dispatchers.IO) {
                cameraProviderFuture.get()
            }
            Log.d(cameraViewTag, "LaunchedEffect: ProcessCameraProvider 获取成功。") // 中文日志: ProcessCameraProvider 获取成功。
        } catch (exc: Exception) {
            Log.e(cameraViewTag, "LaunchedEffect: 获取 ProcessCameraProvider 失败。", exc) // 中文日志: 获取 ProcessCameraProvider 失败。
            withContext(Dispatchers.Main) {
                objectDetectorHelper.objectDetectorListener?.onError(
                    "获取相机Provider失败: ${exc.localizedMessage ?: "未知错误"}", // 中文日志: 获取相机Provider失败: ... 未知错误
                    ObjectDetectorHelper.OTHER_ERROR
                )
            }
            return@LaunchedEffect
        }

        try {
            cameraProvider.unbindAll()
            Log.i(cameraViewTag, "LaunchedEffect: 已调用 cameraProvider.unbindAll()。") // 中文日志: 已调用 cameraProvider.unbindAll()。

            val useCasesToBind = mutableListOf<UseCase>()

            if (isPreviewEnabled) {
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3) // <--- 修改点: 设置目标宽高比为4:3
                    .build()
                    .also {
                        Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。目标宽高比设置为 4:3。") // 中文日志: Preview UseCase 构建完成。目标宽高比设置为 4:3。
                        it.setSurfaceProvider(previewView.surfaceProvider)
                        Log.d(cameraViewTag, "LaunchedEffect: SurfaceProvider 已设置到 Preview UseCase。") // 中文日志: SurfaceProvider 已设置到 Preview UseCase。
                    }
                useCasesToBind.add(preview)
            } else {
                Log.i(cameraViewTag, "LaunchedEffect: 预览已禁用，不创建或绑定 Preview UseCase。") // 中文日志: 预览已禁用，不创建或绑定 Preview UseCase。
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3) // <--- 修改点: 设置目标宽高比为4:3
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。目标宽高比设置为 4:3。") // 中文日志: ImageAnalysis UseCase 构建完成。目标宽高比设置为 4:3。
                    it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                        if (!objectDetectorHelper.isClosed()) {
                            objectDetectorHelper.detectLivestreamFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                    Log.d(cameraViewTag, "LaunchedEffect: Analyzer 已设置到 ImageAnalysis UseCase。") // 中文日志: Analyzer 已设置到 ImageAnalysis UseCase。
                }
            useCasesToBind.add(imageAnalyzer)


            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            Log.d(cameraViewTag, "LaunchedEffect: CameraSelector 设置为后置摄像头。") // 中文日志: CameraSelector 设置为后置摄像头。

            if (useCasesToBind.isNotEmpty()) {
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, *useCasesToBind.toTypedArray()
                )
                Log.i(cameraViewTag, "LaunchedEffect: Camera UseCases (${useCasesToBind.joinToString { it::class.java.simpleName }}) 已成功绑定到生命周期。") // 中文日志: Camera UseCases (...) 已成功绑定到生命周期。
            } else {
                Log.w(cameraViewTag, "LaunchedEffect: 没有 UseCase 需要绑定。") // 中文日志: 没有 UseCase 需要绑定。
            }

        } catch (exc: Exception) {
            Log.e(cameraViewTag, "LaunchedEffect: Use case 绑定失败。", exc) // 中文日志: Use case 绑定失败。
            withContext(Dispatchers.Main) {
                objectDetectorHelper.objectDetectorListener?.onError(
                    "相机绑定失败: ${exc.localizedMessage ?: "未知相机错误"}", // 中文日志: 相机绑定失败: ... 未知相机错误
                    ObjectDetectorHelper.OTHER_ERROR
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.i(cameraViewTag, "DisposableEffect (onDispose): CameraView 正在销毁。准备关闭 imageAnalysisExecutor。") // 中文日志: CameraView 正在销毁。准备关闭 imageAnalysisExecutor。
            imageAnalysisExecutor.shutdown()
            try {
                if (!imageAnalysisExecutor.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                    Log.w(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 在等待50ms后未终止，强制关闭。") // 中文日志: imageAnalysisExecutor 在等待50ms后未终止，强制关闭。
                    imageAnalysisExecutor.shutdownNow()
                }
                Log.i(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 已关闭。") // 中文日志: imageAnalysisExecutor 已关闭。
            } catch (e: InterruptedException) {
                Log.w(cameraViewTag, "DisposableEffect (onDispose): 等待 imageAnalysisExecutor 终止时被中断。", e) // 中文日志: 等待 imageAnalysisExecutor 终止时被中断。
                Thread.currentThread().interrupt()
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            Log.d(cameraViewTag, "AndroidView update. isPreviewEnabled: $isPreviewEnabled. PreviewView visibility: ${view.visibility}") // 中文日志: AndroidView update.
        }
    )
}
