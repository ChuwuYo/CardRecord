# Default ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class kotlinx.serialization.** { *; }
