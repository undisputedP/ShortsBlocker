# R8 already keeps classes referenced from AndroidManifest, but explicit
# rules guard against accidental stripping if the manifest reference is
# generated or moved at build time.
-keep class com.shortsBlocker.service.BlockerVpnService { *; }
-keep class com.shortsBlocker.service.BootReceiver { *; }
-keep class com.shortsBlocker.ui.MainActivity { *; }

# Strip debug logging in release builds.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
