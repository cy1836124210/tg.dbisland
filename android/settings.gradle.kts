pluginManagement {
    repositories {
        // ---- 为什么镜像放在最前面（第 36 条加 Compose 时踩的坑）----
        // 本机 `repo.maven.apache.org` / `repo1.maven.org` **解析不到 IPv4**，
        // 而 `plugins.gradle.org` 对插件 jar 是 **303 重定向到 repo.maven.apache.org**
        // —— 于是「插件找不到」表现为 Gradle **静默挂住**（实测 20 分钟无任何缓存写入、
        // CPU 也不动），不是报错。阿里云/华为镜像可达且代理了 Maven Central，
        // 所以把它们放前面，官方源留作兜底。
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 顺序同 pluginManagement：可达镜像优先，官方源兜底。
        // AndroidX（Compose/Material3）只在这个组合下能稳定取到 AAR。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
    }
}
rootProject.name = "IslandBridge"
include(":app")
