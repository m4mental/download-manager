# Proguard rules for SpeedDown

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# KotlinX Serialization
-keepattributes *Annotation*,InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Android & Jetpack Compose
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
