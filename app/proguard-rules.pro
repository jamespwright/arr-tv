# Retrofit, OkHttp and kotlinx.serialization ship their own R8 rules.

# The trailer page calls back into the app through these, by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
