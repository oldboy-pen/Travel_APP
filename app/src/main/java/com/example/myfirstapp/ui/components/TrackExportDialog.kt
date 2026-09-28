package com.example.myfirstapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myfirstapp.track.TrackFileFormat

/**
 * 轨迹导出弹窗：先选格式（GPX / KML 轨迹 / KML 路径 / KMZ），
 * 再选动作（分享 / 另存到指定位置）。
 * 轨迹库页与轨迹详情页共用。
 */
@Composable
fun TrackExportDialog(
    trackName: String,
    onDismiss: () -> Unit,
    onShare: (TrackFileFormat) -> Unit,
    onSaveAs: (TrackFileFormat) -> Unit
) {
    var format by remember { mutableStateOf(TrackFileFormat.GPX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出「$trackName」") },
        text = {
            Column {
                TrackFileFormat.values().forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = format == f, onClick = { format = f })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Spacer(Modifier.width(4.dp))
                        Text(f.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onShare(format) }) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享文件")
                    }
                    TextButton(onClick = { onSaveAs(format) }) {
                        Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("另存到指定位置")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
