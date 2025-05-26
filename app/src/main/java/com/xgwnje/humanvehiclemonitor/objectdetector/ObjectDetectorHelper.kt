// ObjectDetectorHelper.kt
package com.xgwnje.humanvehiclemonitor.objectdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect // Import Rect for cropRect logging
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.camera.core.ImageProxy
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ObjectDetectorHelper(
    var threshold: Float = DEFAULT_THRESHOLD,
    var maxResults: Int = DEFAULT_MAX_RESULTS,
    var currentDelegate: Int = DELEGATE_CPU,
    var modelName: String = "2.tflite",
    var detectionIntervalMillis: Long = DEFAULT_DETECTION_INTERVAL_MS,
    val context: Context,
    var objectDetectorListener: ObjectDetectorListener? = null
) : DefaultLifecycleObserver {

    private var objectDetector: ObjectDetector? = null
    private var executorService: ExecutorService? = null
    private val lock = ReentrantLock()
    @Volatile
    private var isShuttingDown: Boolean = false
    private var lastDetectionAttemptTimestamp: Long = 0L

    init {
        Log.i(TAG, "ObjectDetectorHelper (TFLite): 实例已创建。检测间隔: $detectionIntervalMillis ms") // 中文日志: 实例已创建。检测间隔: ... ms
    }

    fun setupObjectDetector() {
        Log.d(TAG, "setupObjectDetector (TFLite): Task STARTED on thread ${Thread.currentThread().name}") // 中文日志: Task STARTED on thread ...
        lock.withLock {
            Log.i(TAG, "setupObjectDetector (TFLite): 开始配置 (lock acquired)。当前状态: objectDetector is ${if (objectDetector == null) "null" else "not null"}, executor is ${if (executorService == null || executorService!!.isShutdown) "null or shutdown" else "active"}, isShuttingDown: $isShuttingDown") // 中文日志: 开始配置 (lock acquired)。当前状态: ...
            isShuttingDown = false

            if (objectDetector != null) {
                Log.i(TAG, "setupObjectDetector (TFLite): 检测到已存在的 objectDetector 实例，将进行清理。") // 中文日志: 检测到已存在的 objectDetector 实例，将进行清理。
                try {
                    objectDetector?.close()
                    Log.i(TAG, "setupObjectDetector (TFLite): 旧的 objectDetector 实例已关闭。") // 中文日志: 旧的 objectDetector 实例已关闭。
                } catch (e: Exception) {
                    Log.e(TAG, "setupObjectDetector (TFLite): 关闭旧 objectDetector 时发生错误。", e) // 中文日志: 关闭旧 objectDetector 时发生错误。
                }
                objectDetector = null
            }

            val baseOptionsBuilder = BaseOptions.builder()
            var delegateName = "CPU"
            when (currentDelegate) {
                DELEGATE_CPU -> {
                    baseOptionsBuilder.setNumThreads(4)
                    delegateName = "CPU (4 threads)"
                }
                DELEGATE_GPU -> {
                    try {
                        baseOptionsBuilder.useGpu()
                        delegateName = "GPU"
                        Log.i(TAG, "setupObjectDetector (TFLite): 尝试使用 GPU 代理。") // 中文日志: 尝试使用 GPU 代理。
                    } catch (e: Exception) {
                        Log.e(TAG, "setupObjectDetector (TFLite): 设置 GPU 代理失败，回退到 CPU。", e) // 中文日志: 设置 GPU 代理失败，回退到 CPU。
                        currentDelegate = DELEGATE_CPU
                        baseOptionsBuilder.setNumThreads(4)
                        delegateName = "CPU (GPU fallback, 4 threads)"
                        objectDetectorListener?.onError("GPU代理设置失败: ${e.message}", GPU_ERROR_INIT_FAIL) // 中文日志: GPU代理设置失败: ...
                    }
                }
            }

            Log.i(TAG, "setupObjectDetector (TFLite): 准备创建 ObjectDetector 实例。配置参数: threshold=$threshold, maxResults=$maxResults, delegate=$delegateName, model=$modelName, interval=$detectionIntervalMillis ms") // 中文日志: 准备创建 ObjectDetector 实例。配置参数: ...

            try {
                val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setScoreThreshold(threshold)
                    .setMaxResults(maxResults)

                val objectDetectorOptions = optionsBuilder.build()
                objectDetector = ObjectDetector.createFromFileAndOptions(context, modelName, objectDetectorOptions)
                Log.i(TAG, "setupObjectDetector (TFLite): ObjectDetector 实例创建成功!") // 中文日志: ObjectDetector 实例创建成功!
                isShuttingDown = false
                lastDetectionAttemptTimestamp = 0L
            } catch (e: Exception) {
                val errorMessage = "TFLite Object detector 初始化失败。模型: '$modelName'. 代理: $delegateName. 错误: ${e.message}" // 中文日志: TFLite Object detector 初始化失败。模型: ... 代理: ... 错误: ...
                objectDetectorListener?.onError(errorMessage, OTHER_ERROR)
                Log.e(TAG, "setupObjectDetector (TFLite): ObjectDetector 实例创建失败。模型: '$modelName'", e) // 中文日志: ObjectDetector 实例创建失败。模型: ...
                isShuttingDown = true
            }
        }
        Log.d(TAG, "setupObjectDetector (TFLite): Task FINISHED on thread ${Thread.currentThread().name}. Detector is ${if(objectDetector != null) "NOT NULL" else "NULL"}") // 中文日志: Task FINISHED on thread ... Detector is ...
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.i(TAG, "onResume (TFLite): 生命周期回调。isShuttingDown 当前值: $isShuttingDown. Detector: ${if(objectDetector == null) "null" else "not null"}") // 中文日志: 生命周期回调。isShuttingDown 当前值: ... Detector: ...
        lock.withLock {
            if (executorService == null || executorService!!.isShutdown) {
                Log.i(TAG, "onResume (TFLite): executorService 为 null 或已关闭，创建新的。") // 中文日志: executorService 为 null 或已关闭，创建新的。
                executorService = Executors.newSingleThreadExecutor()
            }
        }

        if (objectDetector == null && !isShuttingDown) {
            Log.i(TAG, "onResume (TFLite): ObjectDetector 为 null 且未关闭，提交 setupObjectDetector 到执行器。") // 中文日志: ObjectDetector 为 null 且未关闭，提交 setupObjectDetector 到执行器。
            executorService?.submit {
                setupObjectDetector()
            }
        } else {
            Log.i(TAG, "onResume (TFLite): 跳过 setupObjectDetector 提交。Detector: ${if(objectDetector == null) "null" else "not null"}, isShuttingDown: $isShuttingDown") // 中文日志: 跳过 setupObjectDetector 提交。Detector: ..., isShuttingDown: ...
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.i(TAG, "onPause (TFLite): 生命周期回调。isShuttingDown 当前值: $isShuttingDown") // 中文日志: 生命周期回调。isShuttingDown 当前值: ...
        val currentExecutor = executorService
        if (currentExecutor != null && !currentExecutor.isShutdown) {
            Log.i(TAG, "onPause (TFLite): 提交 clearObjectDetectorInternal 到执行器。") // 中文日志: 提交 clearObjectDetectorInternal 到执行器。
            currentExecutor.submit {
                clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
            }
        } else {
            Log.w(TAG, "onPause (TFLite): 执行器为 null 或已关闭，尝试同步清理。") // 中文日志: 执行器为 null 或已关闭，尝试同步清理。
            clearObjectDetectorInternal(shutDownExecutor = false, calledFromSetup = false)
        }
    }

    private fun clearObjectDetectorInternal(shutDownExecutor: Boolean, calledFromSetup: Boolean) {
        Log.d(TAG, "clearObjectDetectorInternal (TFLite): Task STARTED on thread ${Thread.currentThread().name}. shutDownExecutor: $shutDownExecutor, calledFromSetup: $calledFromSetup") // 中文日志: Task STARTED on thread ...
        lock.withLock {
            Log.i(TAG, "clearObjectDetectorInternal (TFLite): 开始清理 (lock acquired)。当前 objectDetector 状态: ${if (objectDetector == null) "null" else "not null"}, isShuttingDown: $isShuttingDown") // 中文日志: 开始清理 (lock acquired)。当前 objectDetector 状态: ..., isShuttingDown: ...

            if (!calledFromSetup) {
                isShuttingDown = true
                Log.i(TAG, "clearObjectDetectorInternal (TFLite): isShuttingDown 设置为 true。") // 中文日志: isShuttingDown 设置为 true。
            }

            try {
                objectDetector?.close()
                Log.i(TAG, "clearObjectDetectorInternal (TFLite): objectDetector 实例已调用 close()。") // 中文日志: objectDetector 实例已调用 close()。
            } catch (e: Exception) {
                Log.e(TAG, "clearObjectDetectorInternal (TFLite): 关闭 objectDetector 时发生错误。", e) // 中文日志: 关闭 objectDetector 时发生错误。
            }
            objectDetector = null
            Log.i(TAG, "clearObjectDetectorInternal (TFLite): objectDetector 实例已设为 null。") // 中文日志: objectDetector 实例已设为 null。

            if (shutDownExecutor && executorService != null) {
                if (!executorService!!.isShutdown) {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 准备关闭后台执行器 (shutdown)。") // 中文日志: 准备关闭后台执行器 (shutdown)。
                    executorService?.shutdown()
                    try {
                        if (executorService?.awaitTermination(200, TimeUnit.MILLISECONDS) == false) {
                            Log.w(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在等待200ms后未终止，强制关闭 (shutdownNow)。") // 中文日志: 后台执行器在等待200ms后未终止，强制关闭 (shutdownNow)。
                            executorService?.shutdownNow()
                            if (executorService?.awaitTermination(100, TimeUnit.MILLISECONDS) == false) {
                                Log.e(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在 shutdownNow 后仍未能终止。") // 中文日志: 后台执行器在 shutdownNow 后仍未能终止。
                            } else {
                                Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在 shutdownNow 后成功终止。") // 中文日志: 后台执行器在 shutdownNow 后成功终止。
                            }
                        } else {
                            Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器已优雅关闭。") // 中文日志: 后台执行器已优雅关闭。
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "clearObjectDetectorInternal (TFLite): 等待执行器终止时被中断，强制关闭。", e) // 中文日志: 等待执行器终止时被中断，强制关闭。
                        executorService?.shutdownNow()
                        Thread.currentThread().interrupt()
                    }
                } else {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器已被要求关闭或为null，无需操作。") // 中文日志: 后台执行器已被要求关闭或为null，无需操作。
                }
                if (calledFromSetup) {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 从 setup 调用，不将 executorService 设为 null。") // 中文日志: 从 setup 调用，不将 executorService 设为 null。
                } else {
                    executorService = null
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): executorService 已设为 null。") // 中文日志: executorService 已设为 null。
                }
            } else if (shutDownExecutor) {
                Log.w(TAG, "clearObjectDetectorInternal (TFLite): 请求关闭执行器，但 executorService 为 null。") // 中文日志: 请求关闭执行器，但 executorService 为 null。
            }
        }
        Log.d(TAG, "clearObjectDetectorInternal (TFLite): Task FINISHED on thread ${Thread.currentThread().name}") // 中文日志: Task FINISHED on thread ...
    }

    @WorkerThread
    fun detectLivestreamFrame(imageProxy: ImageProxy) {
        val currentDetector = lock.withLock { objectDetector }
        val currentExecutor = lock.withLock { executorService }

        if (isShuttingDown || currentDetector == null) {
            if(isShuttingDown) Log.v(TAG, "detectLivestreamFrame (TFLite): ObjectDetectorHelper 正在关闭，跳过帧。") // 中文日志: ObjectDetectorHelper 正在关闭，跳过帧。
            if(currentDetector == null && !isShuttingDown) Log.w(TAG, "detectLivestreamFrame (TFLite): ObjectDetector 未初始化，跳过帧。") // 中文日志: ObjectDetector 未初始化，跳过帧。
            imageProxy.close()
            return
        }

        if (currentExecutor == null || currentExecutor.isShutdown) {
            Log.w(TAG, "detectLivestreamFrame (TFLite): 后台执行器未准备好或已关闭，跳过帧。") // 中文日志: 后台执行器未准备好或已关闭，跳过帧。
            imageProxy.close()
            return
        }

        val currentTimeMs = SystemClock.uptimeMillis()
        if (currentTimeMs - lastDetectionAttemptTimestamp < detectionIntervalMillis) {
            imageProxy.close()
            return
        }

        currentExecutor.execute {
            lastDetectionAttemptTimestamp = SystemClock.uptimeMillis()
            var bitmap: Bitmap? = null
            var tempBitmapForRecycle: Bitmap? = null // 用于跟踪最初从 buffer 创建的 bitmap
            var firstCroppedBitmapForRecycle: Bitmap? = null // 用于跟踪第一次裁剪的 bitmap

            try {
                // 新增日志：记录 ImageProxy 的原始信息
                Log.d(TAG, "detectLivestreamFrame (TFLite Executor): ImageProxy received: width=${imageProxy.width}, height=${imageProxy.height}, cropRect=${imageProxy.cropRect}, rotationDegrees=${imageProxy.imageInfo.rotationDegrees}, format=${imageProxy.format}")

                val planes = imageProxy.planes
                val buffer = planes[0].buffer.apply { rewind() }
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * imageProxy.width

                tempBitmapForRecycle = Bitmap.createBitmap(
                    imageProxy.width + rowPadding / pixelStride,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmapForRecycle!!.copyPixelsFromBuffer(buffer)

                bitmap = if (rowPadding > 0) {
                    firstCroppedBitmapForRecycle = Bitmap.createBitmap(tempBitmapForRecycle!!, 0, 0, imageProxy.width, imageProxy.height)
                    firstCroppedBitmapForRecycle!!
                } else {
                    tempBitmapForRecycle!!
                }

                val crop = imageProxy.cropRect
                // 检查 cropRect 是否定义了一个比当前 bitmap 更小的有效区域
                // imageProxy.width/height 应该是 CameraX 根据 targetAspectRatio 调整后的尺寸
                // cropRect 定义了在这个尺寸（或其 buffer）内的有效像素区域
                if (crop.left != 0 || crop.top != 0 || crop.width() != bitmap.width || crop.height() != bitmap.height) {
                    Log.w(TAG, "detectLivestreamFrame (TFLite Executor): ImageProxy cropRect ${crop} differs from bitmap dimensions (${bitmap.width}x${bitmap.height}). Applying explicit crop from original buffer or initial crop.")
                    // 关键：如果 cropRect 指示需要从原始的、可能更大的缓冲图像（tempBitmapForRecycle）中裁剪，
                    // 或者从已经按 imageProxy.width/height 裁剪过的图像（firstCroppedBitmapForRecycle 或 tempBitmapForRecycle）中进一步裁剪。
                    // 我们需要确保裁剪的源是正确的，并且坐标是相对于那个源的。
                    // cropRect 的坐标是相对于 ImageProxy 的缓冲区的 (imageProxy.width + rowPadding / pixelStride, imageProxy.height)。
                    // 如果我们已经基于 imageProxy.width/height 做了第一次裁剪，那么 cropRect 的 (left, top) 可能需要调整。
                    // 然而，更简单和安全的方式是，如果 cropRect 表明需要裁剪，我们就从最原始的 tempBitmapForRecycle 开始，
                    // 使用 cropRect 的绝对坐标。

                    // 重新从 tempBitmapForRecycle (完整 buffer 图像) 开始，使用 cropRect
                    val finalCroppedBitmap = Bitmap.createBitmap(
                        tempBitmapForRecycle!!, // 源是包含padding的完整buffer图像
                        crop.left,    // cropRect的坐标是相对于这个完整buffer的
                        crop.top,
                        crop.width(), // cropRect的宽高是最终有效图像的宽高
                        crop.height()
                    )
                    // 此时 finalCroppedBitmap 的尺寸是 crop.width() x crop.height()
                    // 而 imageProxy.width 和 imageProxy.height 应该是等于 crop.width() 和 crop.height() 的
                    // 如果不是，那么 CameraX 的行为与预期不符，或者我的理解有偏差。
                    // 为了安全，我们应该以 crop.width() 和 crop.height() 作为后续 TensorImage 的基础尺寸。
                    // 但模型的输入尺寸是由 tensorImage.load() 和后续的 Rot90Op 决定的，
                    // 最终会由 TensorFlow Lite 内部调整到模型所需输入尺寸。
                    // 我们传递给 ResultsOverlay 的应该是旋转后的尺寸。

                    // 回收之前的 bitmap (如果它不是 tempBitmapForRecycle)
                    if (bitmap != tempBitmapForRecycle) {
                        bitmap.recycle()
                    }
                    bitmap = finalCroppedBitmap // 更新 bitmap 为最终裁剪的版本
                }


                var tensorImage = TensorImage(DataType.UINT8)
                tensorImage.load(bitmap)

                val numRotation = imageProxy.imageInfo.rotationDegrees / 90
                val imageProcessor = ImageProcessor.Builder()
                    .add(Rot90Op(numRotation))
                    .add(CastOp(DataType.UINT8))
                    .build()

                tensorImage = imageProcessor.process(tensorImage)
                // 这个日志打印的是旋转后，即将输入模型的TensorImage的尺寸
                Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Rotated TensorImage dimensions for model: Width=${tensorImage.width}, Height=${tensorImage.height}")


                val startTime = SystemClock.uptimeMillis()
                val detectorForThisFrame = lock.withLock { objectDetector }
                if (detectorForThisFrame == null || isShuttingDown) {
                    Log.w(TAG, "detectLivestreamFrame (TFLite Executor): 检测前 objectDetector 为 null 或正在关闭。跳过检测。")
                    return@execute
                }

                val results: List<Detection> = detectorForThisFrame.detect(tensorImage)
                val endTime = SystemClock.uptimeMillis()
                val inferenceTime = endTime - startTime

                objectDetectorListener?.onResults(
                    ResultBundle(
                        results,
                        inferenceTime,
                        tensorImage.height, // 传递给 Overlay 的是旋转后图像的高度
                        tensorImage.width   // 传递给 Overlay 的是旋转后图像的宽度
                    )
                )

            } catch (e: Exception) {
                Log.e(TAG, "detectLivestreamFrame (TFLite Executor): 处理帧或执行检测时发生异常。", e)
                objectDetectorListener?.onError("处理帧时出错 (TFLite): ${e.message}")
            } finally {
                // 回收所有创建的 Bitmap
                if (firstCroppedBitmapForRecycle != null && !firstCroppedBitmapForRecycle!!.isRecycled) {
                    if (firstCroppedBitmapForRecycle != bitmap) { // 避免重复回收最终的 bitmap
                        firstCroppedBitmapForRecycle!!.recycle()
                    }
                }
                if (tempBitmapForRecycle != null && !tempBitmapForRecycle!!.isRecycled) {
                    if (tempBitmapForRecycle != bitmap && tempBitmapForRecycle != firstCroppedBitmapForRecycle) { // 避免重复回收
                        tempBitmapForRecycle!!.recycle()
                    }
                }
                // 最终的 bitmap (可能是 tempBitmapForRecycle, firstCroppedBitmapForRecycle, 或 finalCroppedBitmap)
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                imageProxy.close()
            }
        }
    }

    fun clearObjectDetector() {
        Log.i(TAG, "clearObjectDetector (TFLite): 公共方法被调用。") // 中文日志: 公共方法被调用。
        val currentExecutor = lock.withLock { executorService }
        if (currentExecutor != null && !currentExecutor.isShutdown) {
            Log.i(TAG, "clearObjectDetector (TFLite): 提交 clearObjectDetectorInternal 到执行器。") // 中文日志: 提交 clearObjectDetectorInternal 到执行器。
            currentExecutor.submit {
                clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
            }
        } else {
            Log.w(TAG, "clearObjectDetector (TFLite): 执行器为 null 或已关闭，尝试同步清理。") // 中文日志: 执行器为 null 或已关闭，尝试同步清理。
            clearObjectDetectorInternal(shutDownExecutor = false, calledFromSetup = false)
        }
    }

    fun isClosed(): Boolean {
        val closedState = lock.withLock { objectDetector == null || isShuttingDown }
        return closedState
    }

    data class ResultBundle(
        val results: List<Detection>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    companion object {
        const val TAG = "ObjectDetectorHelper"
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_THRESHOLD = 0.6f
        const val DEFAULT_MAX_RESULTS = 1
        const val DEFAULT_DETECTION_INTERVAL_MS: Long = 200
        const val OTHER_ERROR = 0
        const val GPU_ERROR_INIT_FAIL = 1
    }
}
