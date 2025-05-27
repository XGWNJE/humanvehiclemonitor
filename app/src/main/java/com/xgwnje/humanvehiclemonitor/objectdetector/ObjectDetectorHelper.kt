// ObjectDetectorHelper.kt
package com.xgwnje.humanvehiclemonitor.objectdetector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect // Keep for logging if needed
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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ObjectDetectorHelper(
    var threshold: Float = DEFAULT_THRESHOLD,
    var maxResults: Int = DEFAULT_MAX_RESULTS,
    var currentDelegate: Int = DELEGATE_CPU,
    var modelName: String = "2.tflite", // Make sure this model is in assets
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
                lastDetectionAttemptTimestamp = 0L
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
            imageProxy.close()
            return
        }

        if (currentExecutor == null || currentExecutor.isShutdown) {
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
            var modelInputBitmap: Bitmap? = null
            var fullPlaneBitmapWithPadding: Bitmap? = null // For recycling
            var intermediateBitmap: Bitmap? = null      // For recycling

            try {
                val sourceImageRotation = imageProxy.imageInfo.rotationDegrees
                val cropRect = imageProxy.cropRect

                Log.d(TAG, "detectLivestreamFrame (TFLite Executor): ImageProxy received: width=${imageProxy.width}, height=${imageProxy.height}, cropRect=${cropRect}, rotationDegrees=${sourceImageRotation}, format=${imageProxy.format}")

                val planeProxy = imageProxy.planes[0]
                val buffer: ByteBuffer = planeProxy.buffer
                val pixelStride: Int = planeProxy.pixelStride
                val rowStride: Int = planeProxy.rowStride
                val rowPadding = rowStride - pixelStride * imageProxy.width

                // 1. Create bitmap of full imageProxy plane (imageProxy.width x imageProxy.height), handling rowPadding.
                fullPlaneBitmapWithPadding = Bitmap.createBitmap(
                    imageProxy.width + rowPadding / pixelStride, // Full width from buffer
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                buffer.rewind()
                fullPlaneBitmapWithPadding.copyPixelsFromBuffer(buffer)

                intermediateBitmap = if (rowPadding > 0) {
                    // If there's padding, crop to imageProxy.width x imageProxy.height
                    Bitmap.createBitmap(fullPlaneBitmapWithPadding, 0, 0, imageProxy.width, imageProxy.height)
                } else {
                    // No padding, fullPlaneBitmapWithPadding is already imageProxy.width x imageProxy.height
                    fullPlaneBitmapWithPadding // Assign directly, don't create new bitmap
                }
                // Now intermediateBitmap is imageProxy.width x imageProxy.height

                // 2. Crop this intermediateBitmap using cropRect to get the final modelInputBitmap
                // cropRect coordinates are relative to the imageProxy.width x imageProxy.height plane.
                if (cropRect.left == 0 && cropRect.top == 0 && cropRect.width() == intermediateBitmap.width && cropRect.height() == intermediateBitmap.height) {
                    // No further cropping needed if cropRect matches intermediateBitmap dimensions and origin
                    modelInputBitmap = intermediateBitmap
                } else {
                    Log.d(TAG, "Applying cropRect $cropRect to intermediateBitmap (${intermediateBitmap.width}x${intermediateBitmap.height})")
                    modelInputBitmap = Bitmap.createBitmap(
                        intermediateBitmap,
                        cropRect.left,
                        cropRect.top,
                        cropRect.width(),
                        cropRect.height()
                    )
                }
                // Now 'modelInputBitmap' is the correctly cropped image based on cropRect.
                // Its dimensions are cropRect.width() x cropRect.height().

                Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Final modelInputBitmap dimensions: ${modelInputBitmap.width}x${modelInputBitmap.height}")

                var tensorImage = TensorImage(DataType.UINT8)
                tensorImage.load(modelInputBitmap)

                val numRotation = sourceImageRotation / 90
                val imageProcessor = ImageProcessor.Builder()
                    .add(Rot90Op(numRotation))
                    .add(CastOp(DataType.UINT8))
                    .build()

                tensorImage = imageProcessor.process(tensorImage)
                Log.d(TAG, "detectLivestreamFrame (TFLite Executor): Rotated TensorImage dimensions for model: Width=${tensorImage.width}, Height=${tensorImage.height}")

                val startTime = SystemClock.uptimeMillis()
                val detectorForThisFrame = lock.withLock { objectDetector }
                if (detectorForThisFrame == null || isShuttingDown) {
                    Log.w(TAG, "detectLivestreamFrame (TFLite Executor): 检测前 objectDetector 为 null 或正在关闭。跳过检测。")
                } else {
                    val results: List<Detection> = detectorForThisFrame.detect(tensorImage)
                    val endTime = SystemClock.uptimeMillis()
                    val inferenceTime = endTime - startTime

                    objectDetectorListener?.onResults(
                        ResultBundle(
                            results = results,
                            inferenceTime = inferenceTime,
                            inputImageHeight = tensorImage.height,
                            inputImageWidth = tensorImage.width,
                            sourceImageRotationDegrees = sourceImageRotation
                        )
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "detectLivestreamFrame (TFLite Executor): 处理帧或执行检测时发生异常。", e)
                objectDetectorListener?.onError("处理帧时出错 (TFLite): ${e.message}")
            } finally {
                // Recycle bitmaps carefully
                // modelInputBitmap is the final one used.
                // intermediateBitmap might be the same as modelInputBitmap or fullPlaneBitmapWithPadding.
                // fullPlaneBitmapWithPadding is the largest one.

                if (modelInputBitmap != null && !modelInputBitmap.isRecycled) {
                    // If modelInputBitmap is different from intermediateBitmap and fullPlaneBitmapWithPadding, it's a distinct bitmap.
                    // If it's the same as intermediateBitmap, but intermediateBitmap is different from fullPlaneBitmapWithPadding,
                    // then intermediateBitmap (and thus modelInputBitmap) needs recycling if fullPlaneBitmapWithPadding is also recycled.
                    // This gets complex. The safest is to recycle if they are distinct objects.

                    // Recycle modelInputBitmap if it's a unique instance
                    if (modelInputBitmap != intermediateBitmap && modelInputBitmap != fullPlaneBitmapWithPadding) {
                        modelInputBitmap.recycle()
                        Log.d(TAG, "Recycled modelInputBitmap (unique instance)")
                    }
                }

                if (intermediateBitmap != null && !intermediateBitmap.isRecycled) {
                    // Recycle intermediateBitmap if it's unique from fullPlane and not the one used by model (if modelInputBitmap was a crop of it)
                    if (intermediateBitmap != fullPlaneBitmapWithPadding && intermediateBitmap != modelInputBitmap) {
                        intermediateBitmap.recycle()
                        Log.d(TAG, "Recycled intermediateBitmap (unique and not modelInput)")
                    } else if (intermediateBitmap == modelInputBitmap && intermediateBitmap != fullPlaneBitmapWithPadding) {
                        // This means modelInputBitmap was intermediateBitmap. If it's different from fullPlane, recycle it here.
                        // (Handled by the modelInputBitmap check above if it's distinct from fullPlane)
                        // Actually, if modelInputBitmap IS intermediateBitmap, it will be recycled by the modelInputBitmap check if needed.
                    }
                }

                // Always recycle fullPlaneBitmapWithPadding if it was created and is not the same as modelInputBitmap (which could happen if no padding and no crop)
                if (fullPlaneBitmapWithPadding != null && !fullPlaneBitmapWithPadding.isRecycled) {
                    if (fullPlaneBitmapWithPadding != modelInputBitmap && fullPlaneBitmapWithPadding != intermediateBitmap) {
                        fullPlaneBitmapWithPadding.recycle()
                        Log.d(TAG, "Recycled fullPlaneBitmapWithPadding (unique)")
                    } else if (fullPlaneBitmapWithPadding == intermediateBitmap && intermediateBitmap != modelInputBitmap) {
                        // fullPlane was intermediate, but modelInput was a crop of it. Recycle fullPlane/intermediate.
                        fullPlaneBitmapWithPadding.recycle()
                        Log.d(TAG, "Recycled fullPlaneBitmapWithPadding (was intermediate, model cropped from it)")
                    } else if (fullPlaneBitmapWithPadding == modelInputBitmap) {
                        // fullPlane was directly used as modelInput. Recycle it.
                        fullPlaneBitmapWithPadding.recycle()
                        Log.d(TAG, "Recycled fullPlaneBitmapWithPadding (was modelInput)")
                    }
                }
                imageProxy.close()
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
        val sourceImageRotationDegrees: Int
    )

    companion object {
        const val TAG = "ObjectDetectorHelper"
        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_THRESHOLD = 0.4f
        const val DEFAULT_MAX_RESULTS = 3
        const val DEFAULT_DETECTION_INTERVAL_MS: Long = 3000
        const val OTHER_ERROR = 0
        const val GPU_ERROR_INIT_FAIL = 1
    }
}
