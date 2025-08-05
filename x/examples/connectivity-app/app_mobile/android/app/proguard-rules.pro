# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


#############################################
#
# 对于一些基本指令的添加
#
#############################################

#代码混淆压缩比，在0~7之间，默认为5，一般不做修改
-optimizationpasses 5

#混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

#指定不去忽略非公共库的类
-dontskipnonpubliclibraryclasses

#这句话能够使我们的项目混淆后产生映射文件
#包含有类名->混淆后类名的映射关系
-verbose

#指定不去忽略非公共库的类成员
-dontskipnonpubliclibraryclassmembers

#不做预校验，preverify是proguard的四个步骤之一，Android不需要preverify，去掉这一步能够加快混淆速度。
-dontpreverify

#避免混淆Annotation、内部类、泛型、匿名类
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,Exceptions,Deprecated

#抛出异常时保留代码行号
-keepattributes SourceFile,LineNumberTable

#指定混淆是采用的算法，后面的参数是一个过滤器
#这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

#不进行优化，建议使用此选项，理由见上
-dontoptimize


#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################

#保留我们使用的四大组件，自定义的Application等等这些类不被混淆
#因为这些子类都有可能被外部调用
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View
-keep public class com.android.vending.licensing.ILicensingService
#androidx start

-keep class com.google.android.material.** {*;}
-keep interface com.google.android.material.** {*;}
-keep class androidx.** {*;}
-keep public class * extends androidx.**
-keep interface androidx.** {*;}
-dontwarn com.google.android.material.**
-dontnote com.google.android.material.**
-dontwarn androidx.**

#androidx end


#保留support下的所有类及其内部类、继承的
-dontwarn android.support.**
-keep class android.support.** {*;}
-keep public class * extends android.support.v4.**
-keep public class * extends android.support.v7.**
-keep public class * extends android.support.annotation.**

#开启@Keep注解
-keep @androidx.annotation.Keep class *
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
#multidex
-keep class androidx.multidex.**{*;}

#保留support下的所有类及其内部类、继承的
#-dontwarn android.support.**
#-keep class android.support.** {*;}
#-keep public class * extends android.support.v4.**
#-keep public class * extends android.support.v7.**
#-keep public class * extends android.support.annotation.**
##开启@Keep注解
#-keep @androidx.annotation.Keep class *
#-keepclassmembers class * {
#    @androidx.annotation.Keep *;
#}


#保留R下面的资源
-keep class **.R$* {*;}
-keep public class **.R$*{
public static final int *;
}

#保留本地native方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

#保留在Activity中的方法参数是view的方法，
#这样以来我们在layout中写的onClick就不会被影响
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

#保留枚举类不被混淆
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留我们自定义控件（继承自View）不被混淆
-keep public class * extends android.view.View {
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 保留Parcelable序列化类不被混淆
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# 保留Serializable序列化的类不被混淆
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keepattributes Signature

# 对于带有回调函数的onXXEvent、**On*Listener的，不能被混淆
-keepclassmembers class * {
    void *(**On*Event);
    void *(**On*Listener);
}

#WebView处理，没有使用到WebView忽略
-keepattributes *JavascriptInterface*,SetJavaScriptEnabled
#保留JavascriptInterface中的方法
-keep public class android.webkit.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.gsmc.ued.ui.utils.JsTools {
    public *;
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
}

-keep class org.apache.http.** {*;}
-dontwarn org.apache.http.**

-keep class android.net.http.** {*;}
-dontwarn android.net.http.**


#############################################
#
# 三方库
#
#############################################

#common-library
-dontwarn com.gsmc.commonlibrary.widget.**
-keep class com.gsmc.commonlibrary.widget.** {*;}
#common-library inner nineoldandroids library
-dontwarn com.nineoldandroids.**
-keep class com.nineoldandroids.** {*;}
#common-library inner mmkv library
-dontwarn com.tencent.mmkv.**
-keep class com.tencent.mmkv.** {*;}
#common-library inner glide library
-dontwarn com.bumptech.glide.**
-keep class com.bumptech.glide.** {*;}
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
#glide如果项目API级别<=Android API 27则添加
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder

#common-library inner gson library
-dontwarn com.google.**
-keep class com.google.gson.** {*;}
-keep class com.google.protobuf.** {*;}

#butterknife
-keep class butterknife.** {*;}
-dontwarn butterknife.internal.**
-keep class **$$ViewBinder {*;}
-keepclasseswithmembernames class * {
    @butterknife.* <fields>;
}
-keepclasseswithmembernames class * {
    @butterknife.* <methods>;
}

#fresco
-dontwarn com.facebook.**
-keep class com.facebook.** {*;}
-dontwarn bolts.**
-keep class bolts.** {*;}

#BaseRecyclerViewAdapterHelper
-keep class com.chad.library.adapter.** {*;}
-keep public class * extends com.chad.library.adapter.base.BaseQuickAdapter
-keep public class * extends com.chad.library.adapter.base.viewholder.BaseViewHolder
-keepclassmembers  class **$** extends com.chad.library.adapter.base.viewholder.BaseViewHolder {
     <init>(...);
}

#logger
-dontwarn com.orhanobut.logger.**
-keep class com.orhanobut.logger.** {*;}
-keep interface com.orhanobut.logger.** {*;}

#retrofit2
-dontwarn retrofit2.**
-keep class retrofit2.** {*;}
-keep interface retrofit2.** {*;}

#retrofit2 inner okhttp3
-dontwarn okhttp3.**
-keep class okhttp3.** {*;}

#okio
-dontwarn okio.**
-dontwarn com.squareup.javawriter.**
-keep class okio.** {*;}
-keep class com.squareup.javawriter.** {*;}

#rxjava2 & rxandroid
-dontwarn io.reactivex.**
-keep class io.reactivex.** {*;}
-dontwarn org.reactivestreams.**
-keep class org.reactivestreams.** {*;}

#autodispose
-dontwarn com.uber.autodispose.**
-keep class com.uber.autodispose.** {*;}
-dontwarn android.arch.**
-keep class android.arch.** {*;}

#rxbus
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations
-keep enum com.hwangjr.rxbus.thread.EventThread {*;}
-dontwarn com.hwangjr.rxbus.**
-keep class com.hwangjr.rxbus.** {*;}

#recyclerview-flexibledivider
-dontwarn com.yqritc.recyclerviewflexibledivider.**
-keep class com.yqritc.recyclerviewflexibledivider.** {*;}

#router
-dontwarn com.alibaba.android.arouter.**
-keep public class com.alibaba.android.arouter.** {*;}
-keep class * implements com.alibaba.android.arouter.facade.template.ISyringe {*;}
#如果使用了 byType 的方式获取 Service，需添加下面规则，保护接口
-keep interface * implements com.alibaba.android.arouter.facade.template.IProvider
#如果使用了 单类注入，即不定义接口实现 IProvider，需添加下面规则，保护实现
-keep class * implements com.alibaba.android.arouter.facade.template.IProvider

#FlycoDialog
-dontwarn com.flyco.**
-keep class com.flyco.** {*;}

#download-manager
-dontwarn com.gsmc.downloadmanager.**
-keep class com.gsmc.downloadmanager.** {*;}

#Android-PickerView
-dontwarn com.bigkoo.pickerview.**
-keep class com.bigkoo.pickerview.** {*;}
-dontwarn com.contrarywind.**
-keep class com.contrarywind.** {*;}

#walle
-dontwarn com.meituan.android.walle.**
-keep class com.meituan.android.walle.** {*;}

#umeng
-dontwarn com.umeng.**
-keep class com.umeng.** {*;}
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn com.uc.**
-keep class com.uc.** {*;}
-dontwarn com.efs.**
-keep class com.efs.** {*;}

-keep class org.repackage.** {*;}





#jpush
-dontwarn cn.jpush.**
-keep class cn.jpush.** {*;}
-keep class * extends cn.jpush.android.service.JPushMessageReceiver {*;}
-dontwarn cn.jiguang.**
-keep class cn.jiguang.** {*;}

#libraryQrcodeZxing
-dontwarn com.fanwe.**
-keep class com.fanwe.** {*;}
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** {*;}

#tinypinyin
-dontwarn com.github.promeg.pinyinhelper.**
-keep class com.github.promeg.pinyinhelper.** {*;}

#universalimageloader
-dontwarn com.nostra13.universalimageloader.**
-keep class com.nostra13.universalimageloader.** { *; }

#eventbus
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }
# Only required if you use AsyncExecutor
-keepclassmembers class * extends org.greenrobot.eventbus.util.ThrowableFailureEvent {
    <init>(java.lang.Throwable);
}

#MagicIndicator
-dontwarn net.lucode.hackware.magicindicator.**
-keep class net.lucode.hackware.magicindicator.** {*;}

#filedownloader
-dontwarn com.liulishuo.filedownloader.**
-keep class com.liulishuo.filedownloader.** {*;}

#CardviewFix
-dontwarn com.bigman.wmzx.**
-keep class com.bigman.wmzx.** {*;}

#netease captcha
-keep public class com.netease.nis.captcha.**{*;}

#rxpermissions
-dontwarn com.tbruyelle.rxpermissions2.**
-keep class com.tbruyelle.rxpermissions2.** {*;}

#cropiwa
-dontwarn com.steelkiwi.cropiwa.**
-keep class com.steelkiwi.cropiwa.** {*;}

#Sophix
#基线包打包只使用[-printmapping mapping.txt]，生成mapping.txt，注释下面的[-applymapping mapping.txt]
#生成的mapping.txt在app/build/outputs/mapping/release路径下，移动到/app路径下
-printmapping mapping.txt
#修复bug或修改功能后打包使用[-applymapping mapping.txt]，应用mapping.txt，保证混淆结果一致
#上面的[-printmapping mapping.txt]不用注释掉，放开[-applymapping mapping.txt]注释就行
#-applymapping mapping.txt
-keep class com.taobao.sophix.**{*;}
-keep class com.ta.utdid2.device.**{*;}
-dontwarn com.alibaba.sdk.android.utils.**
-keepclassmembers class com.gsmc.ued.AppApplication {
    public <init>();
}

#compresshelper
-dontwarn com.nanchen.compresshelper.**
-keep class com.nanchen.compresshelper.** {*;}

#lottie
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** {*;}
-keep interface com.airbnb.lottie.** {*;}

#############################################
#
# 自定义混淆
#
#############################################
-keep class * implements com.gsmc.ued.data.base.BaseEntity {*;}
-keep class com.gsmc.ued.data.net.converters.DecryptResponseBodyConverter$Result {*;}
-keep class com.gsmc.ued.data.net.entity.** {*;}
-keep class com.gsmc.ued.ui.widget.** {*;}
-keep class **Model {*;}
-keep class com.gsmc.ued.data.net.glide.SimpleGlideModule {*;}
-keep class com.gsmc.ued.data.module.accountcenter.**.entity.** {*;}
-keep class com.gsmc.ued.data.module.**.entity.** {*;}


#############################################
#
# moment-library inner library
#
#############################################

#baidu_map
-dontwarn com.baidu.** #去掉警告
-dontwarn com.baidu.mapapi.**
-keep class com.baidu.** {*;} #过滤BaiduLBS_Android.jar
-keep class vi.com.gdi.bgl.android.**{*;}
-keep class com.baidu.platform.**{*;}
-keep class com.baidu.location.**{*;}
-keep class com.baidu.vi.**{*;}

#qiniu
-dontwarn com.qiniu.**
-keep class com.qiniu.**{*;}
#-ignorewarnings

#httpmime
-dontwarn org.apache.http.entity.mime.**
-keep class org.apache.http.entity.mime.**{*;}

#volley
-keep class com.android.volley.** {*;}
-keep class com.android.volley.toolbox.** {*;}
-keep class com.android.volley.Response$* { *; }
-keep class com.android.volley.Request$* { *; }
-keep class com.android.volley.RequestQueue$* { *; }
-keep class com.android.volley.toolbox.HurlStack$* { *; }
-keep class com.android.volley.toolbox.ImageLoader$* { *; }

#ShareSDK
-keep class cn.sharesdk.**{*;}
-keep class com.sina.**{*;}
-keep class **.R$* {*;}
-keep class **.R{*;}
-keep class com.mob.**{*;}
-keep class m.framework.**{*;}
-dontwarn com.sina.**
-dontwarn com.mob.**
-dontwarn **.R$*

#kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}
-keep class kotlinx.coroutines.android.** {*;}
# ServiceLoader support
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory {}

# Most of volatile fields are updated with AFU and should not be mangled
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}



# 保留 StringConcatFactory 的引用
-keep class java.lang.invoke.StringConcatFactory {
    *;
}

-keep class com.architecture.runtime.** { *; }
-keep class com.drake.brv.** { *; }

# 保留所有动态代理相关的类
-keepattributes *Annotation*
-keep class * implements java.lang.reflect.InvocationHandler {
    *;
}

# lib_webview
#------tbs腾讯x5混淆规则-------

#-optimizationpasses 7
#-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-dontoptimize
-dontusemixedcaseclassnames
-verbose
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontwarn dalvik.**
-dontwarn com.tencent.smtt.**
#-overloadaggressively

# ------------------ Keep LineNumbers and properties ---------------- #
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod
# --------------------------------------------------------------------------

-keep class org.repackage.** {*;}

-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 崩溃日志不混淆
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# 友盟 end

-dontwarn java.lang.invoke.StringConcatFactory

-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class **.databinding.** { *; }
-dontwarn android.databinding.**
-dontwarn androidx.databinding.**

# ali dns
-keep class com.alibaba.pdns.** {*;}