pluginManagement {
    repositories {
        // 国内镜像加速，海外网络可删除这两行
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/public")
        // 腾讯/百度的 SDK 都发布在 Maven Central；腾讯镜像作为兜底，防止
        // 阿里 public 仓库未及时同步到最新版本号导致解析失败
        maven(url = "https://mirrors.tencent.com/repository/maven/tencent_public/")
        google()
        mavenCentral()
    }
}

rootProject.name = "爬山"
include(":app")
