package com.example.myfirstapp.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手机物理指北针：跟随手机转动，刻度盘上的「北」始终指向真实北方
 * （与地图是否旋转无关——这是和 SDK 自带指北针的本质区别）。
 *
 * 数据源：TYPE_ROTATION_VECTOR（加速度+磁力计+陀螺仪的融合传感器，
 * 抖动小）；无该传感器的旧机型回退 加速度计+磁力计 手动合成。
 * 方位角经环形低通滤波防抖（同时处理 0°/360° 跳变）。
 */
@Composable
fun PhoneCompass(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val context = LocalContext.current
    var azimuth by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magSensor = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        var filtered = Float.NaN
        fun update(rawDeg: Float) {
            val deg = (rawDeg + 360f) % 360f
            filtered = if (filtered.isNaN()) deg else {
                // 环形低通滤波：delta 取最短弧，避免 359°→1° 被平滑成绕一整圈
                var delta = deg - filtered
                while (delta > 180f) delta -= 360f
                while (delta < -180f) delta += 360f
                (filtered + delta * 0.15f + 360f) % 360f
            }
            azimuth = filtered
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var gravity: FloatArray? = null
        var geomag: FloatArray? = null

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        update(Math.toDegrees(orientation[0].toDouble()).toFloat())
                    }
                    Sensor.TYPE_ACCELEROMETER -> gravity = e.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> geomag = e.values.clone()
                }
                // 回退路径：无融合传感器时用 加速度+磁力 合成旋转矩阵
                if (rotationSensor == null) {
                    val g = gravity
                    val m = geomag
                    if (g != null && m != null &&
                        SensorManager.getRotationMatrix(rotationMatrix, null, g, m)
                    ) {
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        update(Math.toDegrees(orientation[0].toDouble()).toFloat())
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // SENSOR_DELAY_UI（约 60ms）：罗盘刷新足够流畅，又不至于费电
        if (rotationSensor != null) {
            sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
            magSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        }
        onDispose { sm.unregisterListener(listener) }
    }

    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = w / 2f
        val r = w / 2f

        // ---- 底盘：白底 + 深色描边（浮在地图上保证可读） ----
        drawCircle(Color.White.copy(alpha = 0.92f), r)
        drawCircle(Color(0xFF555555), r * 0.98f, style = Stroke(width = r * 0.045f))

        // ---- 刻度盘：整体反向旋转 -azimuth →「北」刻度始终指向真实北方 ----
        val native = drawContext.canvas.nativeCanvas
        val checkpoint = native.save()
        native.rotate(-azimuth, c, c)

        // 每 30° 一根小刻度线
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30).toDouble())
            val outer = r * 0.90f
            val inner = r * (if (i % 3 == 0) 0.72f else 0.80f) // 四正向刻度稍长
            drawLine(
                color = Color(0xFF888888),
                start = Offset(
                    c + (inner * Math.sin(angle)).toFloat(),
                    c - (inner * Math.cos(angle)).toFloat()
                ),
                end = Offset(
                    c + (outer * Math.sin(angle)).toFloat(),
                    c - (outer * Math.cos(angle)).toFloat()
                ),
                strokeWidth = r * 0.03f
            )
        }

        // 四正向汉字：北红、其余深灰（画在刻度盘内圈，随盘一起转）
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = r * 0.42f
            textAlign = android.graphics.Paint.Align.CENTER
            isFakeBoldText = true
        }
        val labels = arrayOf("北", "东", "南", "西")
        labels.forEachIndexed { i, label ->
            val angle = Math.toRadians((i * 90).toDouble())
            val lr = r * 0.50f
            val x = c + (lr * Math.sin(angle)).toFloat()
            val y = c - (lr * Math.cos(angle)).toFloat()
            textPaint.color = if (i == 0) 0xFFD32F2F.toInt() else 0xFF333333.toInt()
            // drawText 的 y 是基线，加半个字高居中
            native.drawText(label, x, y + textPaint.textSize * 0.38f, textPaint)
        }
        native.restoreToCount(checkpoint)

        // ---- 固定不动部分：顶部红色三角（指示手机正前方）+ 中心铆钉 ----
        val tri = Path().apply {
            moveTo(c, r * 0.02f)
            lineTo(c + r * 0.13f, r * 0.24f)
            lineTo(c - r * 0.13f, r * 0.24f)
            close()
        }
        drawPath(tri, Color(0xFFD32F2F))
        drawCircle(Color(0xFF555555), r * 0.07f)
    }
}
