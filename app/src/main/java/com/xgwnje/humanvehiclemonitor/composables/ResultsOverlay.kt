// composables/ResultsOverlay.kt
package com.xgwnje.humanvehiclemonitor.composables

import android.graphics.RectF
import android.util.Log
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
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

@Composable
fun ResultsOverlay(
    results: List<Detection>?,
    imageHeightFromModel: Int,      // 模型处理的图像的高度 (Rot90Op 之后)
    imageWidthFromModel: Int,       // 模型处理的图像的宽度 (Rot90Op 之后)
    sourceImageRotationDegrees: Int,// 原始 ImageProxy 帧的旋转角度
    personLabels: Set<String>,
    vehicleLabels: Set<String>,
    modifier: Modifier = Modifier
) {
    val overlayTag = "ResultsOverlay(CoordFix)" // 更新 Tag 以反映修复内容

    if (imageHeightFromModel == 0 || imageWidthFromModel == 0) {
        Log.w(overlayTag, "模型图像高度或宽度为零。 H:$imageHeightFromModel, W:$imageWidthFromModel")
        return
    }

    val view = LocalView.current
    val displayRotation = view.display.rotation

    Log.d(overlayTag, "DisplayRotation: $displayRotation, SourceImgOriginalRotation: $sourceImageRotationDegrees, ModelDims: ${imageWidthFromModel}w x ${imageHeightFromModel}h")

    val textMeasurer = rememberTextMeasurer()
    val labelTextColor = Color.White
    val labelBackgroundColor = Color.Black.copy(alpha = 0.7f)
    val boxColorPerson = Color.Green
    val boxColorVehicle = Color.Blue
    val strokeWidth = 2.dp
    val textPadding = 2.dp

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvasWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            Log.w(overlayTag, "画布尺寸无效。W:$canvasWidthPx, H:$canvasHeightPx")
            return@BoxWithConstraints
        }

        val contentAspectRatioToDisplay = imageWidthFromModel.toFloat() / imageHeightFromModel.toFloat()
        val canvasAspectRatio = canvasWidthPx / canvasHeightPx

        val actualPreviewContentWidthPx: Float
        val actualPreviewContentHeightPx: Float
        val offsetXForPreviewCenter: Float
        val offsetYForPreviewCenter: Float

        if (canvasAspectRatio > contentAspectRatioToDisplay) {
            actualPreviewContentHeightPx = canvasHeightPx
            actualPreviewContentWidthPx = actualPreviewContentHeightPx * contentAspectRatioToDisplay
            offsetXForPreviewCenter = (canvasWidthPx - actualPreviewContentWidthPx) / 2f
            offsetYForPreviewCenter = 0f
        } else {
            actualPreviewContentWidthPx = canvasWidthPx
            actualPreviewContentHeightPx = actualPreviewContentWidthPx / contentAspectRatioToDisplay
            offsetXForPreviewCenter = 0f
            offsetYForPreviewCenter = (canvasHeightPx - actualPreviewContentHeightPx) / 2f
        }

        Log.d(overlayTag, "Canvas: ${canvasWidthPx}x${canvasHeightPx} (AR: $canvasAspectRatio). Content AR to Display: $contentAspectRatioToDisplay. ActualPreview: ${actualPreviewContentWidthPx}x${actualPreviewContentHeightPx}, Offset: ($offsetXForPreviewCenter,$offsetYForPreviewCenter)")

        Canvas(modifier = Modifier.fillMaxSize()) {
            val isDisplayPhysicallyFlipped = displayRotation == Surface.ROTATION_180 || displayRotation == Surface.ROTATION_270

            // Log general canvas state
            Log.d(overlayTag, "----- Canvas Redraw -----")
            Log.d(overlayTag, "Props: displayRotation=$displayRotation, sourceImgRotDeg=$sourceImageRotationDegrees, modelW=$imageWidthFromModel, modelH=$imageHeightFromModel")
            Log.d(overlayTag, "Canvas Dims: canvasW=${canvasWidthPx}, canvasH=${canvasHeightPx}")
            Log.d(overlayTag, "PreviewContent Dims: actualW=${actualPreviewContentWidthPx}, actualH=${actualPreviewContentHeightPx}")
            Log.d(overlayTag, "Offsets: offsetX=${offsetXForPreviewCenter}, offsetY=${offsetYForPreviewCenter}")
            Log.d(overlayTag, "Transform: isDisplayPhysicallyFlipped=$isDisplayPhysicallyFlipped")

            withTransform({
                if (isDisplayPhysicallyFlipped) {
                    rotate(degrees = 180f, pivot = this.center)
                }
            }) {
                results?.forEachIndexed { index, detection ->
                    if (detection.categories.isEmpty()) return@forEachIndexed
                    val topCategory = detection.categories[0]
                    val normalizedLabel = topCategory.label.lowercase().trim()

                    val isPerson = personLabels.contains(normalizedLabel)
                    val isVehicle = vehicleLabels.any { vl -> normalizedLabel.contains(vl) }
                    val boxColor = if (isPerson) boxColorPerson else if (isVehicle) boxColorVehicle else return@forEachIndexed

                    val boundingBoxModel: RectF = detection.boundingBox

                    var mLeft = boundingBoxModel.left
                    var mTop = boundingBoxModel.top
                    var mRight = boundingBoxModel.right
                    var mBottom = boundingBoxModel.bottom

                    // 校正来自模型的、基于旋转后图像的坐标，使其与“视觉正向”的场景对应
                    // imageWidthFromModel/imageHeightFromModel 是模型实际处理的图像的 W/H
                    // H_sensor = imageWidthFromModel, W_sensor = imageHeightFromModel (when sourceImageRotationDegrees is 90 or 270)
                    when (sourceImageRotationDegrees) {
                        90 -> { // 模型输入图像顺时针旋转了90度
                            if (index == 0) Log.d(overlayTag, "Applying 90-deg un-rotate. Original LTRB: ${boundingBoxModel.left}, ${boundingBoxModel.top}, ${boundingBoxModel.right}, ${boundingBoxModel.bottom}")
                            mLeft = boundingBoxModel.top
                            mTop = imageWidthFromModel - boundingBoxModel.right // imageWidthFromModel is H_sensor
                            mRight = boundingBoxModel.bottom
                            mBottom = imageWidthFromModel - boundingBoxModel.left
                            if (index == 0) Log.d(overlayTag, "Un-rotated for 90 LTRB: $mLeft, $mTop, $mRight, $mBottom")
                        }
                        180 -> { // 模型输入图像顺时针旋转了180度
                            if (index == 0) Log.d(overlayTag, "Applying 180-deg un-rotate. Original LTRB: ${boundingBoxModel.left}, ${boundingBoxModel.top}, ${boundingBoxModel.right}, ${boundingBoxModel.bottom}")
                            mLeft = imageWidthFromModel - boundingBoxModel.right
                            mTop = imageHeightFromModel - boundingBoxModel.bottom
                            mRight = imageWidthFromModel - boundingBoxModel.left
                            mBottom = imageHeightFromModel - boundingBoxModel.top
                            if (index == 0) Log.d(overlayTag, "Un-rotated for 180 LTRB: $mLeft, $mTop, $mRight, $mBottom")
                        }
                        270 -> { // 模型输入图像顺时针旋转了270度
                            if (index == 0) Log.d(overlayTag, "Applying 270-deg un-rotate. Original LTRB: ${boundingBoxModel.left}, ${boundingBoxModel.top}, ${boundingBoxModel.right}, ${boundingBoxModel.bottom}")
                            mLeft = imageHeightFromModel - boundingBoxModel.bottom // imageHeightFromModel is W_sensor
                            mTop = boundingBoxModel.left
                            mRight = imageHeightFromModel - boundingBoxModel.top
                            mBottom = boundingBoxModel.right
                            if (index == 0) Log.d(overlayTag, "Un-rotated for 270 LTRB: $mLeft, $mTop, $mRight, $mBottom")
                        }
                        // 0 degrees: no coordinate transformation needed for un-rotation
                    }

                    val modelRectLeft = mLeft
                    val modelRectTop = mTop
                    val modelRectRight = mRight
                    val modelRectBottom = mBottom

                    val scaleInputWidth: Float
                    val scaleInputHeight: Float

                    if (sourceImageRotationDegrees == 90 || sourceImageRotationDegrees == 270) {
                        // "反正"后的坐标 modelRectLeft/Top 等，其概念上的"宽度范围"是原始传感器的宽度 (imageHeightFromModel)
                        // 其概念上的"高度范围"是原始传感器的高度 (imageWidthFromModel)
                        scaleInputWidth = imageHeightFromModel.toFloat() // Original Sensor Width
                        scaleInputHeight = imageWidthFromModel.toFloat()  // Original Sensor Height
                    } else { // 0 or 180 degrees
                        scaleInputWidth = imageWidthFromModel.toFloat()
                        scaleInputHeight = imageHeightFromModel.toFloat()
                    }

                    if (scaleInputWidth == 0f || scaleInputHeight == 0f) {
                        Log.e(overlayTag, "Error: scaleInputWidth or scaleInputHeight is zero. Skipping draw for this detection.")
                        return@forEachIndexed
                    }

                    val scaleToPreviewX = actualPreviewContentWidthPx / scaleInputWidth
                    val scaleToPreviewY = actualPreviewContentHeightPx / scaleInputHeight

                    val finalLeft = modelRectLeft * scaleToPreviewX + offsetXForPreviewCenter
                    val finalTop = modelRectTop * scaleToPreviewY + offsetYForPreviewCenter
                    val finalRight = modelRectRight * scaleToPreviewX + offsetXForPreviewCenter
                    val finalBottom = modelRectBottom * scaleToPreviewY + offsetYForPreviewCenter

                    val rectWidth = finalRight - finalLeft
                    val rectHeight = finalBottom - finalTop

                    if (index == 0) {
                        Log.d(overlayTag, "Detection[0]: Label='${topCategory.label}', Score=${topCategory.score}")
                        Log.d(overlayTag, "Detection[0] (un-rotated & used for scaling) BBoxModel: L=${modelRectLeft}, T=${modelRectTop}, R=${modelRectRight}, B=${modelRectBottom}")
                        Log.d(overlayTag, "Detection[0] Scale Denominators: W=${scaleInputWidth}, H=${scaleInputHeight}")
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

                        val labelText = "${topCategory.label} (${String.format("%.1f", topCategory.score * 100)}%)"
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
}