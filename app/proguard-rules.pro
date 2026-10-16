# ScreenCircleSearch ProGuard / R8 rules.
#
# minifyEnabled is false by default in the release build type, so these rules
# only matter once you turn shrinking on. They are written so that R8 can be
# enabled without breaking the OCR path.

# ---------------------------------------------------------------------------
# Tesseract (tess-two)
# ---------------------------------------------------------------------------
# The native library looks these up by name via JNI, so the Java side must not
# be renamed or stripped.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.tesseract.android.TessBaseAPI {
    public native *;
    public *;
}

# ---------------------------------------------------------------------------
# Views inflated from XML
# ---------------------------------------------------------------------------
# Custom views and ViewBinding classes are instantiated reflectively by the
# layout inflater.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# The view binding classes are generated and referenced directly, but keeping
# them makes crash reports far more readable.
-keep class com.screencirclesearch.databinding.** { *; }

# ---------------------------------------------------------------------------
# Our own code
# ---------------------------------------------------------------------------
-keep class com.screencirclesearch.CircleSearchService { *; }
-keep class com.screencirclesearch.MainActivity { *; }

# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------
# Keep the source file / line numbers for readable stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
