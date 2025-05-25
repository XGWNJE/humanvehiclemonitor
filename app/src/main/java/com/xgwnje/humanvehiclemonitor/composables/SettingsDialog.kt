package com.xgwnje.humanvehiclemonitor.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.humanvehiclemonitor.AlarmMode // 导入 AlarmMode
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import java.text.DecimalFormat
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    currentAlarmMode: AlarmMode,
    onAlarmModeChange: (AlarmMode) -> Unit,
    currentContinuousDetectionDurationMs: Long,
    onContinuousDetectionDurationChange: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整参数和报警设置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text("模型参数", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                Text("置信度阈值: ${df.format(currentThreshold.floatValue)}")
                Slider(
                    value = currentThreshold.floatValue,
                    onValueChange = { currentThreshold.floatValue = it },
                    valueRange = 0.1f..0.9f,
                    steps = 79 // ((0.9-0.1)/0.01) - 1 = 79
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("最大结果数: ${currentMaxResults.intValue}")
                Slider(
                    value = currentMaxResults.intValue.toFloat(),
                    onValueChange = { currentMaxResults.intValue = it.toInt() },
                    valueRange = 1f..10f,
                    steps = 8 // 10 - 1 - 1 = 8
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text("推理代理:")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_CPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_CPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("CPU") }
                    Button(
                        onClick = { currentDelegate.intValue = ObjectDetectorHelper.DELEGATE_GPU },
                        enabled = currentDelegate.intValue != ObjectDetectorHelper.DELEGATE_GPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("GPU") }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("模型识别间隔 (ms): ${currentDetectionIntervalMillis.longValue}")
                Slider(
                    value = currentDetectionIntervalMillis.longValue.toFloat(),
                    onValueChange = { currentDetectionIntervalMillis.longValue = it.roundToLong() },
                    valueRange = 0f..1000f, // 0ms (实时) 到 1000ms (1秒)
                    steps = ((1000f - 0f) / 50f).toInt() - 1 // 50ms 步长
                )
                Spacer(modifier = Modifier.height(24.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("报警设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

                Text("报警模式:")
                Column(Modifier.selectableGroup()) {
                    AlarmMode.values().forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .selectable(
                                    selected = (mode == currentAlarmMode),
                                    onClick = { onAlarmModeChange(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == currentAlarmMode),
                                onClick = null
                            )
                            Text(
                                text = when (mode) {
                                    AlarmMode.PERSON_ONLY -> "只报警人员"
                                    AlarmMode.VEHICLE_ONLY -> "只报警车辆"
                                    AlarmMode.PERSON_AND_VEHICLE -> "人员和车辆都报警"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text("持续识别触发报警 (ms): $currentContinuousDetectionDurationMs")
                Slider(
                    value = currentContinuousDetectionDurationMs.toFloat(),
                    onValueChange = { onContinuousDetectionDurationChange(it.roundToLong()) },
                    valueRange = 500f..10000f, // 0.5秒 到 10秒
                    steps = ((10000f - 500f) / 500f).toInt() - 1 // 500ms 步长
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("注意: 更改参数后，模型和报警逻辑会更新。", fontSize = 12.sp, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}
