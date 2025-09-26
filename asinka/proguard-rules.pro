# Add project specific ProGuard rules here.

# Keep gRPC classes
-keep class io.grpc.** { *; }
-keepclassmembers class io.grpc.** { *; }
-dontwarn io.grpc.**

# Keep Protocol Buffers
-keep class com.google.protobuf.** { *; }
-keepclassmembers class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Keep generated proto classes
-keep class digital.vasic.asinka.proto.** { *; }
-keepclassmembers class digital.vasic.asinka.proto.** { *; }

# Keep Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** Companion;
}

# Keep SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep public API
-keep public class digital.vasic.asinka.AsinkaClient { *; }
-keep public class digital.vasic.asinka.AsinkaConfig { *; }
-keep public interface digital.vasic.asinka.** { *; }
-keep public class * implements digital.vasic.asinka.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# General Android
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable