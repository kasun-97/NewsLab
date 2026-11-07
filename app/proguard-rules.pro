# R8 minification and resource shrinking are on for release builds (app/build.gradle.kts).
# The AndroidX, OkHttp and Coil artifacts ship their own consumer rules.
-keepattributes Signature
-keepattributes *Annotation*
