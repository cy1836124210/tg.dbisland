import java.io.StringReader
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose 编译器插件：版本必须与 Kotlin 插件完全一致（见根 build.gradle.kts 的说明）。
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 正式签名（审核问题 4）----
// 老版本提交的是 assembleDebug 的产物：调试证书 + android:debuggable=true。
// 现在 release 构建读取 android/keystore.properties 里的自建 release 密钥库；
// 未提供时仍可构建（只是不签名），方便他人复现。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // 去掉可能的 UTF-8 BOM：带 BOM 时第一个键会变成 "\uFEFFstoreFile"，
        // 导致签名配置静默失效、打包出未签名的 app-release-unsigned.apk。
        val text = keystorePropsFile.readText().removePrefix("\uFEFF")
        StringReader(text).use { load(it) }
    }
}
val releaseStore = keystoreProps.getProperty("storeFile")?.trim()?.takeIf { it.isNotEmpty() }
val hasReleaseKey = releaseStore != null

android {
    namespace = "com.tg.dbisland"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tg.dbisland"
        minSdk = 26
        targetSdk = 36
        // 1.1：安全整改（签名级权限 / 仅本机监听 / 可卸载 root 组件）
        //      + 迁移到星河岛 SDK 0.1.0（通信版本 7）
        versionCode = 3
        versionName = "1.2"
    }
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 明确关闭调试标志：正式包不允许 android:debuggable=true
            isDebuggable = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro")
        }
        debug {
            // debug 包保持可调试，仅用于本地开发；上架必须用 release 产物
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        // v1.2 第 36 条：整个界面重做成 Compose（liquid glass + 底部 dock + 三页）。
        // 旧实现是纯 View 的 LinearLayout + chat.html WebView，已整体替换。
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = false
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    // 星河岛 SDK 0.1.0（通信版本 7）。旧的 astraisland-client 接入库已被上游
    // 停止提供、新版星流不再接受其连接，必须换到这一版。
    implementation(files("libs/astraisland-sdk-0.1.0.aar"))
    compileOnly(files("libs/api-82.jar"))   // classic Xposed API stub (api.xposed.info)
    implementation("androidx.core:core-ktx:1.17.0")
    // okhttp 已随电脑端（SSE）一起删除：仓库里再无 HTTP 客户端调用，
    // 依赖一并移除，避免 APK 里留一个用不到的联网库（CHANGELOG 第 34 条）。
    implementation("org.json:json:20240303")

    // ---- Compose（第 36 条界面重做）----
    // 版本**刻意钉死**成 Gradle 缓存里已有的那一组（ui/foundation/runtime 1.10.4、
    // animation 1.10.4、material3 1.4.0、activity-compose 1.8.2），
    // 不用 BOM（BOM 会把 ui 拉到 1.8.x，反而要重新下载）。
    // 纯 Compose 材料全部来自 androidx —— **不引入第三方 UI 库**：
    // SukiSU 用的 miuix-kmp / kyant0:backdrop 不在缓存里且 Maven Central 在本机
    // 不可达，所以液体玻璃按同源 Apache-2.0 算法自己实现
    // （`ui/glass/GlassShader.kt` 里已标注出处）。
    implementation("androidx.compose.ui:ui:1.10.4")
    implementation("androidx.compose.ui:ui-graphics:1.10.4")
    implementation("androidx.compose.foundation:foundation:1.10.4")
    implementation("androidx.compose.runtime:runtime:1.10.4")
    implementation("androidx.compose.animation:animation:1.10.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    // 图标：material-icons-extended 最后一个稳定版就是 1.7.8（1.8+ 已冻结），
    // 与 ui 1.10.4 只是共用矢量图标 API，混用安全。
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.4")
    debugImplementation("androidx.compose.ui:ui-tooling:1.10.4")
    testImplementation("junit:junit:4.13.2")
}

// ---- 产物落到 dist/ 并附 SHA-256，便于核对提交的确实是 release 包 ----
tasks.register("distRelease") {
    dependsOn("assembleRelease")
    doLast {
        val apk = layout.buildDirectory
            .file("outputs/apk/release/app-release.apk").get().asFile
        if (!apk.exists()) throw GradleException("release APK 不存在: $apk")
        val dir = rootProject.file("dist").apply { mkdirs() }
        val name = "doubaodao-v${android.defaultConfig.versionName}-release.apk"
        val dst = File(dir, name)
        apk.copyTo(dst, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(dst.readBytes()).joinToString("") { "%02x".format(it) }
        File(dir, "$name.sha256").writeText("$sha  $name\n")
        println("release APK -> $dst")
        println("sha256      -> $sha")
    }
}
