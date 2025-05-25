package com.xgwnje.humanvehiclemonitor.objectdetector

import android.content.Context
import android.graphics.Bitmap
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
    var detectionIntervalMillis: Long = DEFAULT_DETECTION_INTERVAL_MS, // 新增：检测间隔参数
    val context: Context,
    var objectDetectorListener: ObjectDetectorListener? = null
) : DefaultLifecycleObserver {

    private var objectDetector: ObjectDetector? = null
    private var executorService: ExecutorService? = null
    private val lock = ReentrantLock()
    @Volatile
    private var isShuttingDown: Boolean = false
    private var lastDetectionAttemptTimestamp: Long = 0L // 新增：记录上次尝试检测的时间戳

    init {
        Log.i(TAG, "ObjectDetectorHelper (TFLite): 实例已创建。检测间隔: $detectionIntervalMillis ms")
    }

    fun setupObjectDetector() {
        Log.d(TAG, "setupObjectDetector (TFLite): Task STARTED on thread ${Thread.currentThread().name}")
        lock.withLock {
            Log.i(TAG, "setupObjectDetector (TFLite): 开始配置 (lock acquired)。当前状态: objectDetector is ${if (objectDetector == null) "null" else "not null"}, executor is ${if (executorService == null || executorService!!.isShutdown) "null or shutdown" else "active"}, isShuttingDown: $isShuttingDown")
            isShuttingDown = false

            if (objectDetector != null) {
                Log.i(TAG, "setupObjectDetector (TFLite): 检测到已存在的 objectDetector 实例，将进行清理。")
                try {
                    objectDetector?.close()
                    Log.i(TAG, "setupObjectDetector (TFLite): 旧的 objectDetector 实例已关闭。")
                } catch (e: Exception) {
                    Log.e(TAG, "setupObjectDetector (TFLite): 关闭旧 objectDetector 时发生错误。", e)
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
                        Log.i(TAG, "setupObjectDetector (TFLite): 尝试使用 GPU 代理。")
                    } catch (e: Exception) {
                        Log.e(TAG, "setupObjectDetector (TFLite): 设置 GPU 代理失败，回退到 CPU。", e)
                        currentDelegate = DELEGATE_CPU
                        baseOptionsBuilder.setNumThreads(4)
                        delegateName = "CPU (GPU fallback, 4 threads)"
                        objectDetectorListener?.onError("GPU代理设置失败: ${e.message}", GPU_ERROR_INIT_FAIL)
                    }
                }
            }

            Log.i(TAG, "setupObjectDetector (TFLite): 准备创建 ObjectDetector 实例。配置参数: threshold=$threshold, maxResults=$maxResults, delegate=$delegateName, model=$modelName, interval=$detectionIntervalMillis ms")

            try {
                val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOptionsBuilder.build())
                    .setScoreThreshold(threshold)
                    .setMaxResults(maxResults)

                val objectDetectorOptions = optionsBuilder.build()
                objectDetector = ObjectDetector.createFromFileAndOptions(context, modelName, objectDetectorOptions)
                Log.i(TAG, "setupObjectDetector (TFLite): ObjectDetector 实例创建成功!")
                isShuttingDown = false
                lastDetectionAttemptTimestamp = 0L // 重置上次检测时间戳，以便新实例立即开始检测
            } catch (e: Exception) {
                val errorMessage = "TFLite Object detector 初始化失败。模型: '$modelName'. 代理: $delegateName. 错误: ${e.message}"
                objectDetectorListener?.onError(errorMessage, OTHER_ERROR)
                Log.e(TAG, "setupObjectDetector (TFLite): ObjectDetector 实例创建失败。模型: '$modelName'", e)
                isShuttingDown = true
            }
        }
        Log.d(TAG, "setupObjectDetector (TFLite): Task FINISHED on thread ${Thread.currentThread().name}. Detector is ${if(objectDetector != null) "NOT NULL" else "NULL"}")
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.i(TAG, "onResume (TFLite): 生命周期回调。isShuttingDown 当前值: $isShuttingDown. Detector: ${if(objectDetector == null) "null" else "not null"}")
        lock.withLock {
            if (executorService == null || executorService!!.isShutdown) {
                Log.i(TAG, "onResume (TFLite): executorService 为 null 或已关闭，创建新的。")
                executorService = Executors.newSingleThreadExecutor()
            }
        }

        if (objectDetector == null && !isShuttingDown) {
            Log.i(TAG, "onResume (TFLite): ObjectDetector 为 null 且未关闭，提交 setupObjectDetector 到执行器。")
            executorService?.submit {
                setupObjectDetector()
            }
        } else {
            Log.i(TAG, "onResume (TFLite): 跳过 setupObjectDetector 提交。Detector: ${if(objectDetector == null) "null" else "not null"}, isShuttingDown: $isShuttingDown")
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Log.i(TAG, "onPause (TFLite): 生命周期回调。isShuttingDown 当前值: $isShuttingDown")
        val currentExecutor = executorService
        if (currentExecutor != null && !currentExecutor.isShutdown) {
            Log.i(TAG, "onPause (TFLite): 提交 clearObjectDetectorInternal 到执行器。")
            currentExecutor.submit {
                clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
            }
        } else {
            Log.w(TAG, "onPause (TFLite): 执行器为 null 或已关闭，尝试同步清理。")
            clearObjectDetectorInternal(shutDownExecutor = false, calledFromSetup = false)
        }
    }

    private fun clearObjectDetectorInternal(shutDownExecutor: Boolean, calledFromSetup: Boolean) {
        Log.d(TAG, "clearObjectDetectorInternal (TFLite): Task STARTED on thread ${Thread.currentThread().name}. shutDownExecutor: $shutDownExecutor, calledFromSetup: $calledFromSetup")
        lock.withLock {
            Log.i(TAG, "clearObjectDetectorInternal (TFLite): 开始清理 (lock acquired)。当前 objectDetector 状态: ${if (objectDetector == null) "null" else "not null"}, isShuttingDown: $isShuttingDown")

            if (!calledFromSetup) {
                isShuttingDown = true
                Log.i(TAG, "clearObjectDetectorInternal (TFLite): isShuttingDown 设置为 true。")
            }

            try {
                objectDetector?.close()
                Log.i(TAG, "clearObjectDetectorInternal (TFLite): objectDetector 实例已调用 close()。")
            } catch (e: Exception) {
                Log.e(TAG, "clearObjectDetectorInternal (TFLite): 关闭 objectDetector 时发生错误。", e)
            }
            objectDetector = null
            Log.i(TAG, "clearObjectDetectorInternal (TFLite): objectDetector 实例已设为 null。")

            if (shutDownExecutor && executorService != null) {
                if (!executorService!!.isShutdown) {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 准备关闭后台执行器 (shutdown)。")
                    executorService?.shutdown()
                    try {
                        if (executorService?.awaitTermination(200, TimeUnit.MILLISECONDS) == false) {
                            Log.w(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在等待200ms后未终止，强制关闭 (shutdownNow)。")
                            executorService?.shutdownNow()
                            if (executorService?.awaitTermination(100, TimeUnit.MILLISECONDS) == false) {
                                Log.e(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在 shutdownNow 后仍未能终止。")
                            } else {
                                Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器在 shutdownNow 后成功终止。")
                            }
                        } else {
                            Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器已优雅关闭。")
                        }
                    } catch (e: InterruptedException) {
                        Log.w(TAG, "clearObjectDetectorInternal (TFLite): 等待执行器终止时被中断，强制关闭。", e)
                        executorService?.shutdownNow()
                        Thread.currentThread().interrupt()
                    }
                } else {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 后台执行器已被要求关闭或为null，无需操作。")
                }
                if (calledFromSetup) {
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): 从 setup 调用，不将 executorService 设为 null。")
                } else {
                    executorService = null
                    Log.i(TAG, "clearObjectDetectorInternal (TFLite): executorService 已设为 null。")
                }
            } else if (shutDownExecutor) {
                Log.w(TAG, "clearObjectDetectorInternal (TFLite): 请求关闭执行器，但 executorService 为 null。")
            }
        }
        Log.d(TAG, "clearObjectDetectorInternal (TFLite): Task FINISHED on thread ${Thread.currentThread().name}")
    }

    @WorkerThread
    fun detectLivestreamFrame(imageProxy: ImageProxy) {
        val currentDetector = lock.withLock { objectDetector }
        val currentExecutor = lock.withLock { executorService }

        if (isShuttingDown || currentDetector == null) {
            if(isShuttingDown) Log.v(TAG, "detectLivestreamFrame (TFLite): ObjectDetectorHelper 正在关闭，跳过帧。") // Verbose
            if(currentDetector == null && !isShuttingDown) Log.w(TAG, "detectLivestreamFrame (TFLite): ObjectDetector 未初始化，跳过帧。")
            imageProxy.close()
            return
        }

        if (currentExecutor == null || currentExecutor.isShutdown) {
            Log.w(TAG, "detectLivestreamFrame (TFLite): 后台执行器未准备好或已关闭，跳过帧。")
            imageProxy.close()
            return
        }

        val currentTimeMs = SystemClock.uptimeMillis()
        // 新增：检测间隔控制
        if (currentTimeMs - lastDetectionAttemptTimestamp < detectionIntervalMillis) {
            // Log.v(TAG, "detectLivestreamFrame (TFLite): 跳过帧，未达到检测间隔 ${detectionIntervalMillis}ms。距离上次: ${currentTimeMs - lastDetectionAttemptTimestamp}ms") // Verbose
            imageProxy.close() // 必须关闭，否则 CameraX 会停止发送新帧
            return
        }
        // 更新时间戳，表示我们正在尝试处理这一帧 (即使后续处理失败)
        // 或者，也可以只在成功提交到执行器后更新，取决于具体需求
        // lastDetectionAttemptTimestamp = currentTimeMs; // 如果希望严格按间隔尝试提交

        val frameTime = SystemClock.uptimeMillis() // 和 currentTimeMs 几乎一样，可以复用
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        // Log.d(TAG, "detectLivestreamFrame (TFLite): 提交帧到执行器。时间戳: $frameTime, 旋转: $rotationDegrees.") // Verbose

        currentExecutor.execute {
            // Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Task STARTED on thread ${Thread.currentThread().name}") // Verbose
            // 在执行器内部再次更新时间戳，表示实际开始处理的时间
            lastDetectionAttemptTimestamp = SystemClock.uptimeMillis()

            var bitmap: Bitmap? = null
            try {
                val planes = imageProxy.planes
                val buffer = planes[0].buffer.apply { rewind() }
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * imageProxy.width

                val tempBitmap = Bitmap.createBitmap(
                    imageProxy.width + rowPadding / pixelStride,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmap.copyPixelsFromBuffer(buffer)

                bitmap = if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, imageProxy.width, imageProxy.height)
                    tempBitmap.recycle()
                    cropped
                } else {
                    tempBitmap
                }

                // Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Bitmap 创建成功 ${bitmap.width}x${bitmap.height}") // Verbose

                var tensorImage = TensorImage(DataType.UINT8)
                tensorImage.load(bitmap)

                val numRotation = rotationDegrees / 90
                val imageProcessor = ImageProcessor.Builder()
                    .add(Rot90Op(numRotation))
                    .add(CastOp(DataType.UINT8))
                    .build()

                tensorImage = imageProcessor.process(tensorImage)
                // Log.d(TAG, "detectLivestreamFrame (TFLite Executor): TensorImage 处理完毕。旋转次数: $numRotation. 最终图像尺寸: ${tensorImage.width}x${tensorImage.height}") // Verbose

                val startTime = SystemClock.uptimeMillis()
                val detectorForThisFrame = lock.withLock { objectDetector }
                if (detectorForThisFrame == null || isShuttingDown) {
                    Log.w(TAG, "detectLivestreamFrame (TFLite Executor): 检测前 objectDetector 为 null 或正在关闭。跳过检测。")
                    return@execute
                }

                val results: List<Detection> = detectorForThisFrame.detect(tensorImage)
                val endTime = SystemClock.uptimeMillis()
                val inferenceTime = endTime - startTime

                // Log.d(TAG, "detectLivestreamFrame (TFLite Executor): 检测成功。耗时: $inferenceTime ms, 结果数量: ${results.size}。") // Verbose
                objectDetectorListener?.onResults(
                    ResultBundle(
                        results,
                        inferenceTime,
                        tensorImage.height,
                        tensorImage.width
                    )
                )

            } catch (e: Exception) {
                Log.e(TAG, "detectLivestreamFrame (TFLite Executor): 处理帧或执行检测时发生异常。", e)
                objectDetectorListener?.onError("处理帧时出错 (TFLite): ${e.message}")
            } finally {
                bitmap?.recycle()
                imageProxy.close() // 确保 ImageProxy 在这里关闭
                // Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Task FINISHED on thread ${Thread.currentThread().name}") // Verbose
            }
        }
    }

    fun clearObjectDetector() {
        Log.i(TAG, "clearObjectDetector (TFLite): 公共方法被调用。")
        val currentExecutor = lock.withLock { executorService }
        if (currentExecutor != null && !currentExecutor.isShutdown) {
            Log.i(TAG, "clearObjectDetector (TFLite): 提交 clearObjectDetectorInternal 到执行器。")
            currentExecutor.submit {
                clearObjectDetectorInternal(shutDownExecutor = true, calledFromSetup = false)
            }
        } else {
            Log.w(TAG, "clearObjectDetector (TFLite): 执行器为 null 或已关闭，尝试同步清理。")
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
        const val DEFAULT_THRESHOLD = 0.4f
        const val DEFAULT_MAX_RESULTS = 5
        const val DEFAULT_DETECTION_INTERVAL_MS: Long = 200 // 默认检测间隔 200ms
        const val OTHER_ERROR = 0
        const val GPU_ERROR_INIT_FAIL = 1
    }
}
