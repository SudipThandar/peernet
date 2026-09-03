# Keep UniFFI/JNI bindings for the Rust core (added in Milestone 2).
-keep class com.peernet.wifiextender.core.** { *; }

# Keep protobuf-style / serde JSON model names if reflection is ever used.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Google Mobile Ads SDK
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
