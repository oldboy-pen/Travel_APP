package com.example.myfirstapp.ui.components

import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 完整 HSV 取色器弹窗：
 * - 上方方形 SV 色域（横=饱和度，纵=明度，随当前色相变化），拖动选点；
 * - 下方色相条（0°~360° 彩虹），拖动选色相；
 * - 右上角实时预览色块 + 十六进制色值；
 * - 「恢复默认」回到 [defaultColor]，确认/取消由 [onConfirm]/[onDismiss] 处理。
 *
 * 颜色一律用 ARGB Int（与 MapEngine.addPolyline 一致），内部转 HSV 供拖拽。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    defaultColor: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // initial → HSV
    val hsv = remember { FloatArray(3).also { AColor.colorToHSV(initialColor, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var alpha by remember { mutableFloatStateOf((initialColor ushr 24) / 255f) }

    val current = AColor.HSVToColor(floatArrayOf(hue, sat, value)).let { rgb ->
        (alpha.roundToInt() shl 24) or (rgb and 0x00FFFFFF)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // ---- SV 色域 ----
                val hueColor = Color(AColor.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.small)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p -> sat = (p.x / size.width).coerceIn(0f, 1f)
                                    value = 1f - (p.y / size.height).coerceIn(0f, 1f) },
                                onDrag = { c, _ -> sat = (c.position.x / size.width).coerceIn(0f, 1f)
                                    value = 1f - (c.position.y / size.height).coerceIn(0f, 1f) }
                            )
                        }
                ) {
                    // 底：白 → 当前色相的全饱和色；再叠 透明 → 黑
                    drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    // 游标：白圈黑边，位置 (sat, 1-value)
                    val cx = size.width * sat
                    val cy = size.height * (1f - value)
                    drawCircle(Color.White, radius = 9f, center = Offset(cx, cy))
                    drawCircle(Color.Black, radius = 9f, center = Offset(cx, cy), style = Stroke(2.5f))
                }

                Spacer(Modifier.height(12.dp))

                // ---- 色相条 ----
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(MaterialTheme.shapes.small)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p -> hue = (p.x / size.width).coerceIn(0f, 1f) * 360f },
                                onDrag = { c, _ -> hue = (c.position.x / size.width).coerceIn(0f, 1f) * 360f }
                            )
                        }
                ) {
                    val hues = (0..360 step 30).map { Color(AColor.HSVToColor(floatArrayOf(it.toFloat(), 1f, 1f))) }
                    drawRect(Brush.horizontalGradient(hues))
                    // 游标
                    val cx = size.width * (hue / 360f)
                    drawRect(
                        Color.White,
                        topLeft = Offset(cx - 3f, 0f),
                        size = androidx.compose.ui.geometry.Size(6f, size.height)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- 透明度条（轨迹线常不透明，但保留完整取色能力）----
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(MaterialTheme.shapes.small)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { p -> alpha = (p.x / size.width).coerceIn(0f, 1f) },
                                onDrag = { c, _ -> alpha = (c.position.x / size.width).coerceIn(0f, 1f) }
                            )
                        }
                ) {
                    val c = Color(AColor.HSVToColor(floatArrayOf(hue, sat, value)))
                    drawRect(Brush.horizontalGradient(listOf(c.copy(alpha = 0f), c)))
                    val cx = size.width * alpha
                    drawRect(
                        Color.White,
                        topLeft = Offset(cx - 3f, 0f),
                        size = androidx.compose.ui.geometry.Size(6f, size.height)
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ---- 预览 + 色值 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = Color(current),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    ) {}
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "#%08X".format(current),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "色相 ${hue.roundToInt()}° · 饱和度 ${(sat * 100).roundToInt()}% · 明度 ${(value * 100).roundToInt()}%",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(current) }) { Text("确定") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(defaultColor) }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
