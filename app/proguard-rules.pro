# Keep JNI Native Bridge methods
-keep class com.statis.app.native.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep ViewBinding and data models
-keep class com.statis.app.model.** { *; }
