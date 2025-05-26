// composables/ResultsOverlay.kt
package com.xgwnje.humanvehiclemonitor.composables

import android.graphics.RectF
import android.util.Log
import android.view.Surface // 用于获取屏幕旋转状态
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
import androidx.compose.ui.graphics.graphicsLayer // 新增导入: 用于图形层变换
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView // 用于获取当前视图从而获取显示旋转
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tensorflow.lite.task.vision.detector.Detection
import kotlin.math.roundToInt

@Composable
fun ResultsOverlay(
    results: List<Detection>?,
    imageHeight: Int, // 模型处理的（已旋转到垂直的）图像的高度
    imageWidth: Int,  // 模型处理的（已旋转到垂直的）图像的宽度
    personLabels: Set<String>,
    vehicleLabels: Set<String>,
    modifier: Modifier = Modifier
) {
    if (imageHeight == 0 || imageWidth == 0) {
        Log.w("ResultsOverlay(TFLite)", "图像高度或宽度为零，不绘制覆盖层。ImageH: $imageHeight, ImageW: $imageWidth") // 中文日志
        return
    }

    val view = LocalView.current
    val displayRotation = view.display.rotation
    // 判断是否为反向横屏
    val isReverseLandscape = displayRotation == Surface.ROTATION_270
    // Log.d("ResultsOverlay(TFLite)", "当前屏幕旋转: $displayRotation, isReverseLandscape: $isReverseLandscape") // 中文调试日志

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // BoxWithConstraints 根据当前方向提供可用的画布尺寸
        val canvasWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            Log.w("ResultsOverlay(TFLite)", "画布宽度或高度为零或负数。CanvasW: $canvasWidthPx, CanvasH: $canvasHeightPx") // 中文日志
            return@BoxWithConstraints
        }

        // 计算缩放比例和偏移量，以将 imageWidth x imageHeight 的区域适应并居中到画布上
        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val canvasAspectRatio = canvasWidthPx / canvasHeightPx

        val scale: Float
        val transformOffsetX: Float // 水平偏移，用于在画布上居中图像
        val transformOffsetY: Float // 垂直偏移，用于在画布上居中图像

        if (imageAspectRatio > canvasAspectRatio) { // 图像更宽 (letterbox)
            scale = canvasWidthPx / imageWidth.toFloat()
            transformOffsetX = 0f
            transformOffsetY = (canvasHeightPx - (imageHeight.toFloat() * scale)) / 2f
        } else { // 图像更高 (pillarbox)
            scale = canvasHeightPx / imageHeight.toFloat()
            transformOffsetY = 0f
            transformOffsetX = (canvasWidthPx - (imageWidth.toFloat() * scale)) / 2f
        }

        // 这个内部Box将包含所有绘制内容。
        // 如果是反向横屏，我们通过 graphicsLayer 将这个Box整体旋转180度。
        // 内部的坐标计算则始终按标准横屏处理。
        Box(
            modifier = Modifier
                .fillMaxSize() // 填充父级 BoxWithConstraints
                .graphicsLayer(
                    rotationZ = if (isReverseLandscape) 180f else 0f
                    // 旋转中心默认为 (0.5f, 0.5f) 即中心点，这对于翻转整个绘制区域是正确的。
                )
        ) {
            if (results != null) {
                results.forEachIndexed { index, detection ->
                    val uniqueKey = detection.boundingBox.hashCode() + index

                    key(uniqueKey) {
                        if (detection.categories.isNotEmpty()) {
                            val topCategory = detection.categories[0]
                            val normalizedLabel = topCategory.label.lowercase().trim()

                            val isPerson = personLabels.contains(normalizedLabel)
                            val isVehicle = vehicleLabels.any { vehicleLabel -> normalizedLabel.contains(vehicleLabel) }

                            if (isPerson || isVehicle) {
                                val boundingBox: RectF = detection.boundingBox

                                // --- 修改: 移除之前根据 isReverseLandscape 判断的 effectiveBoxLeft/Top 逻辑 ---
                                // 坐标直接从 boundingBox 获取，因为父 Box 的 graphicsLayer 会处理整体的180度旋转（如果需要）。
                                val boxLeftOnImage = boundingBox.left
                                val boxTopOnImage = boundingBox.top
                                val boxWidthOnImage = boundingBox.width()
                                val boxHeightOnImage = boundingBox.height()

                                // 将图像上的坐标映射到画布上（已考虑缩放和居中偏移）
                                val targetBoxLeftPx = boxLeftOnImage * scale + transformOffsetX
                                val targetBoxTopPx = boxTopOnImage * scale + transformOffsetY
                                val targetBoxWidthPx = boxWidthOnImage * scale
                                val targetBoxHeightPx = boxHeightOnImage * scale
                                /*
                                // 详细调试日志
                                Log.d("ResultsOverlay(TFLite)", "绘制对象: ${topCategory.label} " +
                                        "| isReverse(gfxLayer): $isReverseLandscape " +
                                        "| Orig TL: (${boundingBox.left}, ${boundingBox.top}), W: ${boxWidthOnImage}, H: ${boxHeightOnImage} " +
                                        "| Scale: $scale, tX: $transformOffsetX, tY: $transformOffsetY " +
                                        "| Canvas TL: (${targetBoxLeftPx.roundToInt()}, ${targetBoxTopPx.roundToInt()}) " +
                                        "| Canvas Dim: (${targetBoxWidthPx.roundToInt()}w x ${targetBoxHeightPx.roundToInt()}h)")
                                */

                                if (targetBoxWidthPx <= 0 || targetBoxHeightPx <= 0) {
                                    Log.w("ResultsOverlay(TFLite)", "计算得到的检测框尺寸无效 (<=0): W=$targetBoxWidthPx, H=$targetBoxHeightPx. 跳过绘制此框。") // 中文日志
                                } else {
                                    val boxWidthDp: Dp = with(LocalDensity.current) { targetBoxWidthPx.toDp() }
                                    val boxHeightDp: Dp = with(LocalDensity.current) { targetBoxHeightPx.toDp() }

                                    if (boxWidthDp > 0.dp && boxHeightDp > 0.dp) {
                                        // 绘制边界框
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

                                        // 绘制标签文本
                                        val labelText = "${topCategory.label} (${String.format("%.2f", topCategory.score)})"
                                        Box(
                                            modifier = Modifier
                                                .offset {
                                                    IntOffset(
                                                        targetBoxLeftPx.roundToInt(),
                                                        targetBoxTopPx.roundToInt() // 文本锚点与框的锚点一致
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
}