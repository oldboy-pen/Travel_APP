plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.myfirstapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myfirstapp"
        minSdk = 24                 // 覆盖 Android 7.0+，约 97% 设备
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 三个地图 SDK 都带多架构 so，不过滤的话 APK 会到 200MB+。
        // arm64-v8a / armeabi-v7a 覆盖全部真机；x86_64 是给 Android 模拟器用的，
        // 出正式包想再省 ~30MB 就删掉它（删了 x86 模拟器装不上，但真机不受影响）。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 上线前改为 true 开启代码混淆
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // 核心库
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM：统一管理所有 Compose 库版本
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 扩展图标集（Hiking/Route/Flag 等图标在此库中）
    implementation(libs.androidx.material.icons.extended)

    // ViewModel 与 Compose 集成 + 生命周期感知的状态收集
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 页面导航（底部 Tab 切换 待办/地图）
    implementation(libs.androidx.navigation.compose)

    // 高德地图：3D 地图 SDK（v5.0.0 起已内置定位功能，无需再单独引入定位 SDK）+ 路径规划
    implementation(libs.amap.map3d)
    implementation(libs.amap.search)

    // 腾讯地图（原生引擎，map.TencentMapEngine 用）
    implementation(libs.tencent.map.sdk)
    implementation(libs.tencent.map.foundation)

    // 百度地图（原生引擎，map.BaiduMapEngine 用；会传递依赖 com.baidu.lbsyun:base）
    implementation(libs.baidu.map.sdk)

    // 二维码：扫码添加图源（embedded 内置 CaptureActivity）+ 生成分享二维码
    implementation(libs.zxing.embedded)
    implementation(libs.zxing.core)

    // 调试工具（仅 debug 生效）
    debugImplementation(libs.androidx.ui.tooling)

    // 单元测试
    testImplementation("junit:junit:4.13.2")
}
