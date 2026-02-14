# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.example.localllmchat.data.remote.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
