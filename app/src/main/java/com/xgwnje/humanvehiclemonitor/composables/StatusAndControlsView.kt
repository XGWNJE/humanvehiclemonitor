package com.xgwnje.humanvehiclemonitor.composables

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import java.text.DecimalFormat

@Composable
fun StatusAndControlsView(
    detectionError: String?,
    currentStatusText: String,
    inferenceTime: Long,
    currentThresholdValue: Float,
    currentMaxResultsValue: Int,
    currentDelegateValue: Int,
    currentDetectionIntervalMillisValue: Long,
    isPreviewEnabled: Boolean,
    isMonitoringActive: Boolean,
    onTogglePreview: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onShowSettingsDialog: () -> Unit,
    mainScreenTag: String,
    modifier: Modifier = Modifier // 修改点: 添加 modifier 参数
) {
    val df = remember { DecimalFormat("#.##") }
    val statusAndControlsViewTag = "StatusAndControlsView(TFLite)"

    Log.d(statusAndControlsViewTag, "StatusAndControlsView: Composable recomposing. Always landscape. isPreviewEnabled: $isPreviewEnabled, isMonitoringActive: $isMonitoringActive") // 中文日志

    val delegateString = if (currentDelegateValue == ObjectDetectorHelper.DELEGATE_CPU) "CPU" else "GPU"
    val intervalString = "${currentDetectionIntervalMillisValue}ms"

    val baseStatus = if (detectionError != null) {
        Log.w(statusAndControlsViewTag, "显示错误状态: $detectionError") // 中文日志
        "错误: $detectionError"
    } else {
        currentStatusText
    }
    val inferenceDisplay = if (detectionError == null && (isMonitoringActive || inferenceTime > 0) ) " (${inferenceTime}ms)" else ""
    val paramsDisplay = "\nT: ${df.format(currentThresholdValue)} M: $currentMaxResultsValue D: $delegateString I: $intervalString"
    val combinedStatusText = "$baseStatus$inferenceDisplay$paramsDisplay"

    val buttonMinHeight = 48.dp
    val previewButtonText = if (isPreviewEnabled) "关闭预览" else "开启预览" // 中文按钮文本
    val previewButtonIcon = if (isPreviewEnabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility

    val monitorButtonText = if (isMonitoringActive) "停止监控" else "开始监控" // 中文按钮文本
    val monitorButtonIcon = if (isMonitoringActive) Icons.Filled.Stop else Icons.Filled.PlayArrow

    Row(
        modifier = modifier // 使用传入的 modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = combinedStatusText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .align(Alignment.BottomStart),
                lineHeight = 12.sp
            )
        }

        Column(
            modifier = Modifier.wrapContentWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
        ) {
            val landscapeButtonModifier = Modifier
                .widthIn(min = 130.dp, max = 180.dp)
                .defaultMinSize(minHeight = buttonMinHeight)

            FilledTonalButton(
                onClick = {
                    Log.d(statusAndControlsViewTag, "设置按钮被点击。") // 中文日志
                    onShowSettingsDialog()
                },
                modifier = landscapeButtonModifier,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "调整参数", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("调参", fontSize = 14.sp) // 中文按钮文本
            }

            Button(
                onClick = {
                    Log.d(statusAndControlsViewTag, "切换监控按钮被点击。当前状态: $isMonitoringActive -> ${!isMonitoringActive}") // 中文日志
                    onToggleMonitoring()
                },
                modifier = landscapeButtonModifier,
                shape = RoundedCornerShape(12.dp),
                colors = if (isMonitoringActive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(monitorButtonIcon, contentDescription = monitorButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(monitorButtonText, fontSize = 14.sp)
            }

            OutlinedButton(
                onClick = {
                    Log.d(statusAndControlsViewTag, "切换预览按钮被点击。当前状态: $isPreviewEnabled -> ${!isPreviewEnabled}") // 中文日志
                    onTogglePreview()
                },
                modifier = landscapeButtonModifier,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(previewButtonIcon, contentDescription = previewButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(previewButtonText, fontSize = 14.sp)
            }
        }
    }
}