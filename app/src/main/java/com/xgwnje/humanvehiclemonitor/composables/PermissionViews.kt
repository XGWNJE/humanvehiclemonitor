package com.xgwnje.humanvehiclemonitor.composables

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState

@Composable
fun CameraPermissionRationaleDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要相机权限") }, // 文本：需要相机权限
        text = { Text("此应用需要相机访问权限以检测人和车辆。请授予权限。") }, // 文本：此应用需要相机访问权限以检测人和车辆。请授予权限。
        confirmButton = {
            Button(onClick = onConfirm) { Text("授予") } // 文本：授予
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("拒绝") } // 文本：拒绝
        }
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionDeniedContent(cameraPermissionState: PermissionState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "相机权限被拒绝。请在设置中启用。", // 文本：相机权限被拒绝。请在设置中启用。
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(onClick = {
            cameraPermissionState.launchPermissionRequest()
        }) { Text("再次请求权限") } // 文本：再次请求权限
    }
}
