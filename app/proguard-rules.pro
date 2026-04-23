# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherLoader { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keepnames class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keepnames class kotlin.coroutines.Continuation { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }

# --- Retrofit 2 ---
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**

# Keep all Retrofit interface methods and their parameter types
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}

# DO NOT obfuscate or shrink our API and Data Models
-keep class de.traewelling.app.data.api.** { *; }
-keep interface de.traewelling.app.data.api.** { *; }
-keep class de.traewelling.app.data.model.** { *; }
-keepclassmembers class de.traewelling.app.data.model.** { *; }

# --- OkHttp 3 ---
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# --- Gson ---
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * implements com.google.gson.TypeAdapterFactory { *; }

# --- Compose & Android ---
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keepattributes *Annotation*
