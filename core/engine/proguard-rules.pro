# zstd-jni（@aar 制品）：native 代码通过 GetFieldID 按名访问 ZstdInputStreamNoFinalizer 的
# dstPos/srcPos/srcSize 字段；release 默认混淆会删除字段名导致 JNI abort 闪退。
# 必须整个包 keep（对齐 home_assistant_flutter 项目已验证配置）。
# 此文件必须存在且被加入 proguardFiles（app 模块同样需要保留一份）。
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { *; }
