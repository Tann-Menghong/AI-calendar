# MediaPipe's GenAI runtime is reached from JNI, so its entry points must survive.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.genai.llminference.** { *; }
-dontwarn com.google.mediapipe.**

# Tesseract4Android binds native methods by name.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# ML Kit loads its recognisers reflectively.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# AutoValue builders used by the MediaPipe options classes.
-keepclassmembers class * extends com.google.auto.value.AutoValue { *; }

# Guava's ListenableFuture is referenced by the GenAI async API.
-dontwarn com.google.common.util.concurrent.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
