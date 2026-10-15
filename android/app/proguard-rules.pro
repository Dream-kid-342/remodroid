# WebRTC uses JNI callbacks into these classes.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.remodroid.shared.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.remodroid.shared.**$$serializer { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
