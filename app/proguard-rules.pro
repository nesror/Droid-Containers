# zstd-jni：HA 等官方镜像的层是 zstd 压缩，本地解压用它（@aar 制品，Bionic 原生库）。
# 它的 native 代码用 GetFieldID / FindClass 按"名字"访问 Java 侧成员
# （dstPos/srcPos/srcSize 等），release 默认混淆会删除/重命名字段，
# 于是 initDStream 抛 NoSuchFieldError 并在 JNI 里直接 abort（应用闪退）。
# 必须整个包 keep：类名与字段名都要原样保留（对齐 home_assistant_flutter 项目）。
# 规则文件存在才会被加入 proguardFiles —— 不能省略本文件。
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { *; }

# WebView JS 桥（容器终端 TerminalBridge）：WebView 运行时按注解反射匹配可注入方法，
# 混淆后方法被重命名会导致 addJavascriptInterface 失效（终端黑屏无输入）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ktor-util 的 IntellijIdeaDebugDetector 引用了 JVM 专属的 java.lang.management.*，
# Android 上该类不存在，但对应代码路径（IDE 调试探测）永远不会执行，安全忽略
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
