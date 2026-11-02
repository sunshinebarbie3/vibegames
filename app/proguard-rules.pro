# Moshi reflection adapters and their generic signatures.
-keep class com.vibe.app.data.remote.** { *; }
-keep class com.vibe.app.domain.** { *; }
-keepclassmembers class kotlin.Metadata { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
