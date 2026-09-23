// 顶层构建文件：声明 Android Gradle Plugin 版本
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
