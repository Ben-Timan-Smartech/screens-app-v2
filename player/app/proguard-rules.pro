# Keep Retrofit service interfaces + serialization metadata.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep,allowobfuscation,allowshrinking class kotlin.Metadata
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Kotlinx serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Media3 keeps its own rules via consumer ProGuard files.
