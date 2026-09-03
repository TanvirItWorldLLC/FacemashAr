# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }

# Keep CameraX classes
-keep class androidx.camera.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep Guava ListenableFuture
-keep class com.google.common.util.concurrent.** { *; }

# Keep application class
-keep class com.facemesh.ar.FaceMeshApplication { *; }

# Keep MainActivity
-keep class com.facemesh.ar.MainActivity { *; }

# Keep custom views
-keep class com.facemesh.ar.FaceMeshOverlayView { *; }

# Suppress warnings for MediaPipe native libs
-dontwarn com.google.mediapipe.**
-dontwarn androidx.camera.**