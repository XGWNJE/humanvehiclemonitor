package com.xgwnje.humanvehiclemonitor.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
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
import com.xgwnje.humanvehiclemonitor.AlarmMode
import com.xgwnje.humanvehiclemonitor.objectdetector.ObjectDetectorHelper
import java.text.DecimalFormat
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentThreshold: MutableFloatState,
    currentMaxResults: MutableIntState,
    currentDelegate: MutableIntState,
    currentDetectionIntervalMillis: MutableLongState,
    currentAlarmMode: AlarmMode,
    onAlarmModeChange: (AlarmMode) -> Unit,
    continuousDetectionDurationMsState: MutableLongState,
    onNavigateBack: () -> Unit
) {
    val df = remember { DecimalFormat("#.##") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调整参数和报警设置") }, // 文本：调整参数和报警设置
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回" // 文本：返回
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Row( // 使用 Row 实现左右分栏
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 8.dp, vertical = 8.dp) // 稍微减少水平内边距，因为列内部会有
        ) {
            // 左侧栏：模型参数
            Column(
                modifier = Modifier
                    .weight(1f) //占据一半宽度
                    .padding(horizontal = 8.dp) // 列的内边距
                    .verticalScroll(rememberScrollState())
            ) {
                Text("模型参数", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp)) // 文本：模型参数

                SettingSliderItem(
                    label = "置信度阈值: ${df.format(currentThreshold.value)}", // 文本：置信度阈值
                    value = currentThreshold.value,
                    onValueChange = { currentThreshold.value = it },
                    valueRange = 0.1f..0.9f,
                    steps = 79
                )

                SettingSliderItem(
                    label = "最大结果数: ${currentMaxResults.value}", // 文本：最大结果数
                    value = currentMaxResults.value.toFloat(),
                    onValueChange = { currentMaxResults.value = it.toInt() },
                    valueRange = 1f..10f,
                    steps = 8
                )

                SettingSliderItem(
                    label = "模型识别间隔 (ms): ${currentDetectionIntervalMillis.value}", // 文本：模型识别间隔 (ms)
                    value = currentDetectionIntervalMillis.value.toFloat(),
                    onValueChange = { currentDetectionIntervalMillis.value = it.roundToLong() },
                    valueRange = 0f..1000f,
                    steps = ((1000f - 0f) / 50f).toInt() - 1
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("推理代理:", style = MaterialTheme.typography.titleMedium) // 文本：推理代理
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), // 按钮间距
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_CPU },
                        enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_CPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("CPU") }
                    Button(
                        onClick = { currentDelegate.value = ObjectDetectorHelper.DELEGATE_GPU },
                        enabled = currentDelegate.value != ObjectDetectorHelper.DELEGATE_GPU,
                        modifier = Modifier.weight(1f)
                    ) { Text("GPU") }
                }
                Spacer(modifier = Modifier.height(16.dp)) // 在模型参数部分的底部添加一些间距
            }

            // 分隔线 (可选，用于视觉上区分两栏)
            // Divider(modifier = Modifier.fillMaxHeight().width(1.dp).padding(vertical = 8.dp))

            // 右侧栏：报警设置
            Column(
                modifier = Modifier
                    .weight(1f) //占据一半宽度
                    .padding(horizontal = 8.dp) // 列的内边距
                    .verticalScroll(rememberScrollState())
            ) {
                Text("报警设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp)) // 文本：报警设置

                Text("报警模式:", style = MaterialTheme.typography.titleMedium) // 文本：报警模式
                Column(Modifier.selectableGroup()) {
                    AlarmMode.values().forEach { mode ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = (mode == currentAlarmMode),
                                    onClick = { onAlarmModeChange(mode) },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (mode == currentAlarmMode),
                                onClick = null
                            )
                            Text(
                                text = when (mode) {
                                    AlarmMode.PERSON_ONLY -> "只报警人员" // 文本：只报警人员
                                    AlarmMode.VEHICLE_ONLY -> "只报警车辆" // 文本：只报警车辆
                                    AlarmMode.PERSON_AND_VEHICLE -> "人员和车辆都报警" // 文本：人员和车辆都报警
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                SettingSliderItem(
                    label = "持续识别触发报警 (ms): ${continuousDetectionDurationMsState.value}", // 文本：持续识别触发报警 (ms)
                    value = continuousDetectionDurationMsState.value.toFloat(),
                    onValueChange = { continuousDetectionDurationMsState.value = it.roundToLong() },
                    valueRange = 500f..10000f,
                    steps = ((10000f - 500f) / 500f).toInt() - 1
                )
                Spacer(modifier = Modifier.height(16.dp)) // 在报警设置部分的底部添加一些间距
            }
        }
        // "注意"文本可以放在底部，横跨两栏，或者在各自栏的末尾。
        // 如果要横跨两栏，需要将Row的外部再包一层Column，并将此Text放在Row之后。
        // 为简单起见，暂时不移动它，但您可以根据需要调整。
        // Text(
        // "注意: 更改参数后，模型和报警逻辑会更新。",
        // fontSize = 12.sp,
        // style = MaterialTheme.typography.labelSmall,
        // modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter) // 示例：如果想放底部
        // )
    }
}

@Composable
private fun SettingSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium) // 标签使用 titleMedium 可能更合适
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
