# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Keep all Retrofit interface methods
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}

# Keep the actual interface classes
-keep interface de.traewelling.app.data.api.** { *; }

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-dontwarn retrofit2.Response

# Keep Retrofit classes
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class de.traewelling.app.data.model.** { *; }
-keepclassmembers class de.traewelling.app.data.model.** { *; }

# Compose
-keep class androidx.compose.** { *; }
