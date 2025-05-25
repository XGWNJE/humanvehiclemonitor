package com.xgwnje.humanvehiclemonitor.composables

import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tensorflow.lite.task.vision.detector.Detection // TensorFlow Lite Detection
import kotlin.math.min

@Composable
fun ResultsOverlay(
    results: List<Detection>?, // 改为 List<Detection>? from TensorFlow Lite
    imageHeight: Int,      // 这是用于检测的（可能已旋转的）图像的像素高度
    imageWidth: Int,       // 这是用于检测的（可能已旋转的）图像的像素宽度
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
            Log.w("ResultsOverlay(TFLite)", "画布宽度或高度为零或负数，因此不执行任何绘制逻辑。CanvasW: $canvasWidthPx, CanvasH: $canvasHeightPx") // 中文日志
            return@BoxWithConstraints
        }

        // 针对 FIT_CENTER 的核心修改 (与之前类似，但确保与 TFLite 的坐标系兼容)
        val scaleX = canvasWidthPx / imageWidth.toFloat()
        val scaleY = canvasHeightPx / imageHeight.toFloat()
        val scale = min(scaleX, scaleY)

        val scaledImageWidth = imageWidth * scale
        val scaledImageHeight = imageHeight * scale

        val transformOffsetX = (canvasWidthPx - scaledImageWidth) / 2f
        val transformOffsetY = (canvasHeightPx - scaledImageHeight) / 2f

        if (results != null && results.isNotEmpty()) {
            // Log.d("ResultsOverlay(TFLite)", "检测到 ${results.size} 个对象。") // 中文日志

            results.forEach { detection ->
                // TensorFlow Lite Task Vision 的 BoundingBox 是 RectF
                // RectF 包含 left, top, right, bottom 坐标
                val boundingBox: RectF = detection.boundingBox

                // 将 TFLite 的 RectF 坐标转换为 Compose 的左上角和宽高
                val boxLeftPx = boundingBox.left * scale + transformOffsetX
                val boxTopPx = boundingBox.top * scale + transformOffsetY
                val boxWidthPx = boundingBox.width() * scale // RectF.width()
                val boxHeightPx = boundingBox.height() * scale // RectF.height()

                if (boxWidthPx <= 0 || boxHeightPx <= 0) {
                    Log.w("ResultsOverlay(TFLite)", "计算得到的检测框尺寸无效 (<=0): W=$boxWidthPx, H=$boxHeightPx. 跳过绘制此框。") // 中文日志
                    return@forEach
                }

                val boxLeftDp = with(LocalDensity.current) { boxLeftPx.toDp() }
                val boxTopDp = with(LocalDensity.current) { boxTopPx.toDp() }
                val boxWidthDp = with(LocalDensity.current) { boxWidthPx.toDp() }
                val boxHeightDp = with(LocalDensity.current) { boxHeightPx.toDp() }

                if (boxWidthDp <= 0.dp || boxHeightDp <= 0.dp) {
                    Log.w("ResultsOverlay(TFLite)", "计算得到的检测框DP尺寸无效 (<=0): W=$boxWidthDp, H=$boxHeightDp. 跳过绘制此框。") // 中文日志
                    return@forEach
                }

                Box(
                    modifier = Modifier
                        .offset(x = boxLeftDp, y = boxTopDp)
                        .width(boxWidthDp)
                        .height(boxHeightDp)
                        .border(2.dp, Color.Green) // 将颜色改为绿色以便与之前的 MediaPipe 区分
                )

                // TensorFlow Lite Detection 中的 Category 包含 label 和 score
                if (detection.categories.isNotEmpty()) {
                    val topCategory = detection.categories[0] // 通常第一个是最佳匹配
                    val labelText = "${topCategory.label} (${String.format("%.2f", topCategory.score)})"
                    // Log.d("ResultsOverlay(TFLite)", "标签: $labelText, 位置: $boxLeftDp, $boxTopDp")

                    Box(
                        modifier = Modifier
                            .offset(x = boxLeftDp, y = boxTopDp)
                            .background(Color.Green.copy(alpha = 0.6f)) // 同样改为绿色
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
        } else {
            // Log.d("ResultsOverlay(TFLite)", "结果为 null 或为空，不绘制检测框。") // 中文日志
        }
    }
}
