package com.example.myfirstapp.offline

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 离线存储根目录与全局 Context。
 *
 * 目录落在应用私有目录（无需任何存储权限），卸载时随 App 一起清掉。
 * 瓦片存档与高程库都放在这里。
 */
object OfflineStorage {

    @Volatile
    private var ctx: android.content.Context? = null

    /** 幂等初始化（Application / MainActivity / 各入口都会调） */
    fun init(context: android.content.Context) {
        if (ctx == null) {
            synchronized(this) {
                if (ctx == null) ctx = context.applicationContext
            }
        }
    }

    private fun context(): android.content.Context =
        ctx ?: error("OfflineStorage 未初始化：请先调用 OfflineStorage.init(context)")

    /** 离线根目录 */
    val rootDir: File
        get() = File(context().filesDir, "offline").apply { mkdirs() }

    /** 瓦片存档目录（每个离线区域一个 mbtiles 文件） */
    val regionsDir: File
        get() = File(rootDir, "regions").apply { mkdirs() }

    /** 某个区域的存档文件 */
    fun regionFile(regionId: String): File = File(regionsDir, "$regionId.mbtiles")

    /** 高程（terrarium 栅格）存档 */
    val elevationFile: File get() = File(rootDir, "elevation.mbtiles")

    /** 已用磁盘字节数（区域存档 + 高程库） */
    fun usedBytes(): Long = runCatching { dirSize(rootDir) }.getOrDefault(0L)

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        val files = dir.listFiles() ?: return 0L
        var sum = 0L
        for (f in files) sum += if (f.isDirectory) dirSize(f) else f.length()
        return sum
    }

    /** 删掉整个离线目录（UI 的"清空离线数据"） */
    fun clearAll(): Boolean = runCatching { rootDir.deleteRecursively() }.getOrDefault(false)
}

/**
 * MBTiles 1.1 规范的瓦片存档（SQLite）。
 *
 * 选 MBTiles 而不是自己定 schema，是因为 **osmdroid 原生就能读**：
 * `org.osmdroid.tileprovider.modules.MBTilesFileArchive` 认的就是
 * `tiles(zoom_level, tile_column, tile_row, tile_data)` 四列，
 * 于是离线渲染可以直接复用官方的 MapTileFileArchiveProvider，不必再写一个 module provider。
 *
 * ★ 行号方向：MBTiles 用 **TMS**（y 自下而上），App 内部与 HTTP 模板用 **XYZ**（y 自上而下）。
 *   转换只发生在写库/读库这一瞬间（[TileGrid.tmsY]），其余地方一律 XYZ。
 *
 * ★ 不用 SQLiteOpenHelper：它强制要 Context，而下载在 Service 里跑，
 *   直接用 `SQLiteDatabase.openOrCreateDatabase` 更干净（schema 自己建）。
 */
class MbTiles(val file: File) {

    @Volatile
    private var db: SQLiteDatabase? = null

    private fun db(): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        file.parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(file, null)
        d.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COL_ZOOM INTEGER, $COL_COLUMN INTEGER, $COL_ROW INTEGER, $COL_DATA BLOB)"
        )
        d.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_tile ON $TABLE " +
                "($COL_ZOOM, $COL_COLUMN, $COL_ROW)"
        )
        // MBTiles 规范要求的元数据表（osmdroid 不读，但保持文件合规，可被 QGIS 等工具打开）
        d.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        db = d
        return d
    }

    /** 预建库（下载前调用，避免第一个事务时才建表） */
    fun ensureOpen() {
        db()
    }

    /** 写一条元数据（图源名/bbox 等，便于事后排查） */
    fun putMeta(name: String, value: String) {
        runCatching {
            db().execSQL(
                "INSERT OR REPLACE INTO metadata (name, value) VALUES (?, ?)",
                arrayOf<Any?>(name, value)
            )
        }
    }

    fun getMeta(name: String): String? = runCatching {
        db().rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(name)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /** 写入一张瓦片（XYZ 坐标）。已存在则覆盖（续传/更新） */
    fun putTile(z: Int, x: Int, y: Int, data: ByteArray) {
        val cv = ContentValues().apply {
            put(COL_ZOOM, z)
            put(COL_COLUMN, x)
            put(COL_ROW, TileGrid.tmsY(y, z))
            put(COL_DATA, data)
        }
        runCatching {
            db().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** 批量写入（单事务，比逐条 insert 快一个数量级） */
    fun putTiles(tiles: List<Pair<TileCoord, ByteArray>>) {
        if (tiles.isEmpty()) return
        runCatching {
            val d = db()
            d.beginTransaction()
            try {
                val cv = ContentValues()
                for ((coord, data) in tiles) {
                    cv.clear()
                    cv.put(COL_ZOOM, coord.zoom)
                    cv.put(COL_COLUMN, coord.x)
                    cv.put(COL_ROW, TileGrid.tmsY(coord.y, coord.zoom))
                    cv.put(COL_DATA, data)
                    d.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    /** 读一张瓦片（XYZ 坐标），没有则 null */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? = runCatching {
        db().query(
            TABLE, arrayOf(COL_DATA),
            "$COL_COLUMN=? AND $COL_ROW=? AND $COL_ZOOM=?",
            arrayOf(x.toString(), TileGrid.tmsY(y, z).toString(), z.toString()),
            null, null, null
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }
    }.getOrNull()

    /** 是否已缓存（比 getTile 省一次 BLOB 拷贝，供覆盖率统计用） */
    fun hasTile(z: Int, x: Int, y: Int): Boolean = runCatching {
        db().query(
            TABLE, arrayOf(COL_ROW),
            "$COL_COLUMN=? AND $COL_ROW=? AND $COL_ZOOM=?",
            arrayOf(x.toString(), TileGrid.tmsY(y, z).toString(), z.toString()),
            null, null, null
        ).use { it.moveToFirst() }
    }.getOrDefault(false)

    /** 瓦片总数 */
    fun count(): Long = runCatching {
        db().rawQuery("SELECT count(*) FROM $TABLE", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrDefault(0L)

    /** 某级别的瓦片数（覆盖率展示用） */
    fun countAtZoom(z: Int): Long = runCatching {
        db().rawQuery("SELECT count(*) FROM $TABLE WHERE $COL_ZOOM=?", arrayOf(z.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }.getOrDefault(0L)

    /** 存档内所有级别（升序） */
    fun zooms(): List<Int> = runCatching {
        val out = ArrayList<Int>()
        db().rawQuery("SELECT DISTINCT $COL_ZOOM FROM $TABLE ORDER BY $COL_ZOOM", null).use {
            while (it.moveToNext()) out.add(it.getInt(0))
        }
        out
    }.getOrDefault(emptyList())

    /** 占用字节数：优先用文件大小（含 SQLite 页开销，更接近真实占用） */
    fun sizeBytes(): Long {
        val f = file.length()
        if (f > 0) return f
        return runCatching {
            db().rawQuery("SELECT sum(length($COL_DATA)) FROM $TABLE", null)
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        }.getOrDefault(0L)
    }

    /** 删除某级别全部瓦片（"只保留到 z14"这类裁剪） */
    fun deleteZoom(z: Int) {
        runCatching {
            db().delete(TABLE, "$COL_ZOOM=?", arrayOf(z.toString()))
            db().execSQL("VACUUM")
        }
    }

    /** 关闭连接（删文件前必须调，否则句柄占着删不掉） */
    fun close() {
        runCatching { db?.close() }
        db = null
    }

    /** 删除存档文件及 SQLite 伴生文件 */
    fun deleteFile(): Boolean {
        close()
        for (suffix in arrayOf("-journal", "-wal", "-shm")) {
            runCatching { File(file.parentFile, file.name + suffix).delete() }
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    companion object {
        const val TABLE = "tiles"
        const val COL_ZOOM = "zoom_level"
        const val COL_COLUMN = "tile_column"
        const val COL_ROW = "tile_row"
        const val COL_DATA = "tile_data"

        /** 打开（不存在则创建）一个存档 */
        fun open(file: File): MbTiles = MbTiles(file).apply { ensureOpen() }
    }
}
