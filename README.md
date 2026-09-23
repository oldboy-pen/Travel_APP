# MyFirstApp — Android 户外轨迹示例项目

基于 **Kotlin + Jetpack Compose + MVVM**，一个类似「两步路」的户外运动 App，包含：

- **待办清单**：Compose 入门
- **地图**：高德地图、定位、驾车路线规划、一键导航
- **运动**：后台轨迹记录（前台服务保活）、实时距离/时长/均速/爬升、途经点打点
- **轨迹库**：历史轨迹列表、轨迹回放、GPX 导入/导出/分享

## 项目结构

```
MyFirstApp/
├── settings.gradle.kts          # 模块配置 + 国内镜像加速
├── build.gradle.kts             # 顶层构建文件
├── gradle.properties            # Gradle 全局配置
├── gradle/
│   ├── libs.versions.toml       # 依赖版本统一管理（版本目录）
│   └── wrapper/
│       └── gradle-wrapper.properties
└── app/                         # 主应用模块
    ├── build.gradle.kts         # 模块构建配置 + 依赖声明
    ├── proguard-rules.pro       # 混淆规则
    └── src/main/
        ├── AndroidManifest.xml  # 权限、前台服务、高德Key、FileProvider
        ├── res/                 # 资源（字符串、主题、图标、分享路径）
        └── java/com/example/myfirstapp/
            ├── MainActivity.kt          # 入口：隐私弹窗 + 高德合规初始化
            ├── data/
            │   ├── TodoViewModel.kt     # 待办：MVVM 状态管理
            │   └── MapViewModel.kt      # 地图：定位 + 路径规划
            ├── track/                   # ★ 轨迹功能包
            │   ├── TrackData.kt         #   数据模型 + Haversine 距离/降噪
            │   ├── TrackRecorder.kt     #   记录器单例：定位采样→轨迹/数据
            │   ├── TrackRecordingService.kt # 前台服务：息屏保活+常驻通知
            │   └── TrackRepository.kt   #   本地JSON存储 + GPX导入导出
            ├── utils/
            │   └── AMapPrivacy.kt       # 高德SDK隐私合规（必须！）
            └── ui/
                ├── navigation/
                │   └── AppNavHost.kt    # 底部导航（4 Tab）+ 详情路由
                ├── theme/               # Material 3 主题
                └── screens/
                    ├── TodoScreen.kt    # 待办界面
                    ├── MapScreen.kt     # 地图界面
                    ├── RecordScreen.kt  # ★ 运动记录界面
                    ├── TrackHistoryScreen.kt # ★ 轨迹库
                    └── TrackDetailScreen.kt  # ★ 轨迹回放/详情
```

## 核心知识点

| 文件 | 学到什么 |
|------|----------|
| `TrackRecorder.kt` | 单例状态容器、StateFlow、GPS 降噪（漂移/跳点过滤）、累计爬升算法 |
| `TrackRecordingService.kt` | 前台服务（foregroundServiceType=location）、通知渠道、息屏保活 |
| `TrackRepository.kt` | 文件持久化、GPX 标准格式生成与 XmlPullParser 解析 |
| `RecordScreen.kt` | 复杂状态驱动 UI、运行时权限、MapView 生命周期绑定 |
| `TrackDetailScreen.kt` | FileProvider 分享文件、地图回放轨迹、AlertDialog 确认交互 |
| `MapScreen.kt` | AndroidView 嵌入传统 View、地图覆盖物 |
| `AppNavHost.kt` | Navigation Compose 多 Tab + 全屏详情页 |
| `MainActivity.kt` | 隐私弹窗、高德SDK合规初始化 |

## 运行前必读

### 高德 Key（必须）

1. 注册 [高德开放平台控制台](https://console.amap.com/dev/key/app) → 创建应用 → 添加 Key（Android 平台）
2. 包名 `com.example.myfirstapp` + 调试 SHA1：

```
keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

3. 替换 `AndroidManifest.xml` 中的 `REPLACE_WITH_YOUR_AMAP_KEY`

### 轨迹记录功能说明

| 功能 | 说明 |
|------|------|
| 开始记录 | 「运动」Tab → 开始记录（自动申请定位+通知权限，启动前台服务） |
| 息屏记录 | 前台服务保活。若厂商系统仍杀后台，请把 App 加入电池白名单，定位权限选"**始终允许**" |
| 途经点 | 记录中点「打点」，轨迹库详情页可查看 |
| 结束 | 自动保存 → 跳转轨迹详情（回放） |
| GPX 导出 | 详情页右上角分享按钮，可导入两步路/奥维/Garmin 等 |
| GPX 导入 | 轨迹库页「导入 GPX」选择 .gpx 文件 |

### 常见问题

| 现象 | 原因/解决 |
|------|-----------|
| 地图空白 | Key 无效：检查包名、SHA1；替换后卸载重装 |
| 息屏后轨迹中断 | 定位权限改"始终允许"；App 加入电池优化白名单 |
| 爬升数据偏大 | GPS 海拔噪声，已做 1 米阈值过滤，长时间记录仍需算法优化 |
| 模拟器无定位 | Extended Controls → Location 手动设置坐标 |
| 通知不显示 | Android 13+ 需同意通知权限 |

## 运行方式

1. Android Studio 打开项目根目录，等待 Gradle Sync
2. 完成高德 Key 配置
3. 真机运行（轨迹记录建议真机 + 户外实测）

## 建议的下一步学习路线

1. **Room 数据库**：轨迹存储从 JSON 文件迁移到 Room
2. **轨迹回放动画**：详情页加"播放"按钮，按时间轴动画重绘轨迹
3. **离线地图**：高德离线地图包下载，无网络户外可用
4. **路书功能**：轨迹另存为"线路"，可导航跟随已有线路（两步路"线路导航"）
5. **运动类型**：徒步/骑行/跑步分类记录与统计图表


