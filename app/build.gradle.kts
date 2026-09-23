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

    // 调试工具（仅 debug 生效）
    debugImplementation(libs.androidx.ui.tooling)
}
