# Consumer ProGuard rules for Asinka library

# Keep public API
-keep public class digital.vasic.asinka.AsinkaClient { *; }
-keep public class digital.vasic.asinka.AsinkaConfig { *; }
-keep public interface digital.vasic.asinka.** { *; }

# Keep gRPC and protobuf
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class digital.vasic.asinka.proto.** { *; }