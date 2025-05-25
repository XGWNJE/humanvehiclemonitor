package com.xgwnje.humanvehiclemonitor.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import java.text.DecimalFormat
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsDialog(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    onDismiss: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整模型参数") }, // 文本：调整模型参数
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // 置信度阈值
                Text("置信度阈值: ${df.format(currentThreshold.floatValue)}") // 文本：置信度阈值
                Slider(
                    value = currentThreshold.floatValue,
                    onValueChange = { currentThreshold.floatValue = it },
                    valueRange = 0.1f..0.9f,
                    steps = 79
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 最大结果数
                Text("最大结果数: ${currentMaxResults.intValue}") // 文本：最大结果数
                Slider(
                    value = currentMaxResults.intValue.toFloat(),
                    onValueChange = { currentMaxResults.intValue = it.toInt() },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 推理代理
                Text("推理代理:") // 文本：推理代理
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_CPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_CPU
                    ) { Text("CPU") } // 文本：CPU
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_GPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_GPU
                    ) { Text("GPU") } // 文本：GPU
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 模型识别间隔
                Text("识别间隔 (ms): ${currentDetectionIntervalMillis.longValue}") // 文本：识别间隔 (ms)
                Slider(
                    value = currentDetectionIntervalMillis.longValue.toFloat(),
                    onValueChange = { currentDetectionIntervalMillis.longValue = it.roundToLong() },
                    valueRange = 0f..1000f,
                    steps = 99
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("注意: 更改参数后，模型会重新加载。", fontSize = 12.sp) // 文本：注意: 更改参数后，模型会重新加载。
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") } // 文本：关闭
        }
    )
}
