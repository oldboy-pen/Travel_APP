package com.example.myfirstapp.mapsources

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 坐标换算工具：WGS84 / GCJ02 / BD09 / BD09MC（百度墨卡托）互转 + 瓦片像素计算。
 *
 * 背景：本 App 地图基于高德 SDK，内部按 GCJ-02 展示。叠加第三方瓦片时：
 * - GCJ02 图源（腾讯、高德自有瓦片）：直接按标准 XYZ 网格叠加，无偏差；
 * - WGS84 图源（天地图、OpenTopoMap 等）：每个像素需做 GCJ→WGS 反算，逐像素重投影；
 * - BD09 图源（百度）：需 GCJ→BD09 经纬 → 百度墨卡托(LL2MC 多项式) → 百度瓦片网格，
 *   同样逐像素重投影。
 *
 * 百度 LL2MC 系数表取自百度官方 JS API（与 gcoord 等开源库一致）。
 */
object GeoTransform {

    const val X_PI = PI * 3000.0 / 180.0
    private const val A = 6378245.0                    // 克拉索夫斯基椭球长半轴
    private const val EE = 0.00669342162296594323      // 第一偏心率平方
    private const val TILE_SIZE = 256.0

    // ==================== GCJ02 ←→ WGS84 ====================

    /** WGS84 → GCJ02（国测局加密，标准算法；境外坐标原样返回） */
    fun wgs84ToGcj02(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        val magic = 1 - EE * sin(radLat) * sin(radLat)
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(lng + dLng, lat + dLat)
    }

    /** GCJ02 → WGS84（迭代反算，收敛精度远优于 1e-7 度） */
    fun gcj02ToWgs84(lng: Double, lat: Double): DoubleArray {
        if (outOfChina(lng, lat)) return doubleArrayOf(lng, lat)
        var wgsLng = lng
        var wgsLat = lat
        var gcj = wgs84ToGcj02(wgsLng, wgsLat)
        var dLng = gcj[0] - lng
        var dLat = gcj[1] - lat
        var i = 0
        while (i < 4 && (abs(dLng) > 1e-9 || abs(dLat) > 1e-9)) {
            wgsLng -= dLng
            wgsLat -= dLat
            gcj = wgs84ToGcj02(wgsLng, wgsLat)
            dLng = gcj[0] - lng
            dLat = gcj[1] - lat
            i++
        }
        return doubleArrayOf(wgsLng, wgsLat)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    fun outOfChina(lng: Double, lat: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    // ==================== GCJ02 → BD09 ====================

    /** GCJ02 → BD09 经纬度（百度加密，封闭公式） */
    fun gcj02ToBd09(lng: Double, lat: Double): DoubleArray {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        return doubleArrayOf(z * cos(theta) + 0.0065, z * sin(theta) + 0.006)
    }

    // ==================== BD09 → BD09MC（百度墨卡托） ====================

    private val LLBAND = doubleArrayOf(75.0, 60.0, 45.0, 30.0, 15.0, 0.0)

    /** 每行 10 个数：多项式系数 c0..c8 + 纬度分带宽 c9 */
    private val LL2MC = arrayOf(
        doubleArrayOf(-0.0015702102444, 111320.7020616939, 1704480524535203.0, -10338987376042340.0, 26112667856603880.0, -35149669176653700.0, 26595700718403920.0, -10725012454188240.0, 1800819912950474.0, 82.5),
        doubleArrayOf(0.0008277824516172526, 111320.7020463578, 647795574.6671607, -4082003173.641316, 10774905663.51142, -15171875531.51559, 12053065338.62167, -5124939663.577472, 913311935.9512032, 67.5),
        doubleArrayOf(0.00337398766765, 111320.7020202162, 4481351.045890365, -23393751.19931662, 79682215.47186455, -115964993.2797253, 97236711.15602145, -43661946.33752821, 8477230.501135234, 52.5),
        doubleArrayOf(0.00220636496208, 111320.7020209128, 51751.86112841131, 3796837.749470245, 992013.7397791013, -1221952.21711287, 1340652.697009075, -620943.6990984312, 144416.9293806241, 37.5),
        doubleArrayOf(-0.0003441963504368392, 111320.7020576856, 278.2353980772752, 2485758.690035394, 6070.750963243378, 54821.18345352118, 9540.606633304236, -2710.55326746645, 1405.483844121726, 22.5),
        doubleArrayOf(-0.0003218135878613132, 111320.7020701615, 0.00369383431289, 823725.6402795718, 0.46104986909093, 2351.343141331292, 1.58060784298199, 8.77738589078284, 0.37238884252424, 7.45)
    )

    /** BD09 经纬度 → BD09MC 百度墨卡托（米，原点在赤道与本初子午线交点） */
    fun bd09ToBd09Mc(lng: Double, lat: Double): DoubleArray {
        var factors = LL2MC[5]
        for (i in LLBAND.indices) {
            if (abs(lat) > LLBAND[i]) {
                factors = LL2MC[i]
                break
            }
        }
        val cc = abs(lat) / factors[9]
        var xt = factors[0] + factors[1] * abs(lng)
        var yt = factors[2] + factors[3] * cc + factors[4] * cc.pow(2) + factors[5] * cc.pow(3) +
                factors[6] * cc.pow(4) + factors[7] * cc.pow(5) + factors[8] * cc.pow(6)
        if (lng < 0) xt = -xt
        if (lat < 0) yt = -yt
        return doubleArrayOf(xt, yt)
    }

    // ==================== 标准球面 Web 墨卡托（XYZ 瓦片网格，y 自上而下） ====================

    /** 经纬度 → 全球像素坐标（x 向东、y 向下，原点左上角 (-180,85.05)） */
    fun latLngToGlobalPixel(lat: Double, lng: Double, zoom: Int): DoubleArray {
        val n = TILE_SIZE * (1L shl zoom)
        val x = (lng + 180.0) / 360.0 * n
        val sinLat = sin(lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * n
        return doubleArrayOf(x, y)
    }

    /** XYZ 瓦片内像素 → 经纬度（瓦片左上角为 (0,0)；y 自上而下） */
    fun tilePixelToLatLng(x: Int, y: Int, zoom: Int, px: Int, py: Int): DoubleArray {
        val n = TILE_SIZE * (1L shl zoom)
        val lng = (x * TILE_SIZE + px) / n * 360.0 - 180.0
        val mercY = PI * (1.0 - 2.0 * (y * TILE_SIZE + py) / n)
        val lat = 180.0 / PI * (2.0 * atan(kotlin.math.exp(mercY)) - PI / 2.0)
        return doubleArrayOf(lng, lat)
    }

    // ==================== 百度瓦片网格 ====================

    /**
     * BD09MC（米）→ 百度全球像素坐标。
     * 百度 z18 分辨率 = 1 米/像素，故像素 = 米 × 2^(z-18)；原点 (0,0)，x 向东、y 向上。
     */
    fun bd09McToBaiduPixel(mx: Double, my: Double, zoom: Int): DoubleArray {
        val scale = 2.0.pow(zoom - 18)
        return doubleArrayOf(mx * scale, my * scale)
    }

    /** 百度瓦片 URL 用的坐标字符串：负数加 "M" 前缀（如 -3 → "M3"） */
    fun baiduTileCoord(v: Int): String = if (v >= 0) v.toString() else "M" + (-v)

    /** 计算某 BD09 经纬度在百度瓦片网格的瓦片号（z 级） */
    fun baiduTileOf(lngBd: Double, latBd: Double, zoom: Int): IntArray {
        val mc = bd09ToBd09Mc(lngBd, latBd)
        val px = bd09McToBaiduPixel(mc[0], mc[1], zoom)
        return intArrayOf(floor(px[0] / TILE_SIZE).toInt(), floor(px[1] / TILE_SIZE).toInt())
    }

    // ==================== 通用工具 ====================

    /** TMS y 反转（腾讯瓦片用）：标准 XYZ y → 自下而上 y */
    fun tmsY(xyzY: Int, zoom: Int): Int = (1 shl zoom) - 1 - xyzY
}
