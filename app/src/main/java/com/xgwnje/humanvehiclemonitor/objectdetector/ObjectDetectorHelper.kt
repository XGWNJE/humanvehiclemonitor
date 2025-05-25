package com.xgwnje.humanvehiclemonitor.objectdetector // Or com.example.humanvehiclemonitor if that's your build's package name

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.camera.core.ImageProxy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ObjectDetectorHelper(
    var threshold: Float = DEFAULT_THRESHOLD,
    var maxResults: Int = DEFAULT_MAX_RESULTS,
    var currentDelegate: Int = DELEGATE_CPU,
    var modelName: String = "efficientdet_lite0.tflite",
    var runningMode: RunningMode = RunningMode.LIVE_STREAM,
    val context: Context,
    var objectDetectorListener: ObjectDetectorListener? = null
) : DefaultLifecycleObserver {

    private var objectDetector: ObjectDetector? = null
    private var backgroundExecutor: ScheduledThreadPoolExecutor? = null
    private val lock = ReentrantLock()
    @Volatile
    private var isShuttingDown: Boolean = false

    init {
        Log.i(TAG, "ObjectDetectorHelper: 实例已创建。")
    }

    fun setupObjectDetector() {
        lock.withLock {
            Log.i(TAG, "setupObjectDetector: 开始配置。当前状态: objectDetector is ${if (objectDetector == null) "null" else "not null"}, executor is ${if (backgroundExecutor == null || backgroundExecutor!!.isShutdown) "null or shutdown" else "active"}, isShuttingDown: $isShuttingDown")

            isShuttingDown = false

            if (objectDetector != null) {
                Log.i(TAG, "setupObjectDetector: 检测到已存在的 objectDetector 实例，将进行清理 (不关闭执行器)。")
                clearObjectDetectorInternal(shutDownExecutor = false, calledFromSetup = true)
            }

            if (backgroundExecutor == null || backgroundExecutor!!.isShutdown) {
                Log.i(TAG, "setupObjectDetector: 后台执行器为 null 或已关闭，重新创建。")
                backgroundExecutor = ScheduledThreadPoolExecutor(1)
                Log.d(TAG, "setupObjectDetector: 新的后台执行器已初始化。")
            }

            val baseOptionsBuilder = BaseOptions.builder()
            when (currentDelegate) {
                DELEGATE_CPU -> baseOptionsBuilder.setDelegate(Delegate.CPU)
                DELEGATE_GPU -> baseOptionsBuilder.setDelegate(Delegate.GPU)
            }
            baseOptionsBuilder.setModelAssetPath(modelName)

            Log.i(TAG, "setupObjectDetector: 准备创建 ObjectDetector 实例。配置参数: threshold=$threshold, maxResults=$maxResults, delegate=$currentDelegate, model=$modelName, runningMode=$runningMode")

            try {
                val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setScoreThreshold(threshold)
                    .setRunningMode(runningMode)
                    .setMaxResults(maxResults)

                if (runningMode == RunningMode.LIVE_STREAM) {
                    optionsBuilder
                        .setResultListener(this::returnLivestreamResult)
                        .setErrorListener(this::returnLivestreamError)
                    Log.d(TAG, "setupObjectDetector: 已为 LIVE_STREAM 模式设置结果和错误监听器。")
                }
                objectDetector = ObjectDetector.createFromOptions(context, optionsBuilder.build())
                Log.i(TAG, "setupObjectDetector: ObjectDetector 实例创建成功。")
                isShuttingDown = false
            } catch (e: Exception) {
                val errorMessage = "Object detector 初始化失败。错误: ${e.message}"
                objectDetectorListener?.onError(errorMessage, if (e is RuntimeException && currentDelegate == DELEGATE_GPU) GPU_ERROR else OTHER_ERROR)
                Log.e(TAG, "setupObjectDetector: ObjectDetector 实例创建失败。", e)
                isShuttingDown = true
            }
        }
        Log.i(TAG, "setupObjectDetector: 配置结束。isShuttingDown: $isShuttingDown")
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.i(TAG, "onResume: 生命周期回调，尝试设置 ObjectDetector。isShuttingDown 当前值: $isShuttingDown")
        setupObjectDetector()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.i(TAG, "onPause: 生命周期回调，尝试清理 ObjectDetector。isShuttingDown 当前值: $isShuttingDown")
        clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
    }

    private fun clearObjectDetectorInternal(shutDownExecutor: Boolean, calledFromSetup: Boolean) {
        lock.withLock {
            Log.i(TAG, "clearObjectDetectorInternal: 开始清理。是否关闭执行器: $shutDownExecutor, 当前 objectDetector 状态: ${if (objectDetector == null) "null" else "not null"}, isShuttingDown: $isShuttingDown, calledFromSetup: $calledFromSetup")

            if (!calledFromSetup && shutDownExecutor) {
                isShuttingDown = true
                Log.i(TAG, "clearObjectDetectorInternal: 设置 isShuttingDown = true。")
            }

            try {
                objectDetector?.close()
            } catch (e: Exception) {
                Log.e(TAG, "clearObjectDetectorInternal: 关闭 objectDetector 时发生错误。", e)
            }
            objectDetector = null
            Log.i(TAG, "clearObjectDetectorInternal: objectDetector 实例已调用 close() 并设为 null。")

            if (shutDownExecutor && backgroundExecutor != null) {
                if (!backgroundExecutor!!.isShutdown) {
                    Log.i(TAG, "clearObjectDetectorInternal: 准备关闭后台执行器 (shutdown)。")
                    backgroundExecutor?.shutdown()
                    try {
                        if (backgroundExecutor?.awaitTermination(500, TimeUnit.MILLISECONDS) == false) { // 增加等待时间
                            Log.w(TAG, "clearObjectDetectorInternal: 后台执行器在等待500ms后未终止，强制关闭 (shutdownNow)。")
                            backgroundExecutor?.shutdownNow()
                            if (backgroundExecutor?.awaitTermination(250, TimeUnit.MILLISECONDS) == false) { // 再次等待
                                Log.e(TAG, "clearObjectDetectorInternal: 后台执行器在 shutdownNow 后仍未能终止。")
                            } else {
                                Log.i(TAG, "clearObjectDetectorInternal: 后台执行器在 shutdownNow 后成功终止。")
                            }
                        } else {
                            Log.i(TAG, "clearObjectDetectorInternal: 后台执行器已优雅关闭。")
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "clearObjectDetectorInternal: 等待执行器终止时被中断，强制关闭。", e)
                        backgroundExecutor?.shutdownNow()
                        Thread.currentThread().interrupt()
                    }
                } else {
                    Log.i(TAG, "clearObjectDetectorInternal: 后台执行器已被要求关闭或为null，无需操作。")
                }
                backgroundExecutor = null
            }
        }
        Log.i(TAG, "clearObjectDetectorInternal: 清理结束。isShuttingDown: $isShuttingDown")
    }

    @WorkerThread
    fun detectLivestreamFrame(imageProxy: ImageProxy) {
        if (isShuttingDown) {
            Log.w(TAG, "detectLivestreamFrame: ObjectDetectorHelper 正在关闭 (isShuttingDown is true)，跳过帧。")
            imageProxy.close()
            return
        }

        val currentExecutor = lock.withLock { backgroundExecutor } // 读取时加锁

        if (runningMode != RunningMode.LIVE_STREAM) {
            Log.w(TAG, "detectLivestreamFrame: 当前非 LIVE_STREAM 模式，跳过处理。")
            imageProxy.close()
            return
        }

        // 提前检查 objectDetector 和 executor 状态，减少不必要的图像处理
        lock.withLock {
            if (objectDetector == null) {
                Log.w(TAG, "detectLivestreamFrame: ObjectDetector 未初始化 (lock check)，跳过帧。")
                imageProxy.close()
                return
            }
            if (currentExecutor == null || currentExecutor.isShutdown) {
                Log.w(TAG, "detectLivestreamFrame: 后台执行器未准备好或已关闭 (lock check)，跳过帧。")
                imageProxy.close()
                return
            }
        }


        val frameTime = SystemClock.uptimeMillis()
        // 关键: 在 ImageProxy 关闭前提取旋转角度
        val imageRotationDegrees = imageProxy.imageInfo.rotationDegrees
        Log.d(TAG, "detectLivestreamFrame: 帧时间戳: $frameTime, 旋转角度: $imageRotationDegrees. 准备提交到执行器。")

        currentExecutor?.execute { // currentExecutor 可能为 null，做安全调用
            var preppedImage: MPImage? = null
            var successfullySubmitted = false
            try {
                // 图像处理
                val bitmapBuffer: Bitmap?
                imageProxy.use { proxy -> // imageProxy 在此块结束时自动关闭
                    bitmapBuffer = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
                    proxy.planes[0].buffer.rewind()
                    bitmapBuffer!!.copyPixelsFromBuffer(proxy.planes[0].buffer)
                }

                if (bitmapBuffer == null) {
                    Log.w(TAG, "detectLivestreamFrame (executor): BitmapBuffer 为 null。")
                    return@execute
                }

                val rotatedBitmap = if (imageRotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(imageRotationDegrees.toFloat()) }
                    Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
                } else {
                    bitmapBuffer
                }

                if (rotatedBitmap == null) {
                    Log.w(TAG, "detectLivestreamFrame (executor): RotatedBitmap 为 null。")
                    return@execute
                }
                preppedImage = BitmapImageBuilder(rotatedBitmap).build()

                // 在调用 detectAsync 之前进行最终检查（在锁内）
                lock.withLock {
                    if (isShuttingDown || objectDetector == null) {
                        Log.w(TAG, "detectLivestreamFrame (executor): 关键检查失败。ShuttingDown=$isShuttingDown, DetectorNull=${objectDetector == null}")
                        // successfullySubmitted 保持 false
                    } else {
                        Log.d(TAG, "detectLivestreamFrame (executor): 调用 detectAsync。MPImage 尺寸: ${preppedImage!!.width}x${preppedImage!!.height}, 时间戳: $frameTime")
                        objectDetector!!.detectAsync(preppedImage!!, frameTime)
                        successfullySubmitted = true // 标记已成功提交给 MediaPipe
                        Log.d(TAG, "detectLivestreamFrame (executor): detectAsync 调用完毕。")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "detectLivestreamFrame (executor): 处理帧或调用 detectAsync 时发生异常。", e)
                objectDetectorListener?.onError("处理帧时出错: ${e.message}")
                // successfullySubmitted 保持 false
            } finally {
                if (!successfullySubmitted && preppedImage != null) {
                    Log.d(TAG, "detectLivestreamFrame (executor): 检测未成功提交或跳过，关闭 MPImage。")
                    preppedImage.close() // 仅当未成功提交给 MediaPipe 时关闭
                }
                Log.v(TAG, "detectLivestreamFrame (executor): 帧处理任务结束。")
            }
        }
    }

    private fun returnLivestreamResult(result: ObjectDetectionResult, input: MPImage) {
        if (isShuttingDown) {
            Log.w(TAG, "returnLivestreamResult: ObjectDetectorHelper 正在关闭，忽略结果。")
            return
        }
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()
        Log.d(TAG, "returnLivestreamResult: 收到检测结果。推理时间: $inferenceTime ms, 输入图像尺寸: ${input.width}x${input.height}, 结果数量: ${result.detections().size}。线程: ${Thread.currentThread().name}")
        objectDetectorListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width
            )
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        if (isShuttingDown) {
            Log.w(TAG, "returnLivestreamError: ObjectDetectorHelper 正在关闭，忽略错误。 ${error.message}")
            return
        }
        Log.e(TAG, "returnLivestreamError: 收到 MediaPipe 错误。线程: ${Thread.currentThread().name}", error)
        objectDetectorListener?.onError(
            error.message ?: "发生未知的 MediaPipe 错误。"
        )
    }

    fun clearObjectDetector() {
        lock.withLock { //确保公共方法也使用锁
            Log.i(TAG, "clearObjectDetector: 公共清理方法被调用。isShuttingDown 当前值: $isShuttingDown")
            clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
        }
    }

    fun isClosed(): Boolean {
        val closedState = lock.withLock { objectDetector == null || isShuttingDown }
        Log.v(TAG, "isClosed: 当前状态: ${if (closedState) "已关闭" else "活动"} (detector null: ${objectDetector == null}, shuttingDown: $isShuttingDown)")
        return closedState
    }

    data class ResultBundle(
        val results: List<ObjectDetectionResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    companion object {
        const val TAG = "ObjectDetectorHelper"
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_THRESHOLD = 0.4f
        const val DEFAULT_MAX_RESULTS = 5
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }
}