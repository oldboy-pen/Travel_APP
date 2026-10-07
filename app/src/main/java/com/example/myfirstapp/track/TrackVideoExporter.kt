package com.example.myfirstapp.track

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.text.TextPaint
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ============ 轨迹 3D 运动视频导出 ============
 *
 * 原理（类似两步路「运动视频」）：
 * 1. 复用轨迹详情页的池化地图实例，镜头以 3D 倾斜视角（tilt 58°、朝向跟随行进方向）
 *    沿轨迹"飞行"，地图上叠加轨迹线 + 移动光点；
 * 2. 逐帧 TextureView.getBitmap() 抓取地图画面，用软件 Canvas 把运动数据字幕
 *    （距离/用时/速度/海拔 + 进度条 + 轨迹名）直接绘制到帧位图上；
 * 3. 编码线程用 EGL 把帧位图作为纹理绘制到 MediaCodec 的输入 Surface，
 *    H.264 硬编码 + MediaMuxer 封装为 MP4；PTS 手动赋值，时长精确可控。
 *
 * 时长按轨迹长度自动：每公里约 12 秒，夹在 15~60 秒之间。
 * 生成过程为实时回放（24fps 采集），期间地图页需保持在前台。
 */
class TrackVideoExporter(private val mapView: TextureMapView, private val track: Track) {

    private val aMap: AMap = mapView.map
    private val pts = track.points

    /** 累计里程（米）：cum[i] = 起点到第 i 个点的距离 */
    private val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) {
            c[i] = c[i - 1] + GeoUtils.distance(
                pts[i - 1].latitude, pts[i - 1].longitude,
                pts[i].latitude, pts[i].longitude
            )
        }
    }
    private val total = cum.lastOrNull() ?: 0.0

    /** 所有点都带时间才展示真实回放用时，否则按里程比例折算 */
    private val hasTime = pts.isNotEmpty() && pts.all { it.time > 0 }

    private val durationSec = (total / 1000.0 * 12.0).coerceIn(15.0, 60.0)
    private val fps = 24
    private val totalFrames = (durationSec * fps).roundToInt()

    /** 飞行高度（缩放级别）按轨迹尺度分档 */
    private val zoom = when {
        total < 3_000 -> 17f
        total < 15_000 -> 15.5f
        total < 60_000 -> 14f
        else -> 12.5f
    }

    private class Sample(
        val lat: Double, val lng: Double,
        val time: Long, val alt: Double,
        val speedKmh: Double
    )

    /** 平滑后的镜头关键帧（与帧号 0..totalFrames 对齐）：位置 + 朝向 */
    private class CamFrame(val lat: Double, val lng: Double, val bearing: Float)

    /**
     * 生成 3D 运动视频。
     *
     * @param outFile 输出 MP4 路径（调用方保证目录存在）
     * @param onProgress 进度回调 0~1（主线程）
     * @param restore 生成结束（含失败/取消）后恢复详情页地图原状的回调（主线程执行）
     */
    suspend fun export(outFile: File, onProgress: (Float) -> Unit, restore: () -> Unit): File {
        require(pts.size >= 2) { "轨迹点太少，无法生成视频" }
        require(total > 10.0) { "轨迹距离太短，无法生成视频" }

        // 镜头关键帧预计算：位置 + 朝向双重平滑（防摇晃），逐帧直接取用
        val cam = buildCamFrames()

        val texView = findTextureView(mapView) ?: error("地图渲染视图不可用")
        val srcW = texView.width
        val srcH = texView.height
        require(srcW > 0 && srcH > 0) { "地图尚未就绪，请稍后重试" }

        // 视频尺寸：宽固定 1280，高按采集画面比例并 16 对齐（部分硬编码器要求）
        val videoW = 1280
        val videoH = ((videoW * srcH / srcW / 16f).roundToInt().coerceIn(16, 80)) * 16

        outFile.parentFile?.mkdirs()
        if (outFile.exists()) outFile.delete()

        val headerTitle = track.name.ifBlank { "轨迹" }
        val headerSub = buildString {
            append(track.activityType.label)
            if (track.startTime > 0) {
                append(" · ").append(
                    SimpleDateFormat("yyyy/MM/dd", Locale.CHINA).format(Date(track.startTime))
                )
            }
        }
        val painter = OverlayPainter(srcW, srcH, headerTitle, headerSub)
        val encoder = VideoEncodeThread(videoW, videoH, fps, outFile)
        encoder.start()

        try {
            withContext(Dispatchers.Main) {
                aMap.uiSettings.setAllGesturesEnabled(false)
                aMap.clear()

                // 视频专用覆盖物：白边+绿芯轨迹线、起终点、途经点、移动光点
                val latLngs = pts.map { LatLng(it.latitude, it.longitude) }
                aMap.addPolyline(PolylineOptions().addAll(latLngs).width(22f).color(0xE6FFFFFF.toInt()))
                aMap.addPolyline(
                    PolylineOptions().addAll(latLngs).width(11f).color(0xFF00C853.toInt()).zIndex(1f)
                )
                aMap.addMarker(MarkerOptions().position(latLngs.first()).title("起点"))
                aMap.addMarker(MarkerOptions().position(latLngs.last()).title("终点"))
                track.waypoints.forEach { w ->
                    aMap.addMarker(MarkerOptions().position(LatLng(w.latitude, w.longitude)).title(w.name))
                }
                val movingMarker = aMap.addMarker(
                    MarkerOptions().position(latLngs.first())
                        .icon(BitmapDescriptorFactory.fromBitmap(makeDotBitmap()))
                        .anchor(0.5f, 0.5f)
                        .zIndex(10f)
                )

                // 预热：镜头飞到起点姿态，等待首帧瓦片渲染
                val c0 = cam[0]
                aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPos(c0)))
                movingMarker.position = LatLng(c0.lat, c0.lng)
                // 相机实况：确认 tilt/bearing 实际生效值（高德 tilt 上限 45°，超限会被丢弃）
                Log.d(
                    VTAG,
                    "warmup camera=${aMap.cameraPosition} (asked tilt=$TILT bearing=${c0.bearing})"
                )
                val warmupDeadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < warmupDeadline) {
                    val probe = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888)
                    val ready = texView.getBitmap(probe) != null
                    probe.recycle()
                    if (ready) break
                    delay(100)
                }
                delay(800) // 瓦片渲染余量

                // 逐帧：先采集当前画面（镜头停在位置 i），再移动镜头到位置 i+1
                val frameNs = 1_000_000_000L / fps
                val startNs = System.nanoTime()
                val pool = BitmapPool(srcW, srcH)
                for (i in 0..totalFrames) {
                    encoder.error?.let { throw it }
                    val targetNs = startNs + i * frameNs
                    val waitMs = (targetNs - System.nanoTime()) / 1_000_000
                    if (waitMs > 0) delay(waitMs)

                    val d = total * ease(i.toDouble() / totalFrames)
                    val s = sampleAt(d)
                    val elapsedMs = if (hasTime) s.time - pts.first().time
                    else (track.durationMillis * d / total).toLong()

                    val bmp = pool.obtain()
                    if (texView.getBitmap(bmp) != null) {
                        // 字幕直接绘制到帧位图上（软件 Canvas，毫秒级）
                        painter.draw(bmp, d, elapsedMs, s.speedKmh, s.alt, i.toFloat() / totalFrames)
                        encoder.submit(
                            Frame(bmp, startNs + i * frameNs) { pool.release(it) }
                        )
                    } else {
                        pool.release(bmp)
                    }

                    if (i < totalFrames) {
                        val cNext = cam[i + 1]
                        aMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPos(cNext)))
                        movingMarker.position = LatLng(cNext.lat, cNext.lng)
                        if (i % 60 == 0) {
                            val cp = aMap.cameraPosition
                            Log.d(
                                VTAG,
                                "frame#$i camera: tilt=${"%.1f".format(cp.tilt)} zoom=${"%.1f".format(cp.zoom)} bearing=${"%.1f".format(cp.bearing)}"
                            )
                        }
                    }
                    onProgress(i.toFloat() / totalFrames)
                }
            }
            encoder.finishAndWait()
            encoder.error?.let { throw it }
            // 成功兜底校验：编码器/muxer 静默失败会让文件只有头无数据（播放器报损毁）
            if (outFile.length() < 4096) {
                throw IllegalStateException(
                    "视频文件异常（${outFile.length()} 字节），该设备编码器可能不兼容"
                )
            }
        } catch (e: Exception) {
            encoder.cancel()
            runCatching { outFile.delete() }
            throw e
        } finally {
            // 无论成败恢复详情页地图（NonCancellable：协程被取消也要恢复）
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { aMap.uiSettings.setAllGesturesEnabled(true) }
                runCatching { restore() }
            }
        }
        return outFile
    }

    // ---------- 采样与几何 ----------

    /** 缓入缓出（35% smoothstep + 65% 线性）：起步/收尾平滑，中段速度只快 18% */
    private fun ease(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        val s = x * x * (3 - 2 * x)
        return 0.35 * s + 0.65 * x
    }

    /** 二分查找 d 所在轨迹段的右端索引 */
    private fun segIndex(d: Double): Int {
        val dd = d.coerceIn(0.0, total)
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < dd) lo = mid + 1 else hi = mid
        }
        return lo.coerceIn(1, cum.size - 1)
    }

    /** 里程 d 处的坐标（仅经纬度） */
    private fun posAt(d: Double): Pair<Double, Double> {
        val i1 = segIndex(d)
        val i0 = i1 - 1
        val seg = cum[i1] - cum[i0]
        val f = if (seg > 0) (d.coerceIn(0.0, total) - cum[i0]) / seg else 0.0
        return (pts[i0].latitude + (pts[i1].latitude - pts[i0].latitude) * f) to
                (pts[i0].longitude + (pts[i1].longitude - pts[i0].longitude) * f)
    }

    /** 里程 d 处的运动数据：坐标/时间/海拔/速度 */
    private fun sampleAt(d: Double): Sample {
        val i1 = segIndex(d)
        val i0 = i1 - 1
        val seg = cum[i1] - cum[i0]
        val f = if (seg > 0) (d.coerceIn(0.0, total) - cum[i0]) / seg else 0.0
        val lat = pts[i0].latitude + (pts[i1].latitude - pts[i0].latitude) * f
        val lng = pts[i0].longitude + (pts[i1].longitude - pts[i0].longitude) * f
        val time = pts[i0].time + ((pts[i1].time - pts[i0].time) * f).toLong()
        val alt = pts[i0].altitude + (pts[i1].altitude - pts[i0].altitude) * f
        return Sample(lat, lng, time, alt, speedAt(i1) * 3.6)
    }

    /** idx 附近 ±3 点的平滑速度：优先 GPS 多普勒测速，无效时退回段速度 */
    private fun speedAt(idx: Int): Double {
        val lo = max(0, idx - 3)
        val hi = min(pts.size - 1, idx + 3)
        var sum = 0.0
        var n = 0
        for (j in lo..hi) {
            if (pts[j].speed > 0.3f) { sum += pts[j].speed; n++ }
        }
        if (n >= 3) return sum / n
        if (hi > lo && pts[hi].time > pts[lo].time) {
            return (cum[hi] - cum[lo]) / ((pts[hi].time - pts[lo].time) / 1000.0)
        }
        return 0.0
    }

    /** 两点间方位角（0°=正北，顺时针） */
    private fun bearingDeg(lng1: Double, lat1: Double, lng2: Double, lat2: Double): Float {
        val lat1r = Math.toRadians(lat1)
        val lat2r = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(lat2r)
        val x = cos(lat1r) * sin(lat2r) - sin(lat1r) * cos(lat2r) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /**
     * 预计算全部镜头关键帧并做双重平滑，修复镜头摇晃：
     *
     * 1. 位置平滑：原始轨迹含 GPS 抖动，镜头逐帧贴轨迹走会左右"画龙"。
     *    对逐帧采样点做一趟 5 点加权窗口 [1,4,6,4,1] 滤波（端点截断处理），
     *    抖动被平均掉、真实弯形保留；
     * 2. 朝向平滑：摇晃主因——倾角 45° 下朝向每帧 2~3° 的抖动都会放大成
     *    整个画面摆动。朝向取"平滑路径上当前帧 → 前方 LOOKAHEAD_FRAMES 帧"
     *    的方向（镜头快时前瞻米数自然变长），再经双向 EMA（最短弧插值）
     *    滤掉折返抖动；单向 EMA 有相位滞后，前向+后向取平均后急弯响应依然及时。
     */
    private fun buildCamFrames(): Array<CamFrame> {
        val n = totalFrames + 1
        val lat = DoubleArray(n)
        val lng = DoubleArray(n)
        for (i in 0 until n) {
            val p = posAt(total * ease(i.toDouble() / totalFrames))
            lat[i] = p.first
            lng[i] = p.second
        }

        // ---- 位置平滑：5 点加权窗口 ----
        val w = doubleArrayOf(1.0, 4.0, 6.0, 4.0, 1.0)
        val sLat = DoubleArray(n)
        val sLng = DoubleArray(n)
        for (i in 0 until n) {
            var sw = 0.0
            var a = 0.0
            var b = 0.0
            for (k in -2..2) {
                val j = (i + k).coerceIn(0, n - 1)
                sw += w[k + 2]
                a += lat[j] * w[k + 2]
                b += lng[j] * w[k + 2]
            }
            sLat[i] = a / sw
            sLng[i] = b / sw
        }
        System.arraycopy(sLat, 0, lat, 0, n)
        System.arraycopy(sLng, 0, lng, 0, n)

        // ---- 朝向：平滑路径上向前看 LOOKAHEAD_FRAMES 帧 ----
        val rawBrg = FloatArray(n)
        for (i in 0 until n) {
            val j = min(i + LOOKAHEAD_FRAMES, n - 1)
            rawBrg[i] = if (j > i) {
                bearingDeg(lng[i], lat[i], lng[j], lat[j])
            } else {
                bearingDeg(lng[n - 2], lat[n - 2], lng[n - 1], lat[n - 1])
            }
        }

        // ---- 朝向双向 EMA（最短弧插值，避免 359°→1° 绕远路）----
        val alpha = 0.15f
        val fwd = FloatArray(n)
        var acc = rawBrg[0]
        fwd[0] = acc
        for (i in 1 until n) {
            acc += shortestArc(acc, rawBrg[i]) * alpha
            fwd[i] = acc
        }
        val bwd = FloatArray(n)
        acc = rawBrg[n - 1]
        bwd[n - 1] = acc
        for (i in n - 2 downTo 0) {
            acc += shortestArc(acc, rawBrg[i]) * alpha
            bwd[i] = acc
        }
        return Array(n) { i ->
            val b = (fwd[i] + shortestArc(fwd[i], bwd[i]) * 0.5f + 360f) % 360f
            CamFrame(lat[i], lng[i], b)
        }
    }

    /** 最短弧角度差（结果 -180°~180°），用于朝向插值不走远路 */
    private fun shortestArc(from: Float, to: Float): Float =
        ((to - from + 540f) % 360f + 360f) % 360f - 180f

    private fun cameraPos(c: CamFrame) =
        CameraPosition(LatLng(c.lat, c.lng), zoom, TILT, c.bearing)

    /** 移动光点：白色描边 + 绿色圆心 */
    private fun makeDotBitmap(): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(48f, 48f, 44f, p)
        p.color = 0xFF00C853.toInt()
        c.drawCircle(48f, 48f, 30f, p)
        return b
    }

    /** 递归查找 TextureMapView 内部的 TextureView（抓帧用） */
    private fun findTextureView(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findTextureView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    companion object {
        /** 高德 CameraPosition.tilt 有效范围 0~45°，超限整组相机参数会被原生层丢弃（画面无倾角） */
        private const val TILT = 45f

        /** 镜头朝向前瞻帧数：朝向看向平滑路径上该帧位置，镜头越快前瞻米数越长 */
        private const val LOOKAHEAD_FRAMES = 12
    }
}

// ================= 帧数据与位图池 =================

/** 一帧画面（字幕已绘入位图）+ 显示时间戳；onDone 在编码线程消费完后归还位图 */
private class Frame(
    val bitmap: Bitmap,
    val ptsNs: Long,
    val onDone: (Bitmap) -> Unit
)

/** 采集位图循环池：避免 24fps × 全屏位图 的分配风暴 */
private class BitmapPool(private val w: Int, private val h: Int) {
    private val free = ConcurrentLinkedQueue<Bitmap>()

    fun obtain(): Bitmap =
        free.poll() ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    fun release(b: Bitmap) {
        if (free.size < 4) free.offer(b)
    }
}

// ================= 字幕绘制 =================

/**
 * 把运动数据字幕直接画到抓帧位图上：
 * - 左上角：轨迹名 + 运动方式/日期（半透明黑底）
 * - 底部：距离/用时/速度/海拔 四列数据条
 * - 数据条上方：轨迹进度条
 */
private class OverlayPainter(
    private val width: Int,
    private val height: Int,
    private val title: String,
    private val sub: String
) {
    private val u = width / 1280f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private val bgPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f * u
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 30f * u
    }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 27f * u
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 50f * u
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val lineBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF
        strokeWidth = 8f * u
        strokeCap = Paint.Cap.ROUND
    }
    private val lineFg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        strokeWidth = 8f * u
        strokeCap = Paint.Cap.ROUND
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt() }
    private val rect = android.graphics.RectF()

    private val labels = arrayOf("距离", "用时", "速度", "海拔")

    fun draw(bmp: Bitmap, distMeters: Double, elapsedMs: Long, speedKmh: Double, altitude: Double, progress: Float) {
        val canvas = Canvas(bmp)
        val m = 40f * u

        // ---- 左上角标题 ----
        val tw = titlePaint.measureText(title)
        val sw = subPaint.measureText(sub)
        val padH = 26f * u
        val padV = 22f * u
        val titleSize = 46f * u
        val subSize = 30f * u
        val gap = 14f * u
        val boxW = max(tw, sw) + padH * 2
        val boxH = padV * 2 + titleSize + gap + subSize
        rect.set(m, m, m + boxW, m + boxH)
        canvas.drawRoundRect(rect, 26f * u, 26f * u, bgPaint)
        canvas.drawText(title, m + padH, m + padV + titleSize * 0.78f, titlePaint)
        canvas.drawText(sub, m + padH, m + padV + titleSize + gap + subSize * 0.78f, subPaint)

        // ---- 底部数据条 ----
        val barH = 150f * u
        val barTop = height - m - barH
        val barBottom = height - m
        rect.set(m, barTop, width - m, barBottom)
        canvas.drawRoundRect(rect, 26f * u, 26f * u, bgPaint2)
        val values = arrayOf(
            GeoUtils.formatDistance(distMeters),
            GeoUtils.formatDuration(elapsedMs),
            "%.1f km/h".format(speedKmh),
            "%.0f 米".format(altitude)
        )
        val colW = (width - 2 * m) / 4f
        for (i in 0 until 4) {
            val cx = m + colW * (i + 0.5f)
            canvas.drawText(labels[i], cx, barTop + 46f * u, labelPaint)
            canvas.drawText(values[i], cx, barBottom - 38f * u, valuePaint)
        }

        // ---- 进度条 ----
        val py = barTop - 34f * u
        val x0 = m + 20f * u
        val x1 = width - m - 20f * u
        canvas.drawLine(x0, py, x1, py, lineBg)
        val px = x0 + (x1 - x0) * progress.coerceIn(0f, 1f)
        canvas.drawLine(x0, py, px, py, lineFg)
        canvas.drawCircle(px, py, 14f * u, knobPaint)
    }
}

// ================= EGL + MediaCodec 编码 =================

/** 队列元素：帧 或 结束哨兵（null 会被 poll 超时混淆，故用独立对象） */
private object EndOfStream

/** 诊断日志 tag */
private const val VTAG = "TrackVideo"

/**
 * 编码线程：MediaCodec(H.264, Surface 输入) + EGL 纹理上传 + MediaMuxer 封装 MP4。
 *
 * 帧流程：submit(Frame) → EGL 纹理绘制 → eglPresentationTimeANDROID(pts) →
 * swapBuffers 提交到编码器 → drain 编码输出写 muxer。
 * PTS 由生产端赋值，生成过程卡顿不影响视频时长。
 *
 * 所有失败路径（swapBuffers 返回 false、编码器 0 输出、muxer stop 异常）
 * 都会设置 error 向上抛出，绝不静默产出损坏文件。
 */
private class VideoEncodeThread(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val outFile: File
) {
    private val queue = ArrayBlockingQueue<Any?>(6)
    private val done = CountDownLatch(1)

    @Volatile
    var error: Exception? = null
        private set

    @Volatile
    private var cancelled = false

    fun start() {
        kotlin.concurrent.thread(name = "track-video-encoder", isDaemon = true) {
            var codec: android.media.MediaCodec? = null
            var muxer: android.media.MediaMuxer? = null
            var inputSurface: InputSurface? = null
            var samples = 0
            var sampleBytes = 0L
            var muxerStarted = false
            try {
                val fmt = android.media.MediaFormat.createVideoFormat(
                    android.media.MediaFormat.MIMETYPE_VIDEO_AVC, width, height
                ).apply {
                    setInteger(
                        android.media.MediaFormat.KEY_COLOR_FORMAT,
                        android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(android.media.MediaFormat.KEY_BIT_RATE, 10_000_000)
                    setInteger(android.media.MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val c = android.media.MediaCodec.createEncoderByType(
                    android.media.MediaFormat.MIMETYPE_VIDEO_AVC
                )
                codec = c
                c.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                c.start()
                Log.d(VTAG, "codec started: ${c.name} ${width}x$height")

                val m = android.media.MediaMuxer(
                    outFile.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = m

                val ins = InputSurface(surface)
                inputSurface = ins
                ins.makeCurrent()
                val drawer = GlFrameDrawer(width, height)
                Log.d(VTAG, "egl context ready")

                val info = android.media.MediaCodec.BufferInfo()
                var trackIdx = -1

                fun drain(eos: Boolean) {
                    val deadline = System.currentTimeMillis() + 10_000L
                    while (true) {
                        val idx = c.dequeueOutputBuffer(info, if (eos) 10_000L else 0L)
                        when {
                            idx == android.media.MediaCodec.INFO_TRY_AGAIN_LATER ->
                                if (eos) {
                                    if (System.currentTimeMillis() > deadline) return
                                } else return
                            idx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                if (muxerStarted) return
                                trackIdx = m.addTrack(c.outputFormat)
                                m.start()
                                muxerStarted = true
                                Log.d(VTAG, "muxer started, format=${c.outputFormat}")
                            }
                            idx >= 0 -> {
                                val buf = c.getOutputBuffer(idx)
                                if (buf != null) {
                                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                        info.size = 0
                                    }
                                    if (info.size > 0 && muxerStarted) {
                                        buf.position(info.offset)
                                        buf.limit(info.offset + info.size)
                                        m.writeSampleData(trackIdx, buf, info)
                                        samples++
                                        sampleBytes += info.size
                                        if (samples <= 3 || samples % 120 == 0) {
                                            Log.d(VTAG, "sample #$samples size=${info.size} ptsUs=${info.presentationTimeUs}")
                                        }
                                    }
                                }
                                c.releaseOutputBuffer(idx, false)
                                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                var frames = 0
                while (true) {
                    val f = queue.poll(300, TimeUnit.MILLISECONDS)
                    when {
                        f === EndOfStream -> break
                        f == null -> if (cancelled) break else continue
                        f is Frame -> {
                            drawer.draw(f.bitmap)
                            ins.setPresentationTime(f.ptsNs)
                            if (ins.swapBuffers() == false) {
                                throw IllegalStateException(
                                    "eglSwapBuffers 失败 eglError=0x${Integer.toHexString(EGL14.eglGetError())}"
                                )
                            }
                            f.onDone(f.bitmap)
                            frames++
                            if (frames % 60 == 0) Log.d(VTAG, "frames=$frames samples=$samples")
                            drain(false)
                        }
                    }
                }
                Log.d(VTAG, "queue done frames=$frames samples=$samples, signaling EOS")
                c.signalEndOfInputStream()
                drain(true)
                Log.d(VTAG, "drain done samples=$samples bytes=$sampleBytes")
                if (samples == 0) {
                    throw IllegalStateException("编码器未输出任何视频帧（已提交 $frames 帧）")
                }
            } catch (e: Exception) {
                Log.e(VTAG, "encoder thread failed", e)
                error = e
            } finally {
                inputSurface?.release()
                try { codec?.stop() } catch (e: Exception) { Log.w(VTAG, "codec.stop: ${e.message}") }
                try { codec?.release() } catch (e: Exception) { Log.w(VTAG, "codec.release: ${e.message}") }
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.e(VTAG, "muxer.stop failed (samples=$samples bytes=$sampleBytes)", e)
                    if (error == null) error = IllegalStateException("视频封装失败：${e.message}")
                }
                try { muxer?.release() } catch (_: Exception) {}
                Log.d(VTAG, "thread exit samples=$samples bytes=$sampleBytes muxerStarted=$muxerStarted")
                done.countDown()
            }
        }
    }

    /** 生产端投递一帧（编码跟不上时最多阻塞 3 秒后报错） */
    fun submit(frame: Frame) {
        try {
            if (!queue.offer(frame, 3, TimeUnit.SECONDS)) {
                throw IllegalStateException("编码队列阻塞")
            }
        } catch (e: InterruptedException) {
            throw IllegalStateException("编码线程中断", e)
        }
    }

    fun cancel() {
        cancelled = true
    }

    /** 投递结束哨兵并等待编码收尾 */
    suspend fun finishAndWait() {
        try {
            queue.offer(EndOfStream, 3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        withContext(Dispatchers.IO) {
            done.await(30, TimeUnit.SECONDS)
        }
    }
}

/**
 * MediaCodec 输入 Surface 的 EGL 包装（标准 bigflake 模式）：
 * 把 Surface 变成当前 GL 上下文的绘制目标，纹理绘制后 swapBuffers 即提交一帧。
 */
private class InputSurface(surface: Surface) {

    private val eglDisplay: EGLDisplay
    private val eglContext: EGLContext
    private val eglSurface: EGLSurface

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            ?: error("eglGetDisplay failed")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            error("eglInitialize failed")
        }
        // EGL_RECORDABLE_ANDROID(0x3142) 是 eglChooseConfig 的配置属性：
        // 表示该 config 创建的 surface 可送入 MediaCodec。放在 surface 创建
        // 属性里是非法的——华为等设备会静默产出坏 surface（首次使用报
        // EGL_BAD_SURFACE 0x300D），多数设备只是忽略。
        val baseAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        // 先带 recordable 标志选 config，选不到（个别设备不支持该扩展）再去掉重试
        var chosen: EGLConfig? = null
        for (extra in listOf(intArrayOf(0x3142, 1), intArrayOf())) {
            val attribList = baseAttribs + extra + EGL14.EGL_NONE
            if (EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
                && numConfigs[0] > 0
            ) {
                chosen = configs[0]
                break
            }
        }
        val config = chosen ?: error("eglChooseConfig failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            error("eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        // surface 创建属性必须为空（EGL_NONE），recordable 已在 config 层处理
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            error("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            error("eglMakeCurrent failed")
        }
    }

    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    fun swapBuffers(): Boolean =
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    fun release() {
        runCatching {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
    }
}

/** 全屏纹理绘制器：每帧把位图作为 2D 纹理铺满整个编码 Surface */
private class GlFrameDrawer(private val width: Int, private val height: Int) {

    private val program: Int
    private val aPositionLoc: Int
    private val aTexCoordLoc: Int
    private val texId: Int

    // (x, y, u, v)：全屏四边形，v 翻转适配位图行序（首行=画面顶部）
    private val vertexData = floatArrayOf(
        -1f, -1f, 0f, 1f,
        1f, -1f, 1f, 1f,
        -1f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f
    )
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(vertexData) }

    init {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()
        program = buildProgram(vs, fs)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(bitmap: Bitmap) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            error("program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("shader compile failed: $log")
        }
        return shader
    }
}
