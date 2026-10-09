package com.example.myfirstapp.globe

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** GL 世界坐标里地球的半径（单位随意，相机距离、星球位置都以它为基准） */
const val GLOBE_RADIUS = 1.0f

/** 一个标准 XYZ 瓦片坐标（Web 墨卡托，x 向东、y 向南） */
data class TileKey(val z: Int, val x: Int, val y: Int)

/** 球面上一对经纬度（单位：度） */
data class LonLat(val lon: Double, val lat: Double)

internal fun tileColumns(z: Int): Int = 1 shl z

/** 瓦片西边界经度 */
fun tileLonWest(x: Int, z: Int): Double = x.toDouble() / tileColumns(z) * 360.0 - 180.0

/** 瓦片东边界经度 */
fun tileLonEast(x: Int, z: Int): Double = tileLonWest(x + 1, z)

/** 瓦片北边界纬度（墨卡托反算，z=0 的顶边是 85.0511°） */
fun tileLatNorth(y: Int, z: Int): Double {
    val n = PI * (1.0 - 2.0 * y.toDouble() / tileColumns(z))
    return Math.toDegrees(atan(sinh(n)))
}

/** 瓦片南边界纬度 */
fun tileLatSouth(y: Int, z: Int): Double = tileLatNorth(y + 1, z)

/** 瓦片中心经纬度 */
fun tileCenter(t: TileKey): LonLat = LonLat(
    (tileLonWest(t.x, t.z) + tileLonEast(t.x, t.z)) / 2.0,
    (tileLatNorth(t.y, t.z) + tileLatSouth(t.y, t.z)) / 2.0
)

/** 四个子瓦片（左上、右上、左下、右下） */
fun tileChildren(t: TileKey): List<TileKey> {
    val x0 = t.x * 2
    val y0 = t.y * 2
    return listOf(
        TileKey(t.z + 1, x0, y0),
        TileKey(t.z + 1, x0 + 1, y0),
        TileKey(t.z + 1, x0, y0 + 1),
        TileKey(t.z + 1, x0 + 1, y0 + 1)
    )
}

/** 父瓦片（z=0 时返回自身） */
fun tileParent(t: TileKey): TileKey =
    if (t.z <= 0) t else TileKey(t.z - 1, t.x / 2, t.y / 2)

/**
 * 经纬度 → 球面向量。
 *
 * 约定（与 [GlobeRenderer] 里的着色器一致，改这里就要改 shader）：
 * x = cos(lat)·cos(lon)，y = sin(lat)（指向北极），z = cos(lat)·sin(lon)。
 */
fun sphereVec(lon: Double, lat: Double, radius: Float = GLOBE_RADIUS): FloatArray {
    val lr = Math.toRadians(lon)
    val br = Math.toRadians(lat)
    val cb = cos(br)
    return floatArrayOf(
        (cb * cos(lr) * radius).toFloat(),
        (sin(br) * radius).toFloat(),
        (cb * sin(lr) * radius).toFloat()
    )
}

/** 球面向量 → 经纬度（向量不必归一化） */
fun sphereVecToLonLat(v: FloatArray): LonLat {
    val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
    if (len <= 0.0) return LonLat(0.0, 0.0)
    val y = (v[1] / len).toDouble().coerceIn(-1.0, 1.0)
    return LonLat(
        Math.toDegrees(atan2(v[2].toDouble(), v[0].toDouble())),
        Math.toDegrees(asin(y))
    )
}

/** 两个向量的夹角（弧度） */
fun angleBetween(a: FloatArray, b: FloatArray): Double {
    val la = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()
    val lb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]).toDouble()
    if (la <= 0.0 || lb <= 0.0) return PI
    val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble() / (la * lb)
    return acos(d.coerceIn(-1.0, 1.0))
}

/** 向量长度 */
fun lengthOf(v: FloatArray): Double =
    sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()

/**
 * 瓦片的角半径（rad）：中心点到一个角的最大夹角，用于地平线剔除的粗判。
 * 高精度不重要，够 np.sqrt 就行。
 */
fun tileAngularRadius(t: TileKey): Double {
    val c = tileCenter(t)
    val center = sphereVec(c.lon, c.lat)
    val w = tileLonWest(t.x, t.z)
    val e = tileLonEast(t.x, t.z)
    val n = tileLatNorth(t.y, t.z)
    val s = tileLatSouth(t.y, t.z)
    var max = 0.0
    listOf(
        sphereVec(w, n), sphereVec(e, n),
        sphereVec(w, s), sphereVec(e, s)
    ).forEach { max = maxOf(max, angleBetween(center, it)) }
    return max
}

/**
 * 瓦片 C 在祖先 A 里的归一化子矩形：返回 (u0, v0, size)，u 向东、v 向南（= 纹理行方向），
 * 也就是 tile y 的方向。A == C 时返回 (0,0,1)。
 */
fun subTileRect(child: TileKey, ancestor: TileKey): FloatArray {
    var ax = 0.0
    var ay = 0.0
    var size = 1.0
    var level = ancestor.z
    while (level < child.z) {
        size *= 0.5
        val shift = child.z - 1 - level
        if (((child.x shr shift) and 1) == 1) ax += size
        if (((child.y shr shift) and 1) == 1) ay += size
        level++
    }
    return floatArrayOf(ax.toFloat(), ay.toFloat(), size.toFloat())
}
