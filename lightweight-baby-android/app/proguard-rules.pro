# Methods the web page calls through window.Android
-keepclassmembers class com.fuelstride.app.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, Signature, InnerClasses, EnclosingMethod, *Annotation*

# Optional server-side/JVM-only dependencies of the SDK that Android never loads
-dontwarn **
