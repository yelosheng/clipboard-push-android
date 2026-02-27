# Add project specific ProGuard rules here.

# Keep annotations and generics signatures (required by Gson)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# Gson - keep data model classes and API response classes used for JSON serialization
-keep class com.clipboardpush.plus.data.model.** { *; }
-keepclassmembers class com.clipboardpush.plus.data.model.** { *; }
-keep class com.clipboardpush.plus.data.remote.ApiService$* { *; }

# Gson + R8: retain generic type info on TypeToken anonymous classes
# Without these, TypeToken<List<PushMessage>>() has its generic erased by R8,
# causing gson.fromJson() to return List<LinkedTreeMap> instead of List<PushMessage>,
# which silently fails and breaks DataStore read/write.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep fields annotated with @SerializedName after minification
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Gson internal stream/tree classes
-keep class com.google.gson.stream.** { *; }
-keep class com.google.gson.internal.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Socket.IO
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Firebase / Google Play Services
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ZXing (QR scanner)
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**

# WorkManager
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Coil
-dontwarn coil.**
