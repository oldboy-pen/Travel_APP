package com.example.myfirstapp.globe

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/**
 * 3D 地球的显示容器：本质是一个 GLSurfaceView，手势全部自己实现。
 *
 * - 单指拖动：绕地心旋转（内容跟手）；
 * - 双指捏合：拉近 / 推远；
 * - 双击：快速贴近一档。
 *
 * 渲染模式是 WHEN_DIRTY，只有相机变化或有瓦片到货时才重画，省电。
 */
class GlobeSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: GlobeRenderer

    private val scaleDetector: ScaleGestureDetector
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastTapTime = 0L

    init {
        // ★ 必须在 setRenderer 之前声明要 ES 2.0 上下文：
        //   GLSurfaceView 默认建的是 **ES 1.x** 上下文，此时调用 GLES20 里只有 ES2 才有的
        //   接口（glCreateShader/glCreateProgram 等）会跳到空的驱动函数指针，
        //   直接 SIGSEGV（tombstone 里表现为 #00 pc 0000000000000000 + art_jni_trampoline）。
        setEGLContextClientVersion(2)
        // 显式要深度缓冲：球面背面的遮挡全靠深度测试（个别设备没有 8888+16 的组合，失败就用默认配置）
        runCatching { setEGLConfigChooser(8, 8, 8, 8, 16, 0) }
        renderer = GlobeRenderer { requestRender() }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                renderer.zoomBy(detector.scaleFactor)
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                dragging = event.pointerCount == 1
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging && !scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    renderer.rotateBy(dx, dy)
                }
                lastX = event.x
                lastY = event.y
                if (event.pointerCount > 1) dragging = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val moved = abs(event.x - downX) + abs(event.y - downY)
                    val dur = System.currentTimeMillis() - downTime
                    if (moved < 14f && dur < 260L) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 320L) {
                            renderer.zoomStep()
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                        }
                    }
                }
                dragging = false
                performClick()
            }

            MotionEvent.ACTION_POINTER_UP -> dragging = false
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 进入页面：GL 线程恢复；[GlobeScreen] 首次组合时调用 */
    fun onResumeGl() {
        onResume()
    }

    /** 离开页面：停掉渲染线程并释放 GL 资源 */
    fun shutdown() {
        queueEvent { renderer.release() }
        onPause()
    }
}

/** 创建一个跟随当前 Compose 节点的地球视图（页面退出自动释放） */
@Composable
fun rememberGlobeView(): GlobeSurfaceView {
    val context = LocalContext.current
    val view = remember { GlobeSurfaceView(context) }
    DisposableEffect(view) {
        view.onResumeGl()
        onDispose { view.shutdown() }
    }
    return view
}

/** 把地球视图挂到 Compose 上 */
@Composable
fun GlobeMap(view: GlobeSurfaceView, modifier: Modifier = Modifier) {
    // factory 直接返回已在 rememberGlobeView 里创建好的实例（不能就地 new，
    // 否则重组会不断新建 GLSurfaceView；同时 release 由上面的 DisposableEffect 负责）
    AndroidView(
        factory = { view },
        modifier = modifier
    )
}
