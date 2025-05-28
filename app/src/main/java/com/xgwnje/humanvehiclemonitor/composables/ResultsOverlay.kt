// composables/ResultsOverlay.kt
package com.xgwnje.humanvehiclemonitor.composables

import android.graphics.RectF
import android.util.Log
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale

@Composable
fun ResultsOverlay(
    results: List<Detection>?,
    imageHeightFromModel: Int,      // 模型实际处理的图像的高度 (Rot90Op 之后)
    imageWidthFromModel: Int,       // 模型实际处理的图像的宽度 (Rot90Op 之后)
    sourceImageRotationDegrees: Int,// 原始 ImageProxy 需要顺时针旋转多少度才能“摆正”
    personLabels: Set<String>,
    vehicleLabels: Set<String>,
    modifier: Modifier = Modifier // 这个 modifier 现在应该从 MainActivity 传递过来，并带有正确的尺寸
) {
    val overlayTag = "ResultsOverlay(FinalAttempt)" // 更新Tag

    if (imageHeightFromModel == 0 || imageWidthFromModel == 0) {
        Log.w(overlayTag, "模型图像高度或宽度为零。 H:$imageHeightFromModel, W:$imageWidthFromModel")
        return
    }

    val view = LocalView.current
    val displayRotation = view.display.rotation // 屏幕当前的物理方向

    Log.d(overlayTag, "DisplayRotation: $displayRotation, SourceImgOriginalRotation: $sourceImageRotationDegrees, ModelDims: ${imageWidthFromModel}w x ${imageHeightFromModel}h")

    val textMeasurer = rememberTextMeasurer()
    val labelTextColor = Color.White
    val labelBackgroundColor = Color.Black.copy(alpha = 0.7f)
    val boxColorPerson = Color.Green
    val boxColorVehicle = Color.Blue
    val strokeWidth = 2.dp
    val textPadding = 2.dp

    Canvas(modifier = modifier) {
        val canvasWidthPx = this.size.width
        val canvasHeightPx = this.size.height

        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) {
            Log.w(overlayTag, "画布尺寸无效或为零。W:$canvasWidthPx, H:$canvasHeightPx")
            return@Canvas
        }

        val actualPreviewContentWidthPx = canvasWidthPx
        val actualPreviewContentHeightPx = canvasHeightPx
        val offsetXForPreviewCenter = 0f
        val offsetYForPreviewCenter = 0f

        Log.d(overlayTag, "Canvas direct draw (sized by parent): ${canvasWidthPx}x${canvasHeightPx}")

        val isDisplayPhysicallyFlipped = displayRotation == Surface.ROTATION_180 || displayRotation == Surface.ROTATION_270

        if (results?.isNotEmpty() == true) {
            Log.d(overlayTag, "----- ResultsOverlay Redraw with results -----")
            Log.d(overlayTag, "Props: displayRotation=$displayRotation, sourceImgRotDeg=$sourceImageRotationDegrees, modelW=$imageWidthFromModel, modelH=$imageHeightFromModel")
            Log.d(overlayTag, "Canvas: W=${canvasWidthPx}, H=${canvasHeightPx}. isDisplayFlipped=$isDisplayPhysicallyFlipped")
        }

        withTransform({
            if (isDisplayPhysicallyFlipped) {
                rotate(degrees = 180f, pivot = this.center)
            }
        }) {
            results?.forEachIndexed { index, detection ->
                if (detection.categories.isEmpty()) return@forEachIndexed
                val topCategory = detection.categories[0]
                val normalizedLabel = topCategory.label.lowercase().trim()
                val boxColor = if (personLabels.contains(normalizedLabel)) boxColorPerson else if (vehicleLabels.any { vl -> normalizedLabel.contains(vl) }) boxColorVehicle else return@forEachIndexed

                val boundingBoxModel: RectF = detection.boundingBox

                var sensorOrientedLeft = boundingBoxModel.left
                var sensorOrientedTop = boundingBoxModel.top
                var sensorOrientedRight = boundingBoxModel.right
                var sensorOrientedBottom = boundingBoxModel.bottom

                val W_sensor_orig: Float
                val H_sensor_orig: Float

                if (sourceImageRotationDegrees == 90 || sourceImageRotationDegrees == 270) {
                    W_sensor_orig = imageHeightFromModel.toFloat()
                    H_sensor_orig = imageWidthFromModel.toFloat()
                } else {
                    W_sensor_orig = imageWidthFromModel.toFloat()
                    H_sensor_orig = imageHeightFromModel.toFloat()
                }

                when (sourceImageRotationDegrees) {
                    0 -> { /* No change needed, already sensor oriented */ }
                    90 -> {
                        sensorOrientedLeft = boundingBoxModel.top
                        sensorOrientedTop = H_sensor_orig - boundingBoxModel.right
                        sensorOrientedRight = boundingBoxModel.bottom
                        sensorOrientedBottom = H_sensor_orig - boundingBoxModel.left
                    }
                    180 -> {
                        sensorOrientedLeft = W_sensor_orig - boundingBoxModel.right
                        sensorOrientedTop = H_sensor_orig - boundingBoxModel.bottom
                        sensorOrientedRight = W_sensor_orig - boundingBoxModel.left
                        sensorOrientedBottom = H_sensor_orig - boundingBoxModel.top
                    }
                    270 -> {
                        sensorOrientedLeft = W_sensor_orig - boundingBoxModel.bottom
                        sensorOrientedTop = boundingBoxModel.left
                        sensorOrientedRight = W_sensor_orig - boundingBoxModel.top
                        sensorOrientedBottom = boundingBoxModel.right
                    }
                }
                if (index == 0) {
                    Log.d(overlayTag, "[UnRotate $sourceImageRotationDegrees] SensorOrientedLTRB: $sensorOrientedLeft, $sensorOrientedTop, $sensorOrientedRight, $sensorOrientedBottom. SensorDims (for scaling): W=$W_sensor_orig, H=$H_sensor_orig")
                }

                val scaleToPreviewX = actualPreviewContentWidthPx / W_sensor_orig
                val scaleToPreviewY = actualPreviewContentHeightPx / H_sensor_orig

                // 初步计算缩放和平移后的坐标 (这些是“如果直接绘制”的坐标)
                val preCorrectedLeft = sensorOrientedLeft * scaleToPreviewX + offsetXForPreviewCenter
                val preCorrectedTop = sensorOrientedTop * scaleToPreviewY + offsetYForPreviewCenter
                val preCorrectedRight = sensorOrientedRight * scaleToPreviewX + offsetXForPreviewCenter
                val preCorrectedBottom = sensorOrientedBottom * scaleToPreviewY + offsetYForPreviewCenter

                var finalLeft: Float
                var finalTop: Float
                var finalRight: Float
                var finalBottom: Float

                // 针对竖屏模式下观察到的“顺时针90度”偏移进行校正
                if (sourceImageRotationDegrees == 90 || sourceImageRotationDegrees == 270) {
                    if (index == 0) Log.d(overlayTag, "Applying -90deg visual correction for sourceRot $sourceImageRotationDegrees. PreCorrectionLTRB: $preCorrectedLeft, $preCorrectedTop, $preCorrectedRight, $preCorrectedBottom")
                    // 逆时针旋转90度校正: newX = oldY; newY = PreviewContentHeightAvailableForOldX - oldX
                    // 注意：这里的变换是作用在已经缩放和平移到“预览内容区域”内的坐标上的
                    // oldX 对应 preCorrectedLeft (相对于 offsetXForPreviewCenter)
                    // oldY 对应 preCorrectedTop (相对于 offsetYForPreviewCenter)
                    // PreviewContentHeightAvailableForOldX 对应 actualPreviewContentHeightPx (因为旧的X轴现在是新的Y轴，其范围是内容高度)
                    // PreviewContentWidthAvailableForOldY 对应 actualPreviewContentWidthPx

                    finalLeft = preCorrectedTop // newX = oldY (relative to overall canvas origin)
                    finalTop = actualPreviewContentWidthPx - preCorrectedRight // newY = CanvasWidth - oldX_right

                    // 宽度和高度也需要交换
                    val originalScaledWidth = preCorrectedRight - preCorrectedLeft
                    val originalScaledHeight = preCorrectedBottom - preCorrectedTop

                    finalRight = finalLeft + originalScaledHeight // 新的宽度是旧的高度
                    finalBottom = finalTop + originalScaledWidth    // 新的高度是旧的宽度

                    if (index == 0) Log.d(overlayTag, "Post-correction LTRB: $finalLeft, $finalTop, $finalRight, $finalBottom")
                } else { // 横屏或反向横屏，直接使用
                    finalLeft = preCorrectedLeft
                    finalTop = preCorrectedTop
                    finalRight = preCorrectedRight
                    finalBottom = preCorrectedBottom
                }

                val rectWidth = finalRight - finalLeft
                val rectHeight = finalBottom - finalTop

                if (index == 0) {
                    Log.d(overlayTag, "Detection[0]: Label='${topCategory.label}', Score=${topCategory.score}")
                    Log.d(overlayTag, "Detection[0] Scales: scaleX=${scaleToPreviewX}, scaleY=${scaleToPreviewY}")
                    Log.d(overlayTag, "Detection[0] FinalCoords: L=${finalLeft}, T=${finalTop}, R=${finalRight}, B=${finalBottom}")
                    Log.d(overlayTag, "Detection[0] FinalDims: W=${rectWidth}, H=${rectHeight}")
                }

                if (rectWidth > 0 && rectHeight > 0) {
                    drawRect(
                        color = boxColor,
                        topLeft = Offset(finalLeft, finalTop),
                        size = Size(rectWidth, rectHeight),
                        style = Stroke(width = strokeWidth.toPx())
                    )
                    val labelText = "${topCategory.label} (${String.format(Locale.US, "%.1f", topCategory.score * 100)}%)"
                    val textStyle = TextStyle(
                        color = labelTextColor,
                        fontSize = 12.sp,
                        background = labelBackgroundColor
                    )
                    val textLayoutResult: TextLayoutResult = textMeasurer.measure(
                        text = AnnotatedString(labelText),
                        style = textStyle,
                        constraints = Constraints(maxWidth = (rectWidth - 2 * textPadding.toPx()).toInt().coerceAtLeast(0))
                    )
                    val textWidth = textLayoutResult.size.width
                    val textHeight = textLayoutResult.size.height

                    var textDrawX = finalLeft + textPadding.toPx()
                    var textDrawY = finalTop + textPadding.toPx()

                    if (textDrawX + textWidth > finalRight - textPadding.toPx()) {
                        textDrawX = finalRight - textWidth - textPadding.toPx()
                    }
                    if (textDrawY + textHeight > finalBottom - textPadding.toPx()) {
                        textDrawY = finalBottom - textHeight - textPadding.toPx()
                    }
                    textDrawX = textDrawX.coerceAtLeast(finalLeft + textPadding.toPx())
                    textDrawY = textDrawY.coerceAtLeast(finalTop + textPadding.toPx())

                    if (isDisplayPhysicallyFlipped) {
                        withTransform({
                            rotate(degrees = 180f, pivot = Offset(textDrawX + textWidth / 2f, textDrawY + textHeight / 2f))
                        }) {
                            drawText(
                                textLayoutResult = textLayoutResult,
                                topLeft = Offset(textDrawX, textDrawY)
                            )
                        }
                    } else {
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(textDrawX, textDrawY)
                        )
                    }
                }
            }
        }
    }
}