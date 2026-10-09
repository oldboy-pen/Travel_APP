package com.example.myfirstapp.globe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 一条可叠在球面上的折线（WGS-84） */
data class GlobeLine(val points: List<LonLat>, val colorArgb: Int, val widthPx: Float = 3f)

/**
 * 3D 地球的 GL 渲染器。
 *
 * 全流程只用 OpenGL ES 2.0 + Android 框架的 [Matrix]，没有额外依赖：
 * - 一个单位球，按 XYZ 瓦片金字塔把地图贴图逐块贴到球面上（四叉树 LOD）；
 * - 瓦片先合成到一张纹理图集里，所有块共享一次纹理绑定；
 * - 缺瓦片时用「已下载的祖先瓦片」的对应子矩形顶上（低清先出图，再逐步变清晰）；
 * - 山体阴影开关叠一份 Terrarium 编码的高程图，在片元里算法线扰动。
 *
 * 线程约定：UI 线程只改 [pending*] 系列 volatile 字段和相机标量，
 * 真正的 GL 资源操作全部在 onDrawFrame（GL 线程）里完成。
 */
class GlobeRenderer(
    private val requestRedraw: () -> Unit
) : android.opengl.GLSurfaceView.Renderer {

    // ==================== 对外状态（UI 线程写） ====================

    /** 相机变化的回包：中心经纬度 + 相机高度（公里），在主线程回调 */
    var onCameraChanged: ((LonLat, Double) -> Unit)? = null
    /** 高程数据拉不动时回包一次（提示用户关掉山体阴影） */
    var onDemUnavailable: (() -> Unit)? = null

    @Volatile private var pendingSource: GlobeSource? = null
    @Volatile private var pendingHillshade: Boolean? = null
    @Volatile private var pendingLines: List<GlobeLine>? = null
    @Volatile private var pendingMarker: LonLat? = null
    @Volatile private var pendingView: LonLatWithDist? = null

    /** 默认视角：约东经 105°、北纬 30° 上空（yaw/pitch 与球向量一一对应，见 [center]） */
    @Volatile var yawDeg: Float = 165f
    @Volatile var pitchDeg: Float = -30f
    @Volatile var distance: Float = 3.2f      // 相机到地心的距离（地球半径 = 1）

    /** 当前渲染的图源（GL 线程内使用） */
    @Volatile private var source: GlobeSource? = null
    @Volatile private var hillshadeOn = false
    /** loader 的 generation 快照：初值必须和 loader 的初始 generation（0）一致 */
    @Volatile private var tileGen = 0

    // ==================== 公开方法 ====================

    fun setSource(src: GlobeSource) {
        pendingSource = src
        requestRedraw()
    }

    fun setHillshade(on: Boolean) {
        pendingHillshade = on
        requestRedraw()
    }

    /** 叠加的轨迹（已展开线路）+ 当前位置点；空列表表示清空 */
    fun setOverlay(lines: List<GlobeLine>, marker: LonLat?) {
        pendingLines = lines
        pendingMarker = marker
        requestRedraw()
    }

    /** 把镜头挪到某处（立即跳转，不做补间） */
    fun lookAt(lon: Double, lat: Double, dist: Float?) {
        pendingView = LonLatWithDist(lon, lat, dist)
        requestRedraw()
    }

    /** 当前视口中心正在看的位置 */
    fun center(): LonLat = sphereVecToLonLat(floatArrayOf(-camUnit[0], -camUnit[1], -camUnit[2]))

    /** 单指拖动：像素位移 → 球面旋转（内容跟手） */
    fun rotateBy(dxPx: Float, dyPx: Float) {
        val k = degreesPerPixel()
        yawDeg = wrapLon180(yawDeg - dxPx * k)
        pitchDeg = (pitchDeg - dyPx * k).coerceIn(-88f, 88f)
        requestRedraw()
    }

    /** 双指捏合：factor > 1 = 放大贴近 */
    fun zoomBy(factor: Float) {
        distance = (distance / factor.coerceIn(0.2f, 5f)).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        requestRedraw()
    }

    /** 双击：快速贴近一档 */
    fun zoomStep() {
        distance = (distance * 0.55f).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        requestRedraw()
    }

    // ==================== 渲染器实现 ====================

    private val loader = GlobeTileLoader()
    private var width = 1
    private var height = 1
    private var frame = 0

    private val mvp = FloatArray(16)
    private val viewMat = FloatArray(16)
    private val projMat = FloatArray(16)
    private val camUnit = FloatArray(3)
    private val camPos = FloatArray(3)

    // ---- GL 资源 ----
    private var surfaceProg = 0
    private var lineProg = 0
    private var starProg = 0
    private var surfaceHandles: SurfaceHandles? = null
    private var lineHandles: LineHandles? = null
    private var starHandles: StarHandles? = null

    /** GL 初始化是否成功（上下文有效性 + program 都建出来了才会置 true） */
    @Volatile private var glReady = false
    /** 有没有把异常刷爆日志：只打一次，之后静默降级 */
    private var frameErrorLogged = false

    private var atlasTex = 0
    private var demTex = 0
    private var gridBuf = 0
    private var gridIdx = 0
    private var starBuf = 0
    private var graticuleBuf = 0
    private var graticuleCount = 0
    private var overlayBuf = 0

    /** 每条叠加线在叠加层 VBO 里的顶点偏移与顶点数 */
    private var overlaySegments: List<OverlaySegment> = emptyList()
    private var overlayVertexCount = 0
    private var overlayDirty = true
    private var markerCount = 0
    private var markerOffset = 0

    // ---- 图集槽位 ----
    private class Slot {
        var kind: GlobeTileKind = GlobeTileKind.SURFACE
        var key: TileKey? = null
        var lastFrame: Int = -1
    }

    private val slots = Array(SLOT_COUNT) { Slot() }
    /** (kind, TileKey) → 槽位号 */
    private val slotIndex = HashMap<Long, Int>()
    private val visibleTiles = ArrayList<VisibleTile>()
    private var demFailures = 0

    /** 图集里一格占的归一化尺寸（= 1/ATLAS_GRID） */
    private val baseSlotSize: Float = 1f / ATLAS_GRID
    /** 墨卡托瓦片的最靠极侧的纬度（z=0 的顶边），极盖从这里往上补 */
    private val polarLimitRad: Float = Math.toRadians(tileLatNorth(0, 0)).toFloat()

    private class VisibleTile(val key: TileKey, val screenPx: Double)

    private data class LonLatWithDist(val lon: Double, val lat: Double, val dist: Float?)

    private class SurfaceHandles(
        val aGrid: Int, val uLatRange: Int, val uLonRange: Int, val uUvRect: Int,
        val uDemRect: Int, val uRadius: Int, val uMvp: Int, val uLight: Int,
        val uCamPos: Int, val uAmbient: Int, val uHillshade: Int, val uRelief: Int,
        val uTex: Int, val uDem: Int
    )

    private class LineHandles(val aPos: Int, val uMvp: Int, val uColor: Int)
    private class StarHandles(val aPos: Int, val uMvp: Int, val uSize: Int)

    private class OverlaySegment(val offset: Int, val count: Int, val colorArgb: Int, val widthPx: Float)

    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        loader.failureListener = { kind ->
            if (kind == GlobeTileKind.DEM) {
                demFailures++
                if (demFailures == DEM_FAILURE_THRESHOLD) {
                    Handler(Looper.getMainLooper()).post { onDemUnavailable?.invoke() }
                }
            }
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LESS)
        GLES20.glClearColor(0.016f, 0.024f, 0.047f, 1f)

        surfaceProg = buildProgram(SURFACE_VS, SURFACE_FS)
        surfaceHandles = SurfaceHandles(
            aGrid = GLES20.glGetAttribLocation(surfaceProg, "aGrid"),
            uLatRange = GLES20.glGetUniformLocation(surfaceProg, "uLatRange"),
            uLonRange = GLES20.glGetUniformLocation(surfaceProg, "uLonRange"),
            uUvRect = GLES20.glGetUniformLocation(surfaceProg, "uUvRect"),
            uDemRect = GLES20.glGetUniformLocation(surfaceProg, "uDemRect"),
            uRadius = GLES20.glGetUniformLocation(surfaceProg, "uRadius"),
            uMvp = GLES20.glGetUniformLocation(surfaceProg, "uMvp"),
            uLight = GLES20.glGetUniformLocation(surfaceProg, "uLight"),
            uCamPos = GLES20.glGetUniformLocation(surfaceProg, "uCamPos"),
            uAmbient = GLES20.glGetUniformLocation(surfaceProg, "uAmbient"),
            uHillshade = GLES20.glGetUniformLocation(surfaceProg, "uHillshade"),
            uRelief = GLES20.glGetUniformLocation(surfaceProg, "uRelief"),
            uTex = GLES20.glGetUniformLocation(surfaceProg, "uTex"),
            uDem = GLES20.glGetUniformLocation(surfaceProg, "uDem")
        )
        lineProg = buildProgram(LINE_VS, LINE_FS)
        lineHandles = LineHandles(
            aPos = GLES20.glGetAttribLocation(lineProg, "aPos"),
            uMvp = GLES20.glGetUniformLocation(lineProg, "uMvp"),
            uColor = GLES20.glGetUniformLocation(lineProg, "uColor")
        )
        starProg = buildProgram(STAR_VS, STAR_FS)
        // 任何一个 program 建不出来就整体不画：带着 0 句柄进驱动会 SIGSEGV
        glReady = surfaceProg != 0 && lineProg != 0 && starProg != 0
        if (!glReady) {
            android.util.Log.e(
                TAG,
                "program init failed: surface=$surfaceProg line=$lineProg star=$starProg"
            )
            return
        }
        starHandles = StarHandles(
            aPos = GLES20.glGetAttribLocation(starProg, "aPos"),
            uMvp = GLES20.glGetUniformLocation(starProg, "uMvp"),
            uSize = GLES20.glGetUniformLocation(starProg, "uSize")
        )

        // ---- 单位网格模板：aGrid ∈ [0,1]²，每块瓦片都用同一份顶点，只靠 uniform 变形 ----
        val grid = FloatArray((GRID_N + 1) * (GRID_N + 1) * 2)
        var gi = 0
        for (iy in 0..GRID_N) {
            for (ix in 0..GRID_N) {
                grid[gi++] = ix.toFloat() / GRID_N
                grid[gi++] = iy.toFloat() / GRID_N
            }
        }
        val idx = ShortArray(GRID_N * GRID_N * 6)
        var ii = 0
        val row = GRID_N + 1
        for (iy in 0 until GRID_N) {
            for (ix in 0 until GRID_N) {
                val v0 = iy * row + ix
                val v1 = v0 + 1
                val v2 = v0 + row
                val v3 = v2 + 1
                idx[ii++] = v0.toShort(); idx[ii++] = v2.toShort(); idx[ii++] = v3.toShort()
                idx[ii++] = v0.toShort(); idx[ii++] = v3.toShort(); idx[ii++] = v1.toShort()
            }
        }
        gridBuf = createFloatBuffer(grid)
        gridIdx = createShortBuffer(idx)

        starBuf = createFloatBuffer(buildStars())
        val grat = buildGraticule()
        graticuleCount = grat.size / 3
        graticuleBuf = createFloatBuffer(grat)

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        atlasTex = ids[0]
        demTex = ids[1]
        initAtlas()
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        // 没初始化成功 / 上下文已经没了（页面退出、GL 线程收尾）就什么都别碰：
        // 这种情况下调用 GLES20 接口会跳到空函数指针，直接把进程打死
        if (!glReady) return
        if (android.opengl.EGL14.eglGetCurrentContext() == android.opengl.EGL14.EGL_NO_CONTEXT) return
        try {
            drawFrameInternal()
        } catch (t: Throwable) {
            if (!frameErrorLogged) {
                frameErrorLogged = true
                android.util.Log.e(TAG, "drawFrame failed, stop rendering", t)
            }
            glReady = false     // 静默降级，别每帧崩一次
        }
    }

    private fun drawFrameInternal() {
        frame++
        applyPending()
        uploadArrivedTiles()

        val snapDistance = distance
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(pitch)
        camUnit[0] = (cp * sin(yaw)).toFloat()
        camUnit[1] = sin(pitch).toFloat()
        camUnit[2] = (cp * cos(yaw)).toFloat()
        camPos[0] = camUnit[0] * snapDistance
        camPos[1] = camUnit[1] * snapDistance
        camPos[2] = camUnit[2] * snapDistance

        Matrix.setLookAtM(
            viewMat, 0,
            camPos[0], camPos[1], camPos[2],
            0f, 0f, 0f,
            0f, 1f, 0f
        )
        val aspect = width.toFloat() / height.toFloat()
        val near = max(0.002f, snapDistance - 1.02f)
        Matrix.perspectiveM(projMat, 0, FOV_DEG, aspect, near, 400f)
        Matrix.multiplyMM(mvp, 0, projMat, 0, viewMat, 0)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        drawStars()
        val tiles = collectVisibleTiles()
        drawSurface(tiles)
        drawGraticule()
        drawOverlayLayers()

        if (pendingLines != null || pendingView != null || loader.isBusy()) requestRedraw()
        reportCamera()
    }

    /** 页面退出：停掉下载线程并释放 GL 资源（必须在 GL 上下文还活着的时候调用） */
    fun release() {
        loader.shutdown()
        glReady = false
        // 上下文已经销毁时任何 GL 调用都是空指针跳转，直接跳过
        if (android.opengl.EGL14.eglGetCurrentContext() == android.opengl.EGL14.EGL_NO_CONTEXT) return
        runCatching {
            GLES20.glDeleteTextures(2, intArrayOf(atlasTex, demTex), 0)
            GLES20.glDeleteBuffers(4, intArrayOf(gridBuf, gridIdx, starBuf, graticuleBuf), 0)
            if (overlayBuf != 0) GLES20.glDeleteBuffers(1, intArrayOf(overlayBuf), 0)
            if (surfaceProg != 0) GLES20.glDeleteProgram(surfaceProg)
            if (lineProg != 0) GLES20.glDeleteProgram(lineProg)
            if (starProg != 0) GLES20.glDeleteProgram(starProg)
        }
    }

    // ==================== 每帧：消费 UI 线程的请求 ====================

    private fun applyPending() {
        pendingView?.let { v ->
            val unitV = sphereVec(v.lon, v.lat)
            // 相机站在「目标点的反方向」一侧
            val u = floatArrayOf(-unitV[0], -unitV[1], -unitV[2])
            pitchDeg = Math.toDegrees(asin(u[1].toDouble().coerceIn(-1.0, 1.0))).toFloat()
            yawDeg = Math.toDegrees(atan2(u[0].toDouble(), u[2].toDouble())).toFloat()
            if (v.dist != null) distance = v.dist.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            pendingView = null
        }
        pendingSource?.let { src ->
            source = src
            tileGen = loader.bumpGeneration()
            demFailures = 0
            clearSlots()
            pendingSource = null
        }
        pendingHillshade?.let { on ->
            hillshadeOn = on
            pendingHillshade = null
        }
        pendingLines?.let { lines ->
            buildOverlay(lines, pendingMarker)
            pendingLines = null
            pendingMarker = null
        }
    }

    /** 把已经解好的瓦片上传进图集 */
    private fun uploadArrivedTiles() {
        while (true) {
            val t = loader.ready.poll() ?: break
            if (t.gen != tileGen) {   // 切图源后的过期产物
                runCatching { t.bitmap.recycle() }
                continue
            }
            runCatching {
                val slot = takeSlot(t.key, t.kind)
                if (slot < 0) {
                    t.bitmap.recycle()
                    return@runCatching
                }
                val (tex, tileSize) = if (t.kind == GlobeTileKind.SURFACE)
                    atlasTex to SURFACE_TILE_PX else demTex to DEM_TILE_PX
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLUtils.texSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0,
                    (slot % ATLAS_GRID) * tileSize,
                    (slot / ATLAS_GRID) * tileSize,
                    t.bitmap
                )
                t.bitmap.recycle()
                slotIndex[keyOf(t.kind, t.key)] = slot
                slots[slot].kind = t.kind
                slots[slot].key = t.key
                slots[slot].lastFrame = frame
            }
        }
    }

    /** 申请一个槽位：优先空的，其次淘汰「当前不可见 & 最久没用」的 */
    private fun takeSlot(key: TileKey, kind: GlobeTileKind): Int {
        for (i in 1 until SLOT_COUNT) {
            if (slots[i].key == null) return i
        }
        var victim = -1
        var oldest = Int.MAX_VALUE
        for (i in 1 until SLOT_COUNT) {
            val s = slots[i]
            if (s.kind != kind) continue
            if (frame - s.lastFrame < 2) continue          // 这一帧刚用过的别动
            if (s.lastFrame < oldest) {
                oldest = s.lastFrame
                victim = i
            }
        }
        if (victim < 0) {
            // 全是不可用的：退而求其次，淘汰同种类里最旧的（哪怕正在用）
            var alt = -1
            var altAge = Int.MAX_VALUE
            for (i in 1 until SLOT_COUNT) {
                if (slots[i].kind != kind) continue
                if (slots[i].lastFrame < altAge) {
                    altAge = slots[i].lastFrame
                    alt = i
                }
            }
            if (alt < 0) return -1
            victim = alt
        }
        slotIndex.remove(keyOf(slots[victim].kind, slots[victim].key!!))
        slots[victim].key = null
        return victim
    }

    private fun clearSlots() {
        slotIndex.clear()
        for (i in 1 until SLOT_COUNT) slots[i].key = null
    }

    // ==================== 可见瓦片选择 ====================

    private fun collectVisibleTiles(): List<VisibleTile> {
        visibleTiles.clear()
        val src = source ?: return visibleTiles
        val maxZ = minOf(src.maxZoom, MAX_ZOOM_CAP)
        val horizonCos = (GLOBE_RADIUS / distance.toDouble()).coerceIn(-1.0, 1.0)
        val cutoff = acos(horizonCos) + 0.25
        visitTile(TileKey(0, 0, 0), maxZ, cutoff, visibleTiles)
        visibleTiles.sortWith(compareByDescending<VisibleTile> { it.screenPx })
        requestMissingTiles(visibleTiles, maxZ)
        return visibleTiles
    }

    private fun visitTile(t: TileKey, maxZ: Int, cutoff: Double, out: ArrayList<VisibleTile>) {
        val c = tileCenter(t)
        val cv = sphereVec(c.lon, c.lat)
        if (angleBetween(cv, camUnit) > cutoff + tileAngularRadius(t)) return
        val px = tileScreenExtentPx(t)
        if (t.z < maxZ && px > SPLIT_PX && out.size < MAX_VISIBLE_TILES) {
            tileChildren(t).forEach { visitTile(it, maxZ, cutoff, out) }
            return
        }
        out.add(VisibleTile(t, px))
    }

    /** 缺哪就补哪：本帧可见但没有（且不在飞行中）的才请求 */
    private fun requestMissingTiles(tiles: List<VisibleTile>, maxZ: Int) {
        val src = source ?: return
        // 只补最靠前的若干块：列表已按屏幕尺寸排好序，远处的小块等镜头转过去再说
        for (vt in tiles.take(MAX_REQUESTS_PER_FRAME)) {
            val s = slotIndex[keyOf(GlobeTileKind.SURFACE, vt.key)]
            if (s != null) {
                slots[s].lastFrame = frame
                continue
            }
            loader.requestSurface(vt.key, src)
        }
        if (!hillshadeOn) return
        for (vt in tiles.take(MAX_REQUESTS_PER_FRAME)) {
            // DEM 只到 15 级，比这更细的地表块没有对应高程，跳过
            if (vt.key.z > GlobeSources.DEM_MAX_ZOOM) continue
            val key = vt.key
            val s = slotIndex[keyOf(GlobeTileKind.DEM, key)]
            if (s != null) {
                slots[s].lastFrame = frame
                continue
            }
            loader.requestDem(key)
        }
    }

    /** 瓦片在屏幕上的最大边长（像素）：用来决定要不要继续细分 */
    private fun tileScreenExtentPx(t: TileKey): Double {
        val w = tileLonWest(t.x, t.z)
        val e = tileLonEast(t.x, t.z)
        val n = tileLatNorth(t.y, t.z)
        val s = tileLatSouth(t.y, t.z)
        val corners = listOf(
            sphereVec(w, n), sphereVec(e, n),
            sphereVec(w, s), sphereVec(e, s)
        )
        val horizonCos = (GLOBE_RADIUS / distance.toDouble()).coerceIn(-1.0, 1.0)
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var used = 0
        val px = FloatArray(2)
        val tmp = FloatArray(4)
        for (p in corners) {
            // 地平线后面的角不算（它们的投影会被正面拉爆）
            val dp = camUnit[0] * p[0] + camUnit[1] * p[1] + camUnit[2] * p[2]
            if (dp.toDouble() >= horizonCos) continue
            if (!projectToScreen(p, px, tmp)) continue
            minX = minOf(minX, px[0].toDouble())
            maxX = maxOf(maxX, px[0].toDouble())
            minY = minOf(minY, px[1].toDouble())
            maxY = maxOf(maxY, px[1].toDouble())
            used++
        }
        if (used == 0) return 0.0
        return maxOf(maxX - minX, maxY - minY)
    }

    private fun projectToScreen(p: FloatArray, out: FloatArray, tmp4: FloatArray): Boolean {
        tmp4[0] = p[0]; tmp4[1] = p[1]; tmp4[2] = p[2]; tmp4[3] = 1f
        val res = FloatArray(4)
        Matrix.multiplyMV(res, 0, mvp, 0, tmp4, 0)
        if (res[3] <= 0f) return false
        out[0] = (res[0] / res[3] * 0.5f + 0.5f) * width
        out[1] = (0.5f - res[1] / res[3] * 0.5f) * height
        return true
    }

    // ==================== 绘制 ====================

    private fun drawSurface(tiles: List<VisibleTile>) {
        val h = surfaceHandles ?: return
        GLES20.glUseProgram(surfaceProg)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, gridBuf)
        GLES20.glEnableVertexAttribArray(h.aGrid)
        GLES20.glVertexAttribPointer(h.aGrid, 2, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, gridIdx)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex)
        GLES20.glUniform1i(h.uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, demTex)
        GLES20.glUniform1i(h.uDem, 1)

        GLES20.glUniformMatrix4fv(h.uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(h.uRadius, GLOBE_RADIUS)
        GLES20.glUniform3f(h.uCamPos, camPos[0], camPos[1], camPos[2])
        GLES20.glUniform3f(h.uLight, lightDir[0], lightDir[1], lightDir[2])
        GLES20.glUniform1f(h.uAmbient, AMBIENT)
        GLES20.glUniform1f(h.uHillshade, if (hillshadeOn) 1f else 0f)
        GLES20.glUniform1f(h.uRelief, RELIEF_STRENGTH)

        for (vt in tiles) {
            val rect = resolveSurfaceRect(vt.key) ?: continue
            GLES20.glUniform2f(h.uLatRange, rect.latSouth, rect.latNorth)
            GLES20.glUniform2f(h.uLonRange, rect.lonWest, rect.lonEast)
            GLES20.glUniform4f(h.uUvRect, rect.u0, rect.v0, rect.du, rect.dv)
            val d = resolveDemRect(vt.key)
            GLES20.glUniform4f(h.uDemRect, d[0], d[1], d[2], d[3])
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, GRID_N * GRID_N * 6, GLES20.GL_UNSIGNED_SHORT, 0)
        }

        // ---- 极盖：墨卡托瓦片只画到 ±85.05°，两极各留一个小洞，补两张冰色贴片 ----
        // longitude 拉满一圈，顶点在 ±180 处重合；纬度上边界取 90（全部顶点收拢到极点），
        // 于是一个"环球 patch"刚好退化成一个圆盘盖。
        GLES20.glUniform2f(h.uLonRange, -PI.toFloat(), PI.toFloat())
        GLES20.glUniform4f(h.uDemRect, 0f, 0f, baseSlotSize, baseSlotSize)
        GLES20.glUniform4f(h.uUvRect, 0f, 0f, baseSlotSize, baseSlotSize)
        GLES20.glUniform2f(h.uLatRange, polarLimitRad, (PI / 2).toFloat())
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, GRID_N * GRID_N * 6, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glUniform2f(h.uLatRange, -(PI / 2).toFloat(), -polarLimitRad)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, GRID_N * GRID_N * 6, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glDisableVertexAttribArray(h.aGrid)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private class TileRect(
        val latSouth: Float, val latNorth: Float,
        val lonWest: Float, val lonEast: Float,
        val u0: Float, val v0: Float, val du: Float, val dv: Float
    )

    /** 找这块瓦片能用的贴图：自己 → 最近的祖先 → 常驻底色槽位 */
    private fun resolveSurfaceRect(key: TileKey): TileRect? {
        val lat1 = Math.toRadians(tileLatNorth(key.y, key.z)).toFloat()
        val lat0 = Math.toRadians(tileLatSouth(key.y, key.z)).toFloat()
        val lon0 = Math.toRadians(tileLonWest(key.x, key.z)).toFloat()
        val lon1 = Math.toRadians(tileLonEast(key.x, key.z)).toFloat()

        var cur = key
        var slot = slotIndex[keyOf(GlobeTileKind.SURFACE, cur)]
        var subX = 0f
        var subY = 0f
        var subSize = 1f
        while (slot == null && cur.z > 0) {
            val parent = tileParent(cur)
            val r = subTileRect(cur, parent)
            // 父坐标里的偏移折算到「相对最开始那块」的比例
            subX += r[0] * subSize
            subY += r[1] * subSize
            subSize *= r[2]
            cur = parent
            slot = slotIndex[keyOf(GlobeTileKind.SURFACE, cur)]
        }
        val resolvedSlot = slot ?: BLANK_SLOT
        if (slot == null) {
            subX = 0f; subY = 0f; subSize = 1f
        }
        val row = resolvedSlot / ATLAS_GRID
        val col = resolvedSlot % ATLAS_GRID
        val base = 1f / ATLAS_GRID
        return TileRect(
            latSouth = lat0, latNorth = lat1, lonWest = lon0, lonEast = lon1,
            u0 = (col * base) + subX * base,
            v0 = (row * base) + subY * base,
            du = subSize * base,
            dv = subSize * base
        )
    }

    private val demRectTmp = FloatArray(4)

    /** DEM 同上，找不到就退回 0 号槽（整张图初始化成海拔 0，等效没有起伏） */
    private fun resolveDemRect(key: TileKey): FloatArray {
        var cur = key
        var slot = slotIndex[keyOf(GlobeTileKind.DEM, cur)]
        var subX = 0f
        var subY = 0f
        var subSize = 1f
        while (slot == null && cur.z > 0) {
            val parent = tileParent(cur)
            val r = subTileRect(cur, parent)
            subX += r[0] * subSize
            subY += r[1] * subSize
            subSize *= r[2]
            cur = parent
            slot = slotIndex[keyOf(GlobeTileKind.DEM, cur)]
        }
        val resolved = slot ?: 0
        if (slot == null) {
            subX = 0f; subY = 0f; subSize = 1f
        }
        val base = 1f / ATLAS_GRID
        demRectTmp[0] = (resolved % ATLAS_GRID) * base + subX * base
        demRectTmp[1] = (resolved / ATLAS_GRID) * base + subY * base
        demRectTmp[2] = subSize * base
        demRectTmp[3] = subSize * base
        return demRectTmp
    }

    private fun drawStars() {
        val h = starHandles ?: return
        GLES20.glUseProgram(starProg)
        GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(h.uMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(h.uSize, STAR_SIZE)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starBuf)
        GLES20.glEnableVertexAttribArray(h.aPos)
        GLES20.glVertexAttribPointer(h.aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, STAR_COUNT)
        GLES20.glDisableVertexAttribArray(h.aPos)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawGraticule() {
        val h = lineHandles ?: return
        if (graticuleCount <= 0) return
        GLES20.glUseProgram(lineProg)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(h.uMvp, 1, false, mvp, 0)
        GLES20.glUniform4f(h.uColor, 0.85f, 0.88f, 0.92f, 0.22f)
        GLES20.glLineWidth(1f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, graticuleBuf)
        GLES20.glEnableVertexAttribArray(h.aPos)
        GLES20.glVertexAttribPointer(h.aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, graticuleCount)
        GLES20.glDisableVertexAttribArray(h.aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawOverlayLayers() {
        val h = lineHandles ?: return
        if (overlaySegments.isEmpty()) return
        if (overlayDirty) {
            uploadOverlayBuffer()
            overlayDirty = false
        }
        GLES20.glUseProgram(lineProg)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(h.uMvp, 1, false, mvp, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, overlayBuf)
        GLES20.glEnableVertexAttribArray(h.aPos)
        GLES20.glVertexAttribPointer(h.aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
        for (seg in overlaySegments) {
            val c = argb(seg.colorArgb)
            GLES20.glUniform4f(h.uColor, c[0], c[1], c[2], c[3])
            GLES20.glLineWidth(seg.widthPx)
            GLES20.glDrawArrays(GLES20.GL_LINES, seg.offset, seg.count)
        }
        GLES20.glDisableVertexAttribArray(h.aPos)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    // ==================== 叠加层（轨迹 / 当前位置） ====================

    private var overlayData: FloatArray = FloatArray(0)

    private fun buildOverlay(lines: List<GlobeLine>, marker: LonLat?) {
        /**
         * 每条线按最大点数重采样，避免几万点的轨迹把顶点缓冲撑爆；
         * 再按「相邻点对」展开成 GL_LINES 的两两顶点，全部拼进一个大 VBO。
         */
        val verts = ArrayList<Float>(lines.size * MAX_LINE_POINTS * 6 + 512)
        val segs = ArrayList<OverlaySegment>(lines.size)
        val lift = GLOBE_RADIUS * 1.003f
        for (line in lines) {
            val pts = resample(line.points, MAX_LINE_POINTS)
            val start = verts.size / 3
            for (i in 0 until pts.size - 1) {
                val a = sphereVec(pts[i].lon, pts[i].lat, lift)
                val b = sphereVec(pts[i + 1].lon, pts[i + 1].lat, lift)
                verts.add(a[0]); verts.add(a[1]); verts.add(a[2])
                verts.add(b[0]); verts.add(b[1]); verts.add(b[2])
            }
            val count = verts.size / 3 - start
            if (count > 0) segs.add(OverlaySegment(start, count, line.colorArgb, line.widthPx))
        }
        markerOffset = verts.size / 3
        markerCount = 0
        if (marker != null) {
            // 当前位置：一个小圆圈 + 十字，贴在球面上
            val rDeg = 0.55
            var prev: FloatArray? = null
            for (i in 0..24) {
                val a = i / 24.0 * 2 * PI
                val p = offsetPoint(marker.lon, marker.lat, rDeg * cos(a), rDeg * sin(a), GLOBE_RADIUS * 1.006f)
                if (prev != null) {
                    verts.add(prev[0]); verts.add(prev[1]); verts.add(prev[2])
                    verts.add(p[0]); verts.add(p[1]); verts.add(p[2])
                }
                prev = p
            }
            val crossArm = rDeg * 1.6
            listOf(
                offsetPoint(marker.lon, marker.lat, 0.0, crossArm, lift) to offsetPoint(marker.lon, marker.lat, 0.0, -crossArm, lift),
                offsetPoint(marker.lon, marker.lat, -crossArm, 0.0, lift) to offsetPoint(marker.lon, marker.lat, crossArm, 0.0, lift)
            ).forEach { (a, b) ->
                verts.add(a[0]); verts.add(a[1]); verts.add(a[2])
                verts.add(b[0]); verts.add(b[1]); verts.add(b[2])
            }
            markerCount = verts.size / 3 - markerOffset
            segs.add(OverlaySegment(markerOffset, markerCount, MARKER_COLOR, 3f))
        }
        overlayData = verts.toFloatArray()
        overlaySegments = segs
        overlayVertexCount = overlayData.size / 3
        overlayDirty = true
    }

    private fun uploadOverlayBuffer() {
        if (overlayVertexCount == 0) return
        if (overlayBuf == 0) {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            overlayBuf = ids[0]
        }
        val fb = ByteBuffer.allocateDirect(overlayData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(overlayData)
        fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, overlayBuf)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, overlayData.size * 4, fb, GLES20.GL_STATIC_DRAW)
    }

    /** 在球面上做局部偏移（近似）：东向 offEast / 北向 offNorth，单位「度」 */
    private fun offsetPoint(lon: Double, lat: Double, offEastDeg: Double, offNorthDeg: Double, radius: Float): FloatArray {
        val latRad = Math.toRadians(lat)
        val dLon = offEastDeg / max(0.15, cos(latRad))
        return sphereVec(lon + dLon, lat + offNorthDeg, radius)
    }

    private fun resample(points: List<LonLat>, maxPoints: Int): List<LonLat> {
        if (points.size <= maxPoints) return points
        val step = (points.size - 1).toDouble() / (maxPoints - 1)
        val out = ArrayList<LonLat>(maxPoints)
        var acc = 0.0
        while (acc < points.size - 1) {
            out.add(points[acc.toInt()])
            acc += step
        }
        if (out.isEmpty()) out.add(points.first())
        if (out.last() !== points.last()) out.add(points.last())
        return out
    }

    // ==================== 相机回包 ====================

    private var lastReportedLon = Double.NaN
    private var lastReportedLat = Double.NaN
    private var lastReportedDist = -1.0

    private fun reportCamera() {
        val c = sphereVecToLonLat(floatArrayOf(-camUnit[0], -camUnit[1], -camUnit[2]))
        val km = (distance - GLOBE_RADIUS) * EARTH_RADIUS_KM
        if (abs(c.lon - lastReportedLon) < 0.01 && abs(c.lat - lastReportedLat) < 0.01 &&
            abs(km - lastReportedDist) < 1.0
        ) return
        lastReportedLon = c.lon
        lastReportedLat = c.lat
        lastReportedDist = km
        Handler(Looper.getMainLooper()).post { onCameraChanged?.invoke(c, km) }
    }

    private fun degreesPerPixel(): Float {
        val radPerPx = 2.0 * tan(Math.toRadians(FOV_DEG / 2.0)) / max(1, this.height) * ROTATE_GAIN
        return Math.toDegrees(radPerPx).toFloat()
    }

    private fun wrapLon180(v: Float): Float {
        var x = v
        while (x > 180f) x -= 360f
        while (x < -180f) x += 360f
        return x
    }

    // ==================== 静态几何 / GL 工具 ====================

    private fun buildStars(): FloatArray {
        val out = FloatArray(STAR_COUNT * 3)
        // 固定序列的伪随机，保证每次进页面星空一致
        var seed = 20241009L
        fun next(): Double {
            seed = seed * 1103515245L + 12345L
            return ((seed ushr 8) and 0xFFFFFFL).toDouble() / 0xFFFFFFL.toDouble()
        }
        val r = 90f
        for (i in 0 until STAR_COUNT) {
            val u = next() * 2 - 1
            val theta = next() * 2 * PI
            val s = sqrt(1 - u * u)
            out[i * 3] = (s * cos(theta) * r).toFloat()
            out[i * 3 + 1] = (u * r).toFloat()
            out[i * 3 + 2] = (s * sin(theta) * r).toFloat()
        }
        return out
    }

    /** 经纬网：每 15° 一条，统一用 GL_LINES 的成对顶点拼（便于一次画完） */
    private fun buildGraticule(): FloatArray {
        val out = ArrayList<Float>()
        val r = GLOBE_RADIUS * 1.0015f
        var lon = -180.0
        while (lon <= 180.0) {
            var lat = -75.0
            while (lat < 75.0) {
                val a = sphereVec(lon, lat, r)
                val b = sphereVec(lon, lat + 5.0, r)
                out.add(a[0]); out.add(a[1]); out.add(a[2])
                out.add(b[0]); out.add(b[1]); out.add(b[2])
                lat += 5.0
            }
            lon += 15.0
        }
        var lat2 = -75.0
        while (lat2 <= 75.0) {
            var lon2 = -180.0
            while (lon2 < 180.0) {
                val a = sphereVec(lon2, lat2, r)
                val b = sphereVec(lon2 + 5.0, lat2, r)
                out.add(a[0]); out.add(a[1]); out.add(a[2])
                out.add(b[0]); out.add(b[1]); out.add(b[2])
                lon2 += 5.0
            }
            lat2 += 15.0
        }
        return out.toFloatArray()
    }

    private fun initAtlas() {
        // ---- 地表图集：整张用 glTexImage2D 开出来，0 号槽位填一张底色图当兜底 ----
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            ATLAS_GRID * SURFACE_TILE_PX, ATLAS_GRID * SURFACE_TILE_PX, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val blank = Bitmap.createBitmap(SURFACE_TILE_PX, SURFACE_TILE_PX, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(BLANK_COLOR)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, blank)
        blank.recycle()

        // ---- 高程图集：整张预填「海拔 0」（Terrarium 里 0 米 = R 128 G 0 B 0） ----
        val demSize = ATLAS_GRID * DEM_TILE_PX
        val flat = Bitmap.createBitmap(demSize, demSize, Bitmap.Config.ARGB_8888)
        Canvas(flat).drawColor(ZERO_ELEVATION_COLOR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, demTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, flat, 0)
        flat.recycle()
    }

    private fun createFloatBuffer(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val id = ids[0]
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data)
        fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return id
    }

    private fun createShortBuffer(data: ShortArray): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val id = ids[0]
        val sb: ShortBuffer = ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(data)
        sb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, data.size * 2, sb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        return id
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        // 拿不到 shader 句柄（0）就别往下走了：后续 attach/link 会带着非法句柄进驱动
        if (v == 0 || f == 0) {
            android.util.Log.e(TAG, "buildProgram: shader handle invalid v=$v f=$f")
            if (v != 0) GLES20.glDeleteShader(v)
            if (f != 0) GLES20.glDeleteShader(f)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        if (prog == 0) {
            android.util.Log.e(TAG, "buildProgram: glCreateProgram failed")
            return 0
        }
        GLES20.glAttachShader(prog, v)
        GLES20.glAttachShader(prog, f)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(prog))
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        if (sh == 0) {
            android.util.Log.e(TAG, "glCreateShader($type) returned 0")
            return 0
        }
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            android.util.Log.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(sh))
        }
        return sh
    }

    private fun argb(color: Int): FloatArray = floatArrayOf(
        ((color ushr 16) and 0xFF) / 255f,
        ((color ushr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
        ((color ushr 24) and 0xFF) / 255f
    )

    private fun keyOf(kind: GlobeTileKind, key: TileKey): Long {
        val k = if (kind == GlobeTileKind.SURFACE) 0L else 1L
        return (k shl 56) or (key.z.toLong() shl 48) or (key.x.toLong() shl 24) or key.y.toLong()
    }

    // ==================== 常量与着色器 ====================

    companion object {
        const val TAG = "GlobeRenderer"

        /** 单块瓦片的网格细分数（每边 8 格 → 一块 128 个三角形） */
        const val GRID_N = 8
        const val ATLAS_GRID = 8
        const val SLOT_COUNT = ATLAS_GRID * ATLAS_GRID
        const val BLANK_SLOT = 0

        const val MAX_VISIBLE_TILES = 200
        /** 一帧最多发起多少次下载：筛掉那些一闪而过的远景小块 */
        const val MAX_REQUESTS_PER_FRAME = 24
        const val MAX_ZOOM_CAP = 11
        /** 瓦片在屏幕上超过这么多像素就要继续往下细分 */
        const val SPLIT_PX = 220.0
        const val FOV_DEG = 45f
        const val ROTATE_GAIN = 1.5

        const val MIN_DISTANCE = 1.03f
        const val MAX_DISTANCE = 8f
        const val AMBIENT = 0.48f
        const val RELIEF_STRENGTH = 5.0f
        const val EARTH_RADIUS_KM = 6371.0

        const val STAR_COUNT = 700
        const val STAR_SIZE = 2.2f
        const val MAX_LINE_POINTS = 3000
        const val MARKER_COLOR = 0xFFFF3B30.toInt()
        const val DEM_FAILURE_THRESHOLD = 6
        const val BLANK_COLOR = 0xFFECE9E4.toInt()
        const val ZERO_ELEVATION_COLOR = 0xFF800000.toInt()

        /** 简单的 Lambert 光 + 晨昏线的观感：光从相机左上角打过来，保证看到的面总是亮的 */
        val lightDir: FloatArray = run {
            val v = floatArrayOf(-0.55f, 0.62f, 0.56f)
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }

        /**
         * 地表着色器：
         * - aGrid ∈ [0,1]²，(0,0) 是瓦片西北角；(1,1) 是东南角；
         * - 位置在顶点里由经纬度范围插值算出（所以所有瓦片共用一份顶点缓冲）；
         * - 纹理 v 方向直接跟着瓦片行方向（上传时按 GL 的行序，已在 uv 里对齐）。
         */
        val SURFACE_VS = """
            precision highp float;
            attribute vec2 aGrid;
            uniform vec2 uLatRange;   // (南, 北)，弧度
            uniform vec2 uLonRange;   // (西, 东)，弧度
            uniform vec4 uUvRect;     // 图集里的 uv 矩形 (u0, v0, du, dv)
            uniform float uRadius;
            uniform mat4 uMvp;
            varying vec2 vUv;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
                float lat = mix(uLatRange.y, uLatRange.x, aGrid.y);
                float lon = mix(uLonRange.x, uLonRange.y, aGrid.x);
                float cb = cos(lat);
                vec3 p = vec3(cb * cos(lon), sin(lat), cb * sin(lon)) * uRadius;
                vUv = vec2(uUvRect.x + aGrid.x * uUvRect.z, uUvRect.y + aGrid.y * uUvRect.w);
                vNormal = normalize(p);
                vWorld = p;
                gl_Position = uMvp * vec4(p, 1.0);
            }
        """.trimIndent()

        val SURFACE_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uDem;
            uniform vec4 uUvRect;
            uniform vec4 uDemRect;
            uniform vec3 uCamPos;
            uniform vec3 uLight;
            uniform float uAmbient;
            uniform float uHillshade;
            uniform float uRelief;
            varying vec2 vUv;
            varying vec3 vNormal;
            varying vec3 vWorld;

            // Terrarium：height = (R * 256 + G + B / 256) - 32768
            float demAt(vec2 uv) {
                vec3 c = texture2D(uDem, uv).rgb;
                return (c.r * 65280.0 + c.g * 255.0 + c.b * (255.0 / 256.0)) - 32768.0;
            }

            void main() {
                vec4 texel = texture2D(uTex, vUv);
                vec3 n = normalize(vNormal);

                if (uHillshade > 0.5) {
                    // 把 uv 换算到高程图里的同一块区域，取邻域做山体阴影
                    vec2 f = (vUv - uUvRect.xy) / max(uUvRect.zw, vec2(1e-6));
                    vec2 base = uDemRect.xy + f * uDemRect.zw;
                    vec2 ex = vec2(uDemRect.z * 0.02, 0.0);
                    vec2 ey = vec2(0.0, uDemRect.w * 0.02);
                    float hL = demAt(base - ex);
                    float hR = demAt(base + ex);
                    float hD = demAt(base - ey);
                    float hU = demAt(base + ey);
                    vec3 up = vec3(0.0, 1.0, 0.0);
                    vec3 east = cross(n, up);
                    float el = length(east);
                    east = el < 1e-4 ? vec3(1.0, 0.0, 0.0) : east / el;
                    vec3 north = cross(east, n);
                    float gx = (hR - hL) * uRelief * 0.0008;
                    float gy = (hD - hU) * uRelief * 0.0008;
                    n = normalize(n - east * gx - north * gy);
                }

                float diff = max(dot(n, uLight), 0.0);
                float lambert = uAmbient + (1.0 - uAmbient) * diff;
                vec3 viewDir = normalize(uCamPos - vWorld);
                float rim = pow(1.0 - max(dot(normalize(vNormal), viewDir), 0.0), 3.0);
                vec3 color = texel.rgb * lambert + vec3(0.22, 0.42, 0.85) * rim * 0.55;
                gl_FragColor = vec4(color, 1.0);
            }
        """.trimIndent()

        val LINE_VS = """
            precision highp float;
            attribute vec3 aPos;
            uniform mat4 uMvp;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); }
        """.trimIndent()

        val LINE_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """.trimIndent()

        val STAR_VS = """
            precision highp float;
            attribute vec3 aPos;
            uniform mat4 uMvp;
            uniform float uSize;
            varying float vBright;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = uSize;
                float r = fract(sin(dot(aPos.xy, vec2(12.9898, 78.233))) * 43758.5453);
                vBright = 0.25 + 0.75 * r;
            }
        """.trimIndent()

        val STAR_FS = """
            precision mediump float;
            varying float vBright;
            void main() {
                vec2 d = gl_PointCoord - vec2(0.5);
                float m = 1.0 - smoothstep(0.18, 0.5, length(d));
                if (m <= 0.01) discard;
                gl_FragColor = vec4(vec3(vBright), m * vBright);
            }
        """.trimIndent()
    }
}
