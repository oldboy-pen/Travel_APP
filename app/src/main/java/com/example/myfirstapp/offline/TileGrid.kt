package com.example.myfirstapp.offline

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 标准 XYZ 瓦片网格计算（Web 墨卡托 / EPSG:3857，与天地图、OpenTopoMap、osmdroid 一致）。
 *
 * 为什么单独抽出来：离线下载要在**没有地图引擎**的情况下按经纬度矩形展开出所有瓦片坐标
 * （引擎此时可能还没创建），而 osmdroid 的 TileSystem 是给渲染用的。这里保持纯数学、
 * 无 Android 依赖，方便在下载线程里直接调用。
 *
 * 约定：
 * - x 自西向东、y **自上而下**（标准 XYZ / Google 规范）；
 * - MBTiles 存档内部用的是 TMS（y 自下而上），转换见 [tmsY]，只在写库/读库时用一次。
 */
object TileGrid {

    /** 该级别的瓦片边长（2^zoom） */
    fun size(zoom: Int): Int = 1 shl zoom

    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = size(zoom)
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = size(zoom)
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    fun tileXToLon(x: Int, zoom: Int): Double =
        x.toDouble() / size(zoom) * 360.0 - 180.0

    /** 瓦片上边缘的纬度（y 自上而下） */
    fun tileYToLat(y: Int, zoom: Int): Double {
        val n = size(zoom) * Math.PI
        val merc = (1.0 - 2.0 * y.toDouble() / size(zoom)) * Math.PI
        return Math.toDegrees(atanh(sin(merc)))
    }

    private fun atanh(v: Double): Double = 0.5 * ln((1.0 + v) / (1.0 - v))

    /** XYZ(y 自上而下) → TMS(y 自下而上)。MBTiles 规范用 TMS */
    fun tmsY(y: Int, zoom: Int): Int = size(zoom) - y - 1

    /** 某个级别下覆盖 bbox 所需的 x 范围（闭区间，已裁剪到网格内） */
    fun xRange(minLon: Double, maxLon: Double, zoom: Int): IntRange {
        val a = lonToTileX(min(minLon, maxLon), zoom)
        val b = lonToTileX(max(minLon, maxLon), zoom)
        return a..b
    }

    /** 某个级别下覆盖 bbox 所需的 y 范围（XYZ 自上而下，已裁剪） */
    fun yRange(minLat: Double, maxLat: Double, zoom: Int): IntRange {
        val north = max(minLat, maxLat)
        val south = min(minLat, maxLat)
        val a = latToTileY(north, zoom)   // 北边 → y 小
        val b = latToTileY(south, zoom)   // 南边 → y 大
        return a..b
    }

    /**
     * 估算一批级别覆盖 bbox 的总瓦片数（下载前的体积/耗时预估）。
     * 复合图层（如天地图"矢量+注记"）每张瓦片要抓 N 层，用 [layersPerTile] 放大计数。
     */
    fun countTiles(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        minZoom: Int, maxZoom: Int, layersPerTile: Int = 1
    ): Long {
        var total = 0L
        for (z in minZoom..maxZoom) {
            val xs = xRange(minLon, maxLon, z)
            val ys = yRange(minLat, maxLat, z)
            total += (xs.last - xs.first + 1L) * (ys.last - ys.first + 1L) * layersPerTile
        }
        return total
    }

    /**
     * 把 bbox × 级别展开成瓦片坐标序列（惰性，避免一次性生成几十万个对象）。
     * 顺序：级别由小到大、行优先 —— 先出低级别，用户能尽早看到"整片区域有底图"。
     */
    fun enumerate(
        minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        minZoom: Int, maxZoom: Int
    ): Sequence<TileCoord> = sequence {
        for (z in minZoom..maxZoom) {
            val xs = xRange(minLon, maxLon, z)
            val ys = yRange(minLat, maxLat, z)
            for (y in ys) {
                for (x in xs) {
                    yield(TileCoord(z, x, y))
                }
            }
        }
    }

    /**
     * 单个瓦片的地理范围（用于"沿轨迹下载"时判断瓦片是否与轨迹相交，暂未启用）。
     */
    fun tileBounds(c: TileCoord): DoubleArray {
        val n = size(c.zoom).toDouble()
        val west = c.x / n * 360.0 - 180.0
        val east = (c.x + 1) / n * 360.0 - 180.0
        val north = tileYToLat(c.y, c.zoom)
        val south = tileYToLat(c.y + 1, c.zoom)
        return doubleArrayOf(south, west, north, east) // [minLat, minLon, maxLat, maxLon]
    }

    /** 每像素米数（Web 墨卡托在赤道处的分辨率），用于"按屏幕尺度选级别" */
    fun resolutionMeters(lat: Double, zoom: Int): Double =
        156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom.toDouble())
}

/** 一个瓦片坐标（标准 XYZ，y 自上而下） */
data class TileCoord(val zoom: Int, val x: Int, val y: Int)
