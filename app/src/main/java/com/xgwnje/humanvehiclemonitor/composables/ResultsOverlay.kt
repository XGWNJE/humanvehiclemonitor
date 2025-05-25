package com.xgwnje.humanvehiclemonitor.composables

import android.annotation.SuppressLint
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
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectionResult
import kotlin.math.min // 导入 kotlin.math.min

@SuppressLint("UnusedBoxWithConstraintsScope") // 抑制 BoxWithConstraints 的 lint 警告
@Suppress("DEPRECATION") // 抑制 ObjectDetectionResult 的弃用警告
@Composable
fun ResultsOverlay(
    results: ObjectDetectionResult?,
    imageHeight: Int, // 这是用于检测的（可能已旋转的）图像的像素高度
    imageWidth: Int,  // 这是用于检测的（可能已旋转的）图像的像素宽度
    modifier: Modifier = Modifier
) {
    // 函数级别的早期返回，这是允许的
    if (imageHeight == 0 || imageWidth == 0) {
        Log.w("ResultsOverlay", "图像高度或宽度为零，不绘制覆盖层。ImageH: $imageHeight, ImageW: $imageWidth") // 中文日志
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canvasWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val canvasHeightPx = with(LocalDensity.current) { maxHeight.toPx() }

        // 只有当画布尺寸有效时才继续绘制
        if (canvasWidthPx > 0 && canvasHeightPx > 0) {
            // *** 针对 FIT_CENTER 的核心修改开始 ***
            val scaleX = canvasWidthPx / imageWidth.toFloat()
            val scaleY = canvasHeightPx / imageHeight.toFloat()
            val scale = min(scaleX, scaleY) // 取较小的缩放比例以适应边界 (FIT_CENTER)

            val scaledImageWidth = imageWidth * scale
            val scaledImageHeight = imageHeight * scale

            val transformOffsetX = (canvasWidthPx - scaledImageWidth) / 2f
            val transformOffsetY = (canvasHeightPx - scaledImageHeight) / 2f
            // *** 针对 FIT_CENTER 的核心修改结束 ***

            // 只有当检测结果有效时才绘制检测框
            if (results != null) {
                val detections = results.detections()
                if (!detections.isNullOrEmpty()) {
                    // Log.d("ResultsOverlay", "检测到 ${detections.size} 个对象。") // 中文日志：检测到 N 个对象

                    detections.forEachIndexed { _, detection -> // index 未使用，可以用 _ 替代
                        val boundingBox = detection.boundingBox() // BoundingBox 的坐标是相对于原始 imageWidth, imageHeight 的像素值

                        // *** IDE 报错修复：使用 .left 和 .top 属性 ***
                        val boxLeftPx = boundingBox.left * scale + transformOffsetX
                        val boxTopPx = boundingBox.top * scale + transformOffsetY
                        // width() 和 height() 是方法调用，保持不变
                        val boxWidthPx = boundingBox.width() * scale
                        val boxHeightPx = boundingBox.height() * scale

                        if (boxWidthPx <= 0 || boxHeightPx <= 0) {
                            Log.w("ResultsOverlay", "计算得到的检测框尺寸无效 (<=0): W=$boxWidthPx, H=$boxHeightPx. 跳过绘制此框。") // 中文日志
                            return@forEachIndexed // 这是 forEachIndexed lambda 的正确返回方式
                        }

                        val boxLeftDp = with(LocalDensity.current) { boxLeftPx.toDp() }
                        val boxTopDp = with(LocalDensity.current) { boxTopPx.toDp() }
                        val boxWidthDp = with(LocalDensity.current) { boxWidthPx.toDp() }
                        val boxHeightDp = with(LocalDensity.current) { boxHeightPx.toDp() }

                        if (boxWidthDp <= 0.dp || boxHeightDp <= 0.dp) {
                            Log.w("ResultsOverlay", "计算得到的检测框DP尺寸无效 (<=0): W=$boxWidthDp, H=$boxHeightDp. 跳过绘制此框。") // 中文日志
                            return@forEachIndexed // 这是 forEachIndexed lambda 的正确返回方式
                        }

                        Box(
                            modifier = Modifier
                                .offset(x = boxLeftDp, y = boxTopDp)
                                .width(boxWidthDp)
                                .height(boxHeightDp)
                                .border(2.dp, Color.Red) // 将颜色改为红色以便区分测试
                        )

                        val categories = detection.categories()
                        if (categories.isNotEmpty()) {
                            val topCategory = categories[0]
                            val labelText = "${topCategory.categoryName()} (${String.format("%.2f", topCategory.score())})"
                            Box(
                                modifier = Modifier
                                    .offset(x = boxLeftDp, y = boxTopDp) // 标签位置与检测框左上角对齐
                                    .background(Color.Red.copy(alpha = 0.6f)) // 将颜色改为红色以便区分测试
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
                    // Log.d("ResultsOverlay", "Detections list is null or empty.") // 中文日志
                }
            } else {
                // Log.d("ResultsOverlay", "结果为 null，不绘制检测框。") // 中文日志
            }
        } else {
            Log.w("ResultsOverlay", "画布宽度或高度为零或负数，因此不执行任何绘制逻辑。CanvasW: $canvasWidthPx, CanvasH: $canvasHeightPx") // 中文日志
        }
    }
}
