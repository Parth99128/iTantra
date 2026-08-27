# Add project-specific ProGuard rules here.
# Keep Vosk/JNA and ONNX Runtime classes from being stripped since they use JNI/reflection.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class ai.onnxruntime.** { *; }
