// CameraView.kt
package com.xgwnje.humanvehiclemonitor

import android.util.Log
import android.view.Surface
import android.view.ViewGroup
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
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
    modifier: Modifier = Modifier, // 这个 modifier 现在会从 MainActivity 传入，并带有 .size()
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

    Log.i(cameraViewTag, "CameraView: Composable 开始/重组。isPreviewEnabled: $isPreviewEnabled, currentDisplayRotationKey: $currentDisplayRotationKey, Modifier: $modifier")

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

    var lastLoggedProxyRotation by remember { mutableIntStateOf(-1) }

    LaunchedEffect(
        cameraProviderFuture,
        objectDetectorHelper,
        isPreviewEnabled,
        currentDisplayRotationKey
    ) {
        val targetRotationForUseCase = currentDisplayRotationKey
        Log.i(cameraViewTag, "LaunchedEffect: RE-RUNNING. isPreviewEnabled: $isPreviewEnabled, targetRotationForUseCase: $targetRotationForUseCase")
        lastLoggedProxyRotation = -1

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
                    .setTargetRotation(targetRotationForUseCase)
                    .build()
                    .also {
                        Log.d(cameraViewTag, "LaunchedEffect: Preview UseCase 构建完成。目标旋转: $targetRotationForUseCase。")
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                useCasesToBind.add(preview)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setTargetRotation(targetRotationForUseCase)
                .build()
                .also {
                    Log.d(cameraViewTag, "LaunchedEffect: ImageAnalysis UseCase 构建完成。目标旋转: $targetRotationForUseCase。")
                    it.setAnalyzer(imageAnalysisExecutor) { imageProxy ->
                        val currentProxyRotation = imageProxy.imageInfo.rotationDegrees
                        if (currentProxyRotation != lastLoggedProxyRotation) {
                            Log.d(cameraViewTag, "ImageAnalysis - imageProxy rotation CHANGED to: $currentProxyRotation (was: $lastLoggedProxyRotation)")
                            lastLoggedProxyRotation = currentProxyRotation
                        }
                        if (!objectDetectorHelper.isClosed()) {
                            objectDetectorHelper.detectLivestreamFrame(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }
            useCasesToBind.add(imageAnalyzer)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

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
        modifier = modifier, // 直接使用从 MainActivity 传入的、已包含正确尺寸的 Modifier
        update = { /* view -> Log.d(cameraViewTag, "AndroidView update.") */ }
    )
}