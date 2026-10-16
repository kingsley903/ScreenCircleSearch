# ScreenCircleSearch

An Android 11 (API 30) app that implements **Circle to Search**:

1. A draggable floating button is shown on top of every app.
2. Tapping it takes a screenshot of the current screen.
3. You draw a **circle** (or a rectangle) around the area you are interested in.
4. The selected area is cropped and processed by **Tesseract OCR completely
   offline** on the device.
5. The recognised text appears in a Material card with **Copy** and
   **Web search** actions.

No screen content ever leaves the device unless you explicitly press *Search*.

---

## 1. Project layout

```
ScreenCircleSearch/
├── build.gradle                     root build script (AGP 7.0.4, Kotlin 1.5.31)
├── settings.gradle                  repositories (incl. JitPack for tess-two)
├── gradle.properties
├── gradlew / gradlew.bat              Gradle 7.0.2 wrapper (committed)
├── gradle/wrapper/                    wrapper properties + jar
├── local.properties                   <-- your SDK path (not committed)
└── app/
    ├── build.gradle                 app config, deps, checkTessData task
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── tessdata/            eng.traineddata (fast model is committed)
        ├── java/com/screencirclesearch/
        │   ├── MainActivity.kt          setup screen, permission checks
        │   ├── CircleSearchService.kt   AccessibilityService + flow orchestration
        │   ├── ScreenCaptureHelper.kt   takeScreenshot() wrapper
        │   ├── SelectionOverlay.kt      selection view + overlay window
        │   ├── FloatingBubble.kt        draggable floating button
        │   ├── ResultPanel.kt           result card + copy/search
        │   ├── OCRManager.kt            Tesseract wrapper + preprocessing
        │   ├── BitmapUtils.kt           crop / greyscale / blank detection
        │   └── SettingsCompat.kt        special permission helpers
        └── res/
            ├── layout/  activity_main.xml, overlay_selection.xml, overlay_result.xml
            ├── values/  strings.xml, colors.xml, styles.xml
            ├── drawable/
            ├── mipmap-anydpi-v26/  ic_launcher.xml, ic_launcher_round.xml
            └── xml/  accessibility_service_config.xml
```

---

## 2. Requirements

| Tool | Version |
| --- | --- |
| JDK | **11** (AGP 7.0.x will not run on JDK 17) |
| Android SDK Platform | **android-30** |
| Android SDK Build-Tools | **30.0.3** |
| Gradle | 7.0.2 (wrapper included: `gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) |
| minSdk / targetSdk | 30 / 30 |

The Gradle wrapper is committed, so `./gradlew` works straight after a clone.
You only need JDK 11 on `PATH` (or `JAVA_HOME` set). Opening the project in
Android Studio works as well — Studio uses the wrapper too.

---

## 3. The OCR model (`eng.traineddata`) — required

Tesseract cannot recognise anything without its language model. The app loads it
from **`app/src/main/assets/tessdata/eng.traineddata`**, and copies it into
private storage on first run (libtesseract cannot read an `AssetManager`).

A model is **already included**: `tessdata_fast/eng.traineddata` (4.1 MB,
LSTM "fast" model), so the project builds and runs immediately. It is noticeably
less accurate than the full model.

For better accuracy, replace it with the standard model:

1. Download `eng.traineddata` (~23 MB) from
   <https://github.com/tesseract-ocr/tessdata> (`tessdata/main/eng.traineddata`,
   the "best" model) and overwrite the file in
   `app/src/main/assets/tessdata/`.
   The ~4 MB `tessdata_fast` variant is also fine.
2. Rebuild and reinstall. Nothing else has to change.

Verify what is in place at any time:

```bash
./gradlew :app:checkTessData
```

This prints the size of the model currently in `assets/tessdata`, or the exact
download locations if it is missing. Note that the `tess-two` AAR **ships the
native libraries (`jni/<abi>/libtess.so` and friends) but no `*.traineddata`
models**, so this file has to come from the tesseract-ocr repository.

### Verifying it is in place

```bash
./gradlew :app:checkTessData
```

This prints the size of the model or, if it is missing, the exact download
locations. It is a check only — the tess-two AAR **ships the native libraries
(`jni/<abi>/libtess.so` and friends) but no `*.traineddata` models**, so the
model always has to come from the tesseract-ocr repository.

### Additional languages

Download e.g. `deu.traineddata` into the same folder and change
`OCRManager.LANGUAGE` from `eng` to `deu`. Only one language is initialised at a
time in this implementation.

---

## 4. Building

```bash
# 1. point the build at your SDK (or export ANDROID_SDK_ROOT)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 2. build
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # unsigned, minifyEnabled=false
./gradlew :app:installDebug           # build + install on a connected device
```

If you enable R8 in `app/build.gradle` (`minifyEnabled true`), the rules in
`proguard-rules.pro` keep the Tesseract JNI entry points and the custom views
intact.

### Dependencies

```groovy
implementation 'androidx.appcompat:appcompat:1.3.1'
implementation 'androidx.core:core-ktx:1.6.0'
implementation 'androidx.constraintlayout:constraintlayout:2.1.1'
implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.3.1'
implementation 'com.google.android.material:material:1.4.0'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2'
implementation 'com.github.rmtheis:tess-two:9.1.0'   // via JitPack
```

> Note on coordinates: the TessTwo fork you want is published on JitPack as
> **`com.github.rmtheis:tess-two:9.1.0`**, not `com.rmtheis:tess-two`. The
> Java package inside the AAR is still `com.googlecode.tesseract.android`.
> The AAR bundles the native `libtesseract` `.so` files for `armeabi-v7a`,
> `arm64-v8a`, `x86` and `x86_64`.

---

## 5. First-run setup on the device

Android 11 grants two of the three required permissions through settings
screens, not through `requestPermissions()`:

1. **Display over other apps** — the app opens
   `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` for your package directly. Tap
   *Grant*.
2. **Accessibility service** — enable **ScreenCircleSearch** in
   Settings → Accessibility → Installed services.
   * `android:canTakeScreenshot="true"` in
     `res/xml/accessibility_service_config.xml` is what makes
     `takeScreenshot()` legal; without it the call returns
     `ERROR_TAKE_SCREENSHOT_INCOMPATIBLE`.
   * `BIND_ACCESSIBILITY_SERVICE` is not a runtime permission and must never be
     requested in code.
3. **Floating button** — a toggle on the setup screen. Its position is persisted
   in `SharedPreferences` so it stays where you dragged it.

`WRITE_EXTERNAL_STORAGE` is **declared with `maxSdkVersion="28"` and is not
actually used**: screenshots live in memory and the temporary crop is never
written to disk, so scoped storage on API 30 does not affect the app.

---

## 6. How it works

```
CircleSearchService (AccessibilityService)
  │
  ├─ FloatingBubble            TYPE_APPLICATION_OVERLAY, draggable
  │      │  tap
  │      ▼
  ├─ ScreenCaptureHelper       AccessibilityService.takeScreenshot()
  │      │  HardwareBuffer → Bitmap.wrap() → copy(ARGB_8888)
  │      │  (+ blank/secure-window detection)
  │      ▼
  ├─ SelectionOverlay          full-screen overlay showing the frozen
  │      │                    screenshot; SelectionShapeView punches a
  │      │                    circle/rect hole in the scrim and maps
  │      │                    touches to bitmap pixels
  │      ▼  "Extract"
  ├─ BitmapUtils.crop          selection rect → cropped bitmap
  │      ▼
  ├─ OCRManager                scale → greyscale → polarity correction
  │      │                    → Otsu binarisation → TessBaseAPI
  │      ▼
  └─ ResultPanel               copy to clipboard / Google web search
```

Design points worth knowing:

* **The screenshot is taken before the overlay is added.** Otherwise the user's
  own dim layer and the bubble would end up inside the selection.
* **Coordinate mapping is done against the exact `fit center` rect** the
  `ImageView` was laid out with, so status bar height, display cutouts and
  screen density never enter the maths.
* **The bubble is hidden during capture and while the result panel is up**, and
  comes back when the panel is dismissed.
* **Screenshots are never written to storage.** The bitmap lives in memory until
  OCR finishes and is then recycled.
* **Secure windows** (`FLAG_SECURE`: password fields, DRM playback, banking
  apps) come back as flat black images. The blank-image check detects this and
  explains it to the user instead of claiming "no text found".
* `takeScreenshot()` is throttled to one call per 500 ms; a faster second tap
  returns `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`, which is surfaced as
  "please wait a moment".

### API 30 gotchas this code works around

These are the details that make screenshot-based circle-to-search apps fail on
Android 11; all of them are handled:

| Pitfall | What was done |
| --- | --- |
| `AccessibilityService.canTakeScreenshot()` is **not public** in the API 30 SDK (it is `@hide`) | Read `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` instead and map `ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS` to "service not enabled". |
| `Bitmap.wrap(Buffer, ColorSpace)` is **not public** in the API 30 SDK either | Use `Bitmap.wrapHardwareBuffer(buffer, colorSpace)`, then `copy(ARGB_8888, false)` for a CPU-side bitmap. |
| `AccessibilityService.getWindow()` is `@hide` | Overlay windows are added through `getSystemService(WINDOW_SERVICE)` instead. |
| `ScreenshotResult.getHardwareBuffer()` is deprecated from API 33 but is *the* API 30 accessor | It is used deliberately; `getHardwareBuffers()` does not exist on API 30. The `HardwareBuffer` is always `close()`d in a `finally` block. |
| The four real API 30 error codes are `..._INTERNAL_ERROR`, `..._INTERVAL_TIME_SHORT`, `..._INVALID_DISPLAY`, `..._NO_ACCESSIBILITY_ACCESS` — there is **no** `SECURE` / `INCOMPATIBLE` constant | Secure windows are detected by inspecting the pixels instead of by error code. |
| `takeScreenshot()` returns `void` on API 30 | The result is delivered through the callback only. |
| tess-two 9.1.0 has **no `recycle()`** | `end()` is the native teardown call and is wrapped in `runCatching`. |
| `android.util.Point` is not in the API 30 public stub | Display bounds come from `WindowManager.currentWindowMetrics.bounds`. |

---

## 7. OCR quality notes

`ImagePreprocessor` is where most of the accuracy comes from:

* **Scaling** — anything wider than 2200 px is scaled down, anything smaller
  than 200 px is scaled up. Tesseract's layout analysis degrades badly on very
  small or very large inputs.
* **Greyscale** — sub-pixel rendered text in screenshots has colour fringing
  that confuses the binariser.
* **Polarity correction** — dark-mode UIs, video subtitles and photos are
  light-on-dark; Tesseract expects black-on-white, so the image is inverted
  when the mean luminance is below 110.
* **Otsu binarisation** — removes gradients and screenshot compression noise.
  Skipped automatically when the computed threshold is at an extreme, which
  means the image was already effectively binary.

Confidence is shown in the result card; a value below roughly 60 usually means
the selection was too tight — select a little more context around the text.

---

## 8. Verification status

This project was **compiled for real**, not just written out:

* `:app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` (all four
  ABIs of `libtess.so` / `liblept.so` / `libjpgt.so` / `libpngt.so` packaged,
  `assets/tessdata/eng.traineddata` present)
* `:app:assembleRelease` → `app/build/outputs/apk/release/app-release-unsigned.apk`
* `:app:lintDebug` → no errors
* `:app:checkTessData` → reports the model size
* Kotlin compilation is warning-free, verified with AGP 7.0.4 + Kotlin 1.5.31 +
  Gradle 7.0.2 on JDK 11 against `platforms;android-30`.

What has **not** been done: no physical Android 11 device or emulator was
available here, so the runtime behaviour (overlay windows, `takeScreenshot()`
against a real app, Tesseract accuracy) has not been exercised on-device. The
API usage was validated against the real `android-30/android.jar` with `javap`,
which is how the `@hide` pitfalls listed in section 6 were found.

---

## 9. Known limitations

* **Landscape rotation** is handled through the screenshot aspect ratio, but the
  bubble position is clamped to the display bounds of the current rotation.
* **Live content** (video, video calls) may produce a frame that differs from
  what the user saw; the capture is a point-in-time snapshot.
* Only **one language** is loaded at a time.
* `tess-two` 9.1.0 is an unmaintained fork. If you need newer Tesseract
  versions, `cz.adaptech.tesseract4android:tesseract4android-openmp` is the
  maintained alternative — it needs the same `tessdata` folder, but you also
  have to copy `libtesseract.so` and its dependencies into `jniLibs/`.
* Google Play will scrutinise an AccessibilityService that takes screenshots.
  If you publish, document the behaviour in the Data Safety section, keep the
  service limited to what is described here, and be aware that apps in the
  Play Store's restricted categories cannot use an accessibility service for
  automation.

---

## 10. Troubleshooting

| Symptom | Cause |
| --- | --- |
| "Screen capture is not available" | Accessibility service not enabled, or `canTakeScreenshot` missing from the config XML. |
| Bubble does not appear | "Display over other apps" not granted; check `Settings.canDrawOverlays()`. |
| "The OCR engine could not be started" | `assets/tessdata/eng.traineddata` missing — see section 3. |
| "No text was found" | Selection too small, or the region contains no text. Confidence < 60 is a hint. |
| "This screen is protected…" | The foreground app uses `FLAG_SECURE`; nothing can be captured. |
| App installs but immediately crashes with `UnsatisfiedLinkError` | The ABI list in `defaultConfig.ndk.abiFilters` was changed and no matching `libtesseract` `.so` is present. |
