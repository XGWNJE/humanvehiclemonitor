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
    val overlayTag = "ResultsOverlay(MirrorFix)" // 更新 Tag

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

        val previewViewAspectRatio = canvasWidthPx / canvasHeightPx
        val cameraContentAspectRatio = 4.0f / 3.0f

        val actualPreviewContentWidthPx: Float
        val actualPreviewContentHeightPx: Float
        val offsetXForPreviewCenter: Float
        val offsetYForPreviewCenter: Float

        if (previewViewAspectRatio > cameraContentAspectRatio) {
            actualPreviewContentHeightPx = canvasHeightPx
            actualPreviewContentWidthPx = actualPreviewContentHeightPx * cameraContentAspectRatio
            offsetXForPreviewCenter = (canvasWidthPx - actualPreviewContentWidthPx) / 2f
            offsetYForPreviewCenter = 0f
        } else {
            actualPreviewContentWidthPx = canvasWidthPx
            actualPreviewContentHeightPx = actualPreviewContentWidthPx / cameraContentAspectRatio
            offsetXForPreviewCenter = 0f
            offsetYForPreviewCenter = (canvasHeightPx - actualPreviewContentHeightPx) / 2f
        }
        Log.d(overlayTag, "Canvas: ${canvasWidthPx}x${canvasHeightPx}. Actual 4:3 Preview: ${actualPreviewContentWidthPx}x${actualPreviewContentHeightPx}, Offset: ($offsetXForPreviewCenter,$offsetYForPreviewCenter)")

        Canvas(modifier = Modifier.fillMaxSize()) {
            val isDisplayReverseLandscape = displayRotation == Surface.ROTATION_270

            Log.d(overlayTag, "----- Canvas Redraw -----")
            Log.d(overlayTag, "Props: displayRotation=$displayRotation, sourceImgRotDeg=$sourceImageRotationDegrees, modelW=$imageWidthFromModel, modelH=$imageHeightFromModel")
            Log.d(overlayTag, "Canvas Dims: canvasW=${canvasWidthPx}, canvasH=${canvasHeightPx}")
            Log.d(overlayTag, "PreviewContent Dims: actualW=${actualPreviewContentWidthPx}, actualH=${actualPreviewContentHeightPx}")
            Log.d(overlayTag, "Offsets: offsetX=${offsetXForPreviewCenter}, offsetY=${offsetYForPreviewCenter}")
            Log.d(overlayTag, "Transform: isDisplayReverseLandscape=$isDisplayReverseLandscape")

            withTransform({
                if (isDisplayReverseLandscape) {
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

                    var tempLeft = boundingBoxModel.left
                    var tempTop = boundingBoxModel.top
                    var tempRight = boundingBoxModel.right
                    var tempBottom = boundingBoxModel.bottom

                    // 当源图像为模型旋转了180度时，模型的坐标系相对于“正向”场景是完全颠倒的。
                    // 我们需要将这些坐标“翻转”回来，以匹配 PreviewView 中用户看到的正向图像。
                    if (sourceImageRotationDegrees == 180) {
                        if (index == 0) { // 仅为第一个检测框打印一次这个特定日志
                            Log.d(overlayTag, "Applying 180-deg coordinate un-flip for model BBox. Original LTRB: $tempLeft, $tempTop, $tempRight, $tempBottom")
                        }
                        tempLeft = imageWidthFromModel - boundingBoxModel.right
                        tempTop = imageHeightFromModel - boundingBoxModel.bottom
                        tempRight = imageWidthFromModel - boundingBoxModel.left
                        tempBottom = imageHeightFromModel - boundingBoxModel.top
                        if (index == 0) {
                            Log.d(overlayTag, "Un-flipped BBox LTRB: $tempLeft, $tempTop, $tempRight, $tempBottom")
                        }
                    }
                    // 注意: 如果未来 sourceImageRotationDegrees 可能为 90 或 270，
                    // 并且 imageWidthFromModel/imageHeightFromModel 会相应地交换值 (例如模型输入变成 480x640),
                    // 那么这里也需要类似地处理 90/270 度旋转带来的坐标系变换。
                    // 当前日志显示，对于0度和180度的 sourceImageRotationDegrees，模型输入尺寸(imageWidthFromModel, imageHeightFromModel)始终是640x480。

                    val modelRectLeft = tempLeft
                    val modelRectTop = tempTop
                    val modelRectRight = tempRight
                    val modelRectBottom = tempBottom

                    val scaleToPreviewX = actualPreviewContentWidthPx / imageWidthFromModel.toFloat()
                    val scaleToPreviewY = actualPreviewContentHeightPx / imageHeightFromModel.toFloat()

                    val finalLeft = modelRectLeft * scaleToPreviewX + offsetXForPreviewCenter
                    val finalTop = modelRectTop * scaleToPreviewY + offsetYForPreviewCenter
                    val finalRight = modelRectRight * scaleToPreviewX + offsetXForPreviewCenter
                    val finalBottom = modelRectBottom * scaleToPreviewY + offsetYForPreviewCenter

                    val rectWidth = finalRight - finalLeft
                    val rectHeight = finalBottom - finalTop

                    if (index == 0) {
                        Log.d(overlayTag, "Detection[0]: Label='${topCategory.label}', Score=${topCategory.score}")
                        Log.d(overlayTag, "Detection[0] (potentially un-flipped) BBoxModel: L=${modelRectLeft}, T=${modelRectTop}, R=${modelRectRight}, B=${modelRectBottom}")
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

                        if (isDisplayReverseLandscape) {
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