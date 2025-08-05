# R8专用配置
-dontwarn **
-ignorewarnings

# 保持所有类的原始结构
-keep,allowobfuscation,allowshrinking class * {
    <fields>;
    <methods>;
}

# 完全保护Go绑定相关
-keep class shared_backend.** { *; }
-keep class go.** { *; }

# 保护所有JNI相关
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 禁用内联优化（R8特有）
-keepclassmembers,allowobfuscation class * {
    @com.google.errorprone.annotations.DoNotCall <methods>;
}

# 保护所有注解
-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses

# 保护反射使用的类
-keepclassmembers class * {
    public <init>(...);
    public static <fields>;
    public static <methods>;
}

# R8特殊：保护可能被动态调用的方法
-keep class * {
    public <methods>;
    public <fields>;
}

# ===== 增强Go运行时保护 =====

# 保护io包和相关操作
-keep class java.io.** { *; }
-keep interface java.io.** { *; }

# 保护管道和流操作
-keep class * implements java.io.Closeable { *; }
-keep class * implements java.io.Flushable { *; }

# 保护并发和上下文操作
-keep class java.util.concurrent.** { *; }
-keep class * implements java.lang.Runnable { *; }

# 保护Go的上下文管理
-keep class go.** {
    *;
}

# 禁用可能破坏io操作的优化
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# 保护错误处理
-keep class * extends java.lang.Exception { *; }
-keep class * extends java.lang.Error { *; }

# 保护内存管理
-keep class java.lang.ref.** { *; }

# R8特殊：禁用激进优化
-dontshrink
-dontoptimize