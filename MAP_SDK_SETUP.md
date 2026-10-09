# 多地图 SDK 接入说明（高德 / 腾讯 / 百度）

本项目已经从「高德一个容器 + 第三方瓦片叠加」改造成 **按图源路由到对应厂商原生 SDK**：
选「腾讯街道」整个地图就是腾讯地图 SDK 渲染的，选「百度卫星」就是百度地图 SDK 渲染的。

代码改动已全部完成，**现在只剩一件事：去两家开放平台申请 Key 并填进 Manifest**。

---

## 一、现在的状态：两处占位符等你替换

文件：`app/src/main/AndroidManifest.xml`（第 42–52 行）

```xml
<!-- 已配置，不用动 -->
<meta-data android:name="com.amap.api.v2.apikey"
    android:value="8844b9b23ba616c86e95e7d45b1d31a8" />

<!-- ↓ 待替换：腾讯 -->
<meta-data android:name="TencentMapSDK"
    android:value="YOUR_TENCENT_MAP_KEY" />

<!-- ↓ 待替换：百度 -->
<meta-data android:name="com.baidu.lbsapi.API_KEY"
    android:value="YOUR_BAIDU_MAP_KEY" />
```

三个 meta-data 的名字都从 SDK 内部常量核实过（`javap` 反编译 AAR 得到），不要改名字，只改 value。

在替换之前，App 已经做了三层保护，不会白屏吓到用户：

| 保护 | 行为 |
|---|---|
| 图层面板角标 | 对应的卡片右上角显示红色「需 Key」 |
| 点击拦截 | 点这张卡会 Toast「尚未配置 XX 地图 Key…」，**拒绝切换** |
| 地图回落 | 万一已进入该厂商图源，`MapSurface` 自动回落到「高德矢量」 |

判断逻辑在 `map/MapEngineKeys.kt`：`isConfigured()` = 非空 **且** 不以 `YOUR_` 开头。
所以你替换后**务必把 `YOUR_...` 整串换掉**，别只删 `YOUR_` 留下后缀。

---

## 二、准备两个参数（两家都要）

申请时两家都要求填「包名 + SHA1」，本项目的值如下：

- **包名（Package name）**：`com.example.myfirstapp`
- **调试版 SHA1**：每台开发机不同，用下面的命令取

```bash
# Windows（Git Bash / PowerShell 均可）
keytool -list -v -keystore "C:\Users\<你的用户名>\.android\debug.keystore" ^
  -alias androiddebugkey -storepass android -keypass android
```

输出里找 `SHA1:` 那一行（一串用冒号分隔的十六进制）。

> 若 `~/.android/debug.keystore` 不存在，先在 Android Studio 里跑一次 App 生成它。
> 发布正式包时还要另填「发布版 SHA1」，取自你自己的签名 keystore。

建议把 **调试版和发布版 SHA1 都填进同一条 Key**，省得 debug 能跑 release 白屏。

---

## 三、腾讯地图 Key 申请步骤

1. 打开 <https://lbs.qq.com/dev/console/application/mine>，用 QQ 登录
2. 创建应用 → 填写应用名称（随便起，如「爬山」）
3. 在应用下「添加 Key」
   - 勾选 **`Android SDK`**（必须，勾错会导致鉴权失败）
   - Key 名称随便填
   - 填入包名 `com.example.myfirstapp`
   - 填入上面取到的 SHA1（调试版/发布版都填）
4. 提交后复制生成的 Key（一长串字母数字）
5. 替换 Manifest 里 `TencentMapSDK` 的 value

> 腾讯的 Key 有时提交后需要几分钟生效，立刻跑起来报「鉴权失败」可以先等一会再试。

---

## 四、百度地图 Key 申请步骤

1. 打开 <https://lbsyun.baidu.com/apiconsole/key#/home>，登录百度账号并完成开发者认证
2. 点「创建 AK」
   - 应用类型选 **`Android SDK`**
   - 应用名称随便填
   - 「数字签名/SHA1」填上面的 SHA1（BAIDU 支持一行一个，调试+发布都填进去）
   - 「包名」填 `com.example.myfirstapp`
3. 提交，复制生成的 AK
4. 替换 Manifest 里 `com.baidu.lbsapi.API_KEY` 的 value

> 百度 AK 跟包名+SHA1 强绑定，换机器 / 换签名后必须回来更新 SHA1，否则地图只出「未授权」水印。

---

## 五、替换完成后验证

1. **必须在 Android Studio 里编译运行**——本机命令行没有可用的 Gradle/Android SDK 组合
   （只有 JDK 25，Kotlin 2.0.20 编译器不兼容），无法在本机命令行验证编译。
2. 首次 Gradle Sync 会从 Maven Central 拉两家 SDK，耗时较长且有 30MB+ 增量。
3. 验证清单：
   - [ ] 图层面板里「腾讯街道 / 腾讯卫星 / 腾讯暗色 / 百度街道 / 百度卫星」五张卡片无「需 Key」角标
   - [ ] 逐个切换，地图能正常出图、**左下角是对应厂商自己的 logo**
   - [ ] 在腾讯/百度底图下拖动旋转，App 自己的蓝点/轨迹/起终点标记与底图不错位
   - [ ] 来回切 Tab、反复进出轨迹详情页不崩溃
   - [ ] 切回高德后「生成 3D 视频」仍能工作（见下）

---

## 六、本次改造的已知限制

| 限制 | 原因 | 表现 |
|---|---|---|
| **3D 视频导出只能用高德底图** | `TrackVideoExporter` 依赖高德 `TextureMapView` 内部的 TextureView 逐帧抓取 | 腾讯/百度/osmdroid 底图下点导出会提示「请先切回高德矢量/卫星」，并自动拦下而不是崩溃 |
| **WGS84 瓦片由 osmdroid 原生渲染** | 天地图/OpenTopoMap/自定义 WGS 图源路由到 osmdroid 引擎（原生 WGS-84 网格），不做逐像素重投影 | 选中这类图源会自动切到 osmdroid 引擎；GCJ02/BD09 图源仍走高德 `CustomTileProvider`；腾讯/百度引擎的 `applyOverlay` 是空实现 |
| **百度没有 SDK 自带的位置回调** | 百度 SDK 不内置定位客户端 | 百度底图下由 App 用高德定位 SDK 拿点、经 `updateDeviceLocation()` 喂给它；已在 `MapScreen`/`RecordScreen` 处理 |
| **APK 体积增加** | 三家 SDK 的 so | 已用 `abiFilters` 限制为 `arm64-v8a / armeabi-v7a / x86_64`；出正式包想再省 ~30MB 就把 `x86_64` 删掉（模拟器将装不上，真机不受影响） |

---

## 七、架构概览（改代码前必读）

```
ui/… 三个地图页
      └─ MapSurface（Compose 容器，按图源选引擎、管生命周期）
           └─ MapEngine（接口）  ← 业务层只认这个 + GeoPoint
                ├─ AMapEngine        (com.amap.api.maps.TextureMapView)
                ├─ TencentMapEngine  (com.tencent.tencentmap.mapsdk.maps.TextureMapView)
                ├─ BaiduMapEngine    (com.baidu.mapapi.map.MapView)
                └─ OsmdroidEngine    (org.osmdroid.views.MapView，第四家引擎，WGS84 原生渲染)

MapEnginePool：按「页面 × 厂商」池化，只创建不销毁，Activity ON_DESTROY 才释放
MapEngineKeys：从 Manifest meta-data 读三家 Key（osmdroid 开源、无 Key，恒可用）

天地图 / OpenTopoMap / 自定义 WGS：不接 SDK，由 **osmdroid 引擎原生渲染**。官方瓦片 REST API
（t{0-7}.tianditu.gov.cn/DataServer?T=vec_w/img_w/ter_w/cva_w/cia_w/cta_w&x&y&l&tk=Key）→ tdt.* /
opentopomap 等内置图源 → MapSource.engineKind 路由到 OSMDROID → OsmdroidEngine 按标准 XYZ 网格
直接下载贴图（WGS-84 同坐标系，无需逐像素重投影）；业务层 GCJ-02 坐标在引擎边界用 GeoTransform
转 WGS-84，轨迹/蓝点与底图天然对齐。Key（tk）存 MapSourceStore.tiandituKey（图源管理面板）。
GCJ02/BD09 的自定义瓦片图源仍走高德 CustomTileProvider。
```

三条使用纪律（写在 `map/MapEngine.kt` 头部注释里）：

1. **业务层不得 import 任何一家地图 SDK 的类** —— 只能用 `MapEngine` + `GeoPoint`
2. **内部坐标一律 GCJ-02** —— 百度靠 `SDKInitializer.setCoordType(CoordType.GCJ02)` 摆平；osmdroid 原生是 WGS-84，由 `OsmdroidEngine` 在引擎边界用 `GeoTransform` 做 GCJ-02↔WGS-84 点换算（非逐像素重投影）
3. **`clearOverlays()` 不能清掉底图瓦片层** —— 各家 SDK 的原生 `clear()` 会连瓦片一起清，所以引擎内部自己记账逐个 `remove()`

关键文件：

| 文件 | 作用 |
|---|---|
| `map/MapEngine.kt` | 抽象接口 + 使用纪律 |
| `map/MapSurface.kt` | Compose 容器，生命周期/Key 保护/回调转发 |
| `map/MapEnginePool.kt` | 实例池（规避各家 SDK 频繁 destroy 崩溃） |
| `map/MapEngineKeys.kt` | Key 读取与可用性判断 |
| `map/AMapEngine.kt` / `TencentMapEngine.kt` / `BaiduMapEngine.kt` / `OsmdroidEngine.kt` | 四家实现（osmdroid 为第四家，WGS84 原生渲染） |
| `utils/MapSdkPrivacy.kt` | 三家合规初始化（隐私同意后才能调） |
| `mapsources/MapSource.kt` | 图源表，`nativeType` 决定走哪个厂商原生渲染 |
