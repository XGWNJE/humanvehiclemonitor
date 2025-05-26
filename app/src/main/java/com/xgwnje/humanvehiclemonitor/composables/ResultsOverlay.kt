// composables/ResultsOverlay.kt
package com.xgwnje.humanvehiclemonitor.composables

import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tensorflow.lite.task.vision.detector.Detection
import kotlin.math.roundToInt

@Composable
fun ResultsOverlay(
    results: List<Detection>?,
    imageHeight: Int,
    imageWidth: Int,
    personLabels: Set<String>, // 新增参数
    vehicleLabels: Set<String>, // 新增参数
    modifier: Modifier = Modifier
) {
    if (imageHeight == 0 || imageWidth == 0) {
        Log.w("ResultsOverlay(TFLite)", "图像高度或宽度为零，不绘制覆盖层。ImageH: $imageHeight, ImageW: $imageWidth") // 中文日志
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvasWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            Log.w("ResultsOverlay(TFLite)", "画布宽度或高度为零或负数。CanvasW: $canvasWidthPx, CanvasH: $canvasHeightPx") // 中文日志
            return@BoxWithConstraints
        }

        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val canvasAspectRatio = canvasWidthPx / canvasHeightPx

        val scale: Float
        val transformOffsetX: Float
        val transformOffsetY: Float

        if (imageAspectRatio > canvasAspectRatio) {
            scale = canvasWidthPx / imageWidth.toFloat()
            transformOffsetX = 0f
            transformOffsetY = (canvasHeightPx - (imageHeight.toFloat() * scale)) / 2f
        } else {
            scale = canvasHeightPx / imageHeight.toFloat()
            transformOffsetY = 0f
            transformOffsetX = (canvasWidthPx - (imageWidth.toFloat() * scale)) / 2f
        }

        if (results != null) {
            results.forEachIndexed { index, detection ->
                val uniqueKey = detection.boundingBox.hashCode() + index

                key(uniqueKey) {
                    if (detection.categories.isNotEmpty()) {
                        val topCategory = detection.categories[0]
                        val normalizedLabel = topCategory.label.lowercase().trim()

                        // 过滤逻辑
                        val isPerson = personLabels.contains(normalizedLabel)
                        // 使用与报警逻辑类似的车辆判断方式，以获得一致性
                        val isVehicle = vehicleLabels.any { vehicleLabel -> normalizedLabel.contains(vehicleLabel) }


                        if (isPerson || isVehicle) { // 只绘制人和车辆
                            val boundingBox: RectF = detection.boundingBox

                            val targetBoxLeftPx = boundingBox.left * scale + transformOffsetX
                            val targetBoxTopPx = boundingBox.top * scale + transformOffsetY
                            val targetBoxWidthPx = boundingBox.width() * scale
                            val targetBoxHeightPx = boundingBox.height() * scale

                            if (targetBoxWidthPx <= 0 || targetBoxHeightPx <= 0) {
                                Log.w("ResultsOverlay(TFLite)", "计算得到的检测框尺寸无效 (<=0): W=$targetBoxWidthPx, H=$targetBoxHeightPx. 跳过绘制此框。") // 中文日志
                            } else {
                                val boxWidthDp: Dp = with(LocalDensity.current) { targetBoxWidthPx.toDp() }
                                val boxHeightDp: Dp = with(LocalDensity.current) { targetBoxHeightPx.toDp() }

                                if (boxWidthDp > 0.dp && boxHeightDp > 0.dp) {
                                    Box(
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    targetBoxLeftPx.roundToInt(),
                                                    targetBoxTopPx.roundToInt()
                                                )
                                            }
                                            .size(width = boxWidthDp, height = boxHeightDp)
                                            .border(2.dp, Color.Green)
                                    )

                                    val labelText = "${topCategory.label} (${String.format("%.2f", topCategory.score)})"
                                    Box(
                                        modifier = Modifier
                                            .offset {
                                                IntOffset(
                                                    targetBoxLeftPx.roundToInt(),
                                                    targetBoxTopPx.roundToInt()
                                                )
                                            }
                                            .background(Color.Green.copy(alpha = 0.7f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = labelText,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
