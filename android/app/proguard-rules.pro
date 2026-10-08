-keep class com.tg.dbisland.xposed.** { *; }
-keep class com.tg.dbisland.*Receiver { *; }
-keepclassmembers class com.tg.dbisland.** {
    @android.webkit.JavascriptInterface <methods>;
}

# 星河岛 SDK 0.1.0：跨进程走 Binder/AIDL，接口名与实现由框架按名解析，
# 且卡片类会经由 Parcel 传递，R8 无法静态看清全部引用，直接整体保留。
-keep class com.astraisland.** { *; }
-dontwarn com.astraisland.**
