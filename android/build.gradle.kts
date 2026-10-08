plugins {
    id("com.android.application") version "8.9.3" apply false
    // Kotlin 2.2.10 是**与 Compose 编译器插件版本必须一致**的那个版本：
    //   · Gradle 缓存里同时存在 kotlin-gradle-plugin 2.2.10 与
    //     kotlin-compose-compiler-plugin-embeddable 2.2.10（本机已具备，离线可解）；
    //   · 缓存里的 2.1.20 没有配套的 Compose 编译器插件，无法开启 compose = true。
    // 两者任一升级都必须成对改，否则 Kotlin 会报 "Compose Compiler 版本不匹配"。
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
