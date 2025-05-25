package com.xgwnje.humanvehiclemonitor.objectdetector

import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper

// Listener for detection results and errors.
interface ObjectDetectorListener {
    fun onError(error: String, errorCode: Int = ObjectDetectorHelper.OTHER_ERROR)
    fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle)
}
