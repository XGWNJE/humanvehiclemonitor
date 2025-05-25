package com.xgwnje.humanvehiclemonitor.objectdetector

import org.tensorflow.lite.task.vision.detector.Detection // TensorFlow Lite Detection

/**
 * 监听器，用于接收 TensorFlow Lite 对象检测器的检测结果和错误。
 */
interface ObjectDetectorListener {
    /**
     * 当检测过程中发生错误时调用。
     * @param error 错误信息。
     * @param errorCode 错误代码 (可选, 默认为 ObjectDetectorHelper.OTHER_ERROR)。
     */
    fun onError(error: String, errorCode: Int = ObjectDetectorHelper.OTHER_ERROR)

    /**
     * 当成功获取检测结果时调用。
     * @param resultBundle 包含检测结果、推理时间及输入图像尺寸的封装对象。
     */
    fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle?) // 改为可空，以处理无结果的情况
}

