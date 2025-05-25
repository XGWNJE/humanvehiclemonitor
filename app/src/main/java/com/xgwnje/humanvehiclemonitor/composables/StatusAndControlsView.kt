package com.xgwnje.humanvehiclemonitor.composables

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
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
    mainScreenTag: String
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val df = remember { DecimalFormat("#.##") }

    val delegateString = if (currentDelegateValue == ObjectDetectorHelper.DELEGATE_CPU) "CPU" else "GPU"
    val intervalString = "${currentDetectionIntervalMillisValue}ms"

    val baseStatus = if (detectionError != null) "错误: $detectionError" else currentStatusText
    val inferenceDisplay = if (detectionError == null && isMonitoringActive) " (${inferenceTime}ms)" else ""
    val paramsDisplay = "\nT: ${df.format(currentThresholdValue)} M: $currentMaxResultsValue D: $delegateString I: $intervalString"
    val combinedStatusText = "$baseStatus$inferenceDisplay$paramsDisplay"


    val buttonMinHeight = 48.dp
    val previewButtonText = if (isPreviewEnabled) "关闭预览" else "开启预览"
    val previewButtonIcon = if (isPreviewEnabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility

    val monitorButtonText = if (isMonitoringActive) "停止监控" else "开始监控"
    val monitorButtonIcon = if (isMonitoringActive) Icons.Filled.Stop else Icons.Filled.PlayArrow


    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .weight(0.6f) // 给状态文本更多空间
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = combinedStatusText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f), // 略微增加不透明度
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .align(Alignment.BottomStart),
                    lineHeight = 12.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Bottom
            ) {
                FilledTonalButton(
                    onClick = onShowSettingsDialog,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "调整参数", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("调参", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onToggleMonitoring,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (isMonitoringActive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    Icon(monitorButtonIcon, contentDescription = monitorButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(monitorButtonText, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTogglePreview,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(previewButtonIcon, contentDescription = previewButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(previewButtonText, fontSize = 14.sp)
                }
            }
        }
    } else { // 竖屏 UI
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = combinedStatusText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f), // 略微增加不透明度
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp
            )

            Spacer(modifier = Modifier.weight(1f))

            FilledTonalButton(
                onClick = onShowSettingsDialog,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = buttonMinHeight),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "调整参数", modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("调整参数", fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePreview,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(previewButtonIcon, contentDescription = previewButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(previewButtonText, fontSize = 15.sp)
                }
                Button(
                    onClick = onToggleMonitoring,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(16.dp),
                    colors = if (isMonitoringActive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()
                ) {
                    Icon(monitorButtonIcon, contentDescription = monitorButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(monitorButtonText, fontSize = 15.sp)
                }
            }
        }
    }
}
