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
    isPreviewEnabled: Boolean, // 新增：接收预览状态
    onTogglePreview: () -> Unit, // 新增：切换预览的回调
    onShowSettingsDialog: () -> Unit,
    mainScreenTag: String
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val df = remember { DecimalFormat("#.##") }

    val delegateString = if (currentDelegateValue == ObjectDetectorHelper.DELEGATE_CPU) "CPU" else "GPU"
    val intervalString = "${currentDetectionIntervalMillisValue}ms"
    val combinedStatusText = if (detectionError != null) "错误: $detectionError"
    else "$currentStatusText (${inferenceTime}ms)\n" +
            "T: ${df.format(currentThresholdValue)} M: $currentMaxResultsValue D: $delegateString I: $intervalString"

    val buttonMinHeight = 48.dp
    val previewButtonText = if (isPreviewEnabled) "关闭预览" else "开启预览"
    val previewButtonIcon = if (isPreviewEnabled) Icons.Filled.VisibilityOff else Icons.Filled.Visibility


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
                    .weight(0.6f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = combinedStatusText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
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
                    onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控 (TFLite)") },
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "开始监控", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("开始监控", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onTogglePreview, // 绑定切换预览逻辑
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
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
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
                    onClick = onTogglePreview, // 绑定切换预览逻辑
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(previewButtonIcon, contentDescription = previewButtonText, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(previewButtonText, fontSize = 15.sp)
                }
                Button(
                    onClick = { Log.d(mainScreenTag, "按钮点击: 开始监控 (TFLite)") },
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = buttonMinHeight),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "开始监控", modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("开始监控", fontSize = 15.sp)
                }
            }
        }
    }
}
