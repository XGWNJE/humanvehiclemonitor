package com.xgwnje.humanvehiclemonitor

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
fun CameraView(
    modifier: Modifier = Modifier,
    objectDetectorHelper: ObjectDetectorHelper // 确保传入的是同一个实例
) {
    val cameraViewTag = "CameraView" // 为此 Composable 定义 TAG
    Log.i(cameraViewTag, "CameraView: Composable 开始组合或重组。ObjectDetectorHelper 实例哈希: ${objectDetectorHelper.hashCode()}") // 中文日志

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

    val previewView = remember {
        Log.d(cameraViewTag, "remember: 创建 PreviewView 实例。") // 中文日志
        PreviewView(context).apply {
            // 关键修改：将 scaleType 设置为 FIT_CENTER
            // 这将确保预览内容保持其宽高比，并在 PreviewView 边界内完整显示，
            // 如有必要，会产生 letterboxing/pillarboxing。
            this.scaleType = PreviewView.ScaleType.FIT_CENTER
            Log.d(cameraViewTag, "PreviewView 实例已创建。ScaleType: ${this.scaleType}") // 中文日志
        }
    }

    LaunchedEffect(cameraProviderFuture, objectDetectorHelper) {
        Log.i(cameraViewTag, "LaunchedEffect: 开始执行相机绑定逻辑。ObjectDetectorHelper 实例哈希: ${objectDetectorHelper.hashCode()}") // 中文日志
        try {
            val cameraProvider = cameraProviderFuture.get()
            Log.d(cameraViewTag, "LaunchedEffect: ProcessCameraProvider 获取成功。") // 中文日志

            val preview = Preview.Builder()
                .build()
                .also {
                    Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。") // 中文日志
                    it.setSurfaceProvider(previewView.surfaceProvider)
                    Log.d(cameraViewTag, "LaunchedEffect: SurfaceProvider 已设置到 Preview UseCase。") // 中文日志
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。") // 中文日志
                    it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                        Log.v(cameraViewTag, "ImageAnalysis.Analyzer: 收到 ImageProxy。线程: ${Thread.currentThread().name}") // 中文日志 (Verbose)
                        if (!objectDetectorHelper.isClosed()) {
                            objectDetectorHelper.detectLivestreamFrame(imageProxy)
                        } else {
                            Log.w(cameraViewTag, "ImageAnalysis.Analyzer: ObjectDetectorHelper 已关闭，跳过帧处理。") // 中文日志
                            imageProxy.close()
                        }
                    }
                    Log.d(cameraViewTag, "LaunchedEffect: Analyzer 已设置到 ImageAnalysis UseCase。") // 中文日志
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            Log.d(cameraViewTag, "LaunchedEffect: CameraSelector 设置为后置摄像头。") // 中文日志

            cameraProvider.unbindAll()
            Log.i(cameraViewTag, "LaunchedEffect: 已调用 cameraProvider.unbindAll()。") // 中文日志

            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview, imageAnalyzer
            )
            Log.i(cameraViewTag, "LaunchedEffect: Camera UseCases (Preview, ImageAnalysis) 已成功绑定到生命周期。") // 中文日志

        } catch (exc: Exception) {
            Log.e(cameraViewTag, "LaunchedEffect: Use case 绑定失败。", exc) // 中文日志
            objectDetectorHelper.objectDetectorListener?.onError("相机绑定失败: ${exc.localizedMessage}") // 中文日志：相机绑定失败
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.i(cameraViewTag, "DisposableEffect (onDispose): CameraView 正在销毁。准备关闭 imageAnalysisExecutor。") // 中文日志
            imageAnalysisExecutor.shutdown()
            try {
                if (!imageAnalysisExecutor.awaitTermination(50, TimeUnit.MILLISECONDS)) {
                    Log.w(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 在等待50ms后未终止，强制关闭。") // 中文日志
                    imageAnalysisExecutor.shutdownNow()
                }
                Log.i(cameraViewTag, "DisposableEffect (onDispose): imageAnalysisExecutor 已关闭。") // 中文日志
            } catch (e: InterruptedException) {
                Log.w(cameraViewTag, "DisposableEffect (onDispose): 等待 imageAnalysisExecutor 终止时被中断。", e) // 中文日志
                Thread.currentThread().interrupt()
            }
        }
    }

    Box(modifier = modifier) {
        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
    }
    Log.d(cameraViewTag, "CameraView: AndroidView (PreviewView) 已添加到组合中。") // 中文日志
}
