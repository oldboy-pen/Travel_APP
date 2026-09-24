package com.example.myfirstapp.ui.components

import android.content.Context
import com.amap.api.maps.TextureMapView

/**
 * 地图实例池：按页面 key 复用 TextureMapView。
 *
 * 背景：高德 SDK 的 MapView 不支持会话内频繁 销毁-重建
 * （切 Tab 时 onDispose 调 onDestroy 会触发 native 层
 *  GLMapEngine.nativeDestroy 崩溃 SIGABRT，实测 9.8.3 必现）。
 *
 * 策略：
 * - 每个页面（record / map / trackDetail）全局只创建一次 TextureMapView；
 * - 切走页面时只调用 onPause()（不 destroy）；
 * - Activity 真正销毁（ON_DESTROY）时才释放并移出池。
 */
object AMapViewPool {

    private class Entry(val mapView: TextureMapView) {
        var created = false   // onCreate 只调一次
        var destroyed = false // 防止重复 destroy
    }

    private val pool = mutableMapOf<String, Entry>()

    /** 取（或首次创建）指定页面的地图实例 */
    fun get(key: String, context: Context): TextureMapView =
        entry(key, context).mapView

    /** 首次使用时调用生命周期 onCreate（幂等） */
    fun ensureCreated(key: String, context: Context) {
        val e = entry(key, context)
        if (!e.created && !e.destroyed) {
            e.mapView.onCreate(null)
            e.created = true
        }
    }

    /** Activity 销毁时释放（幂等），并移出池 */
    fun destroy(key: String) {
        val e = pool.remove(key) ?: return
        if (!e.destroyed) {
            e.destroyed = true
            runCatching { e.mapView.onDestroy() }
        }
    }

    private fun entry(key: String, context: Context): Entry =
        pool.getOrPut(key) { Entry(TextureMapView(context)) }
}
