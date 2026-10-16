package com.screencirclesearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline OCR built on Tesseract (tess-two, the JitPack fork of TessTwo).
 *
 * Responsibilities:
 *  1. Copy the traineddata files out of `assets/tessdata` into the app's
 *     private storage - libtesseract can only read from the file system, it
 *     cannot read an AssetManager.
 *  2. Initialise [TessBaseAPI] exactly once and keep it alive (initialisation
 *     loads the ~20 MB model and takes several hundred milliseconds).
 *  3. Pre-process the crop: scaling, greyscale, polarity correction and
 *     Otsu binarisation. This is what makes small phone screenshots readable.
 *  4. Run the recognition off the main thread.
 */
class OCRManager(context: Context) {

    private val appContext = context.applicationContext

    /** Where the extracted traineddata lives: /data/data/<pkg>/files/tessdata */
    private val tessDataDir: File = File(appContext.filesDir, TESSDATA_DIR)

    private val initMutex = Mutex()
    private var api: TessBaseAPI? = null

    /** Result of an OCR run. */
    data class Result(
        val text: String,
        val confidence: Int,
        val wordCount: Int,
        val error: Reason? = null,
    ) {
        val isSuccess: Boolean get() = error == null

        /** After normalisation this is what gets searched / copied. */
        val query: String get() = text.trim().replace(Regex("[ \\t]+"), " ")
    }

    enum class Reason { ENGINE_NOT_READY, NOTHING_SELECTED }

    /**
     * Runs OCR on [source]. The bitmap is never recycled here - ownership stays
     * with the caller. Any intermediate bitmaps created while pre-processing are
     * released before returning.
     */
    suspend fun recognize(source: Bitmap): Result = withContext(Dispatchers.IO) {
        val engine = ensureInitialized()
        if (engine == null) {
            return@withContext Result("", 0, 0, Reason.ENGINE_NOT_READY)
        }

        var prepared: Bitmap? = null
        try {
            prepared = ImagePreprocessor.prepare(source)
            if (prepared == null) {
                return@withContext Result("", 0, 0, Reason.NOTHING_SELECTED)
            }

            // setImage hands the buffer to native code. makeBitmap() is *not*
            // called here on purpose: we want Tesseract to hold a reference into
            // the Java heap for the duration of the recognition only.
            engine.setImage(prepared)
            val raw = try {
                // getUTF8Text() cannot become a Kotlin property here: the
                // getter starts with an upper case acronym, so the synthetic
                // property would be called UTF8Text. Calling the Java method
                // directly is clearer.
                engine.getUTF8Text()
            } catch (e: Throwable) {
                Log.e(TAG, "getUTF8Text() failed", e)
                null
            } ?: ""

            val confidence = try {
                engine.meanConfidence()
            } catch (e: Throwable) {
                -1
            }

            val cleaned = TextUtils.clean(raw)
            Result(
                text = cleaned,
                confidence = confidence,
                wordCount = cleaned.split(' ').count { it.isNotBlank() },
            )
        } catch (e: Throwable) {
            Log.e(TAG, "recognition failed", e)
            Result("", 0, 0, Reason.ENGINE_NOT_READY)
        } finally {
            if (prepared != null && prepared !== source) prepared.recycle()
            // Break the reference so we do not hold on to a recycled bitmap.
            runCatching { engine.clear() }
        }
    }

    /**
     * Lazily creates the TessBaseAPI. Safe to call from any thread; the mutex
     * makes sure two concurrent taps cannot initialise the engine twice.
     */
    private suspend fun ensureInitialized(): TessBaseAPI? = withContext(Dispatchers.IO) {
        initMutex.withLock {
            api?.let { return@withLock it }

            runCatching {
                if (!ensureTessDataExtracted()) {
                    throw IOException("tessdata/eng.traineddata is missing from the APK assets")
                }
                if (!tessDataDir.listFiles()?.any { it.name.endsWith(".traineddata") }!!) {
                    throw IOException("no *.traineddata files in $tessDataDir")
                }

                // The no-argument constructor loads the native library and leaves
                // the engine uninitialised; init() below then binds it to the
                // tessdata folder we extracted into the app's private storage.
                val engine = TessBaseAPI()
                val ok = engine.init(appContext.filesDir.absolutePath, LANGUAGE)
                if (!ok) {
                    // tess-two 9.1.0 has no recycle(); end() is the native
                    // destructor it exposes.
                    runCatching { engine.end() }
                    throw IllegalStateException("TessBaseAPI.init() returned false")
                }

                // A single block of text is the right assumption for a user
                // selection; PSM_SINGLE_BLOCK also tolerates multiple lines.
                engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)

                api = engine
                Log.i(TAG, "Tesseract initialised, models in $tessDataDir")
                engine
            }.onFailure { error ->
                Log.e(TAG, "Could not initialise Tesseract", error)
            }.getOrNull()
        }
    }

    /**
     * Copies the traineddata files from `assets/tessdata` into the app's
     * private storage. Tesseract's native code opens the model with plain file
     * I/O, so an AssetManager is not enough. This runs once per install; the
     * check is a cheap file existence test afterwards.
     */
    private fun ensureTessDataExtracted(): Boolean {
        val alreadyThere = File(tessDataDir, "$LANGUAGE.traineddata").exists()
        if (alreadyThere) return true

        if (!tessDataDir.exists() && !tessDataDir.mkdirs()) {
            Log.e(TAG, "cannot create $tessDataDir")
            return false
        }

        val assetNames = try {
            appContext.assets.list(ASSET_TESSDATA_DIR)?.toList().orEmpty()
        } catch (e: IOException) {
            Log.e(TAG, "cannot list $ASSET_TESSDATA_DIR", e)
            emptyList()
        }

        if (assetNames.isEmpty()) {
            Log.e(
                TAG,
                "No files in assets/$ASSET_TESSDATA_DIR. See README: download " +
                    "eng.traineddata and place it there."
            )
            return false
        }

        for (name in assetNames) {
            if (!name.endsWith(".traineddata")) continue
            val target = File(tessDataDir, name)
            try {
                appContext.assets.open("$ASSET_TESSDATA_DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                Log.i(TAG, "extracted $name (${target.length()} bytes)")
            } catch (e: IOException) {
                Log.e(TAG, "failed to extract $name", e)
                return false
            }
        }
        return File(tessDataDir, "$LANGUAGE.traineddata").exists()
    }

    /**
     * Frees the native engine. Call when the service is destroyed.
     *
     * tess-two 9.1.0 does not expose recycle() - end() is the corresponding
     * native teardown call, so it is wrapped in runCatching: if the native
     * library was never loaded, end() throws and there is nothing to leak.
     */
    fun release() {
        synchronized(this) {
            api?.let { runCatching { it.end() } }
            api = null
        }
    }

    /** True when the model files are already present in private storage. */
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        File(tessDataDir, "$LANGUAGE.traineddata").exists()
    }

    companion object {
        private const val TAG = "OCRManager"
        private const val ASSET_TESSDATA_DIR = "tessdata"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGE = "eng"
    }
}

/**
 * Cleans the raw output of Tesseract: drops the form feed and null characters,
 * collapses blank runs and trims trailing whitespace per line.
 */
internal object TextUtils {
    // Everything in the C0 range except \n. \r (0x0D) is left in place so the
    // \r\n -> \n replacement below still works.
    private val CONTROL_CHARS = Regex("[\\x00-\\x09\\x0B\\x0C\\x0E-\\x1F]")
    private val BLANK_LINES = Regex("\\n{3,}")

    fun clean(raw: String): String =
        raw.replace(CONTROL_CHARS, "")
            .replace("\r\n", "\n")
            .replace(BLANK_LINES, "\n\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
}

/**
 * Image pre-processing pipeline. Everything here is deterministic and cheap
 * enough to run on a mid-range phone for a region of a screenshot.
 */
internal object ImagePreprocessor {

    private const val MAX_DIMENSION = 2200
    private const val MIN_DIMENSION = 200

    /**
     * Returns a bitmap ready for Tesseract, or null if the input is degenerate.
     * The returned bitmap may be the input itself (when no processing was
     * needed) - callers must compare by reference before recycling.
     */
    fun prepare(source: Bitmap): Bitmap? {
        if (source.width < 2 || source.height < 2) return null

        var current = source

        // 1. Scale. A user selecting a small word on a 1440p phone produces a
        //    crop that is far too small for the layout analysis; a crop of a
        //    whole paragraph produces a huge image that is slow to binarise.
        val longest = max(current.width, current.height)
        val target = when {
            longest > MAX_DIMENSION -> (MAX_DIMENSION.toFloat() / longest)
            longest < MIN_DIMENSION -> (MIN_DIMENSION.toFloat() / longest)
            else -> 1f
        }
        if (target != 1f) {
            val scaled = Bitmap.createScaledBitmap(
                current,
                (current.width * target).roundToInt().coerceAtLeast(2),
                (current.height * target).roundToInt().coerceAtLeast(2),
                true,
            )
            if (current !== source) current.recycle()
            current = scaled
        }

        // 2. Greyscale. Tesseract works on luminance only, and the saturation
        //    matrix is much faster than a per-pixel Kotlin loop.
        val gray = BitmapUtils.toGrayscale(current)
        if (current !== source && current !== gray) current.recycle()
        current = gray

        // 3. Polarity. Tesseract was trained on black text on a white
        //    background. Dark-mode apps, video subtitles and photos give us the
        //    opposite, which roughly halves accuracy unless we invert.
        if (BitmapUtils.meanLuma(current) < INVERT_BELOW_LUMA) {
            val inverted = invert(current)
            current.recycle()
            current = inverted
        }

        // 4. Binarise. Otsu's method picks the threshold that maximises the
        //    separation between the text and the background, which removes
        //    gradients and JPEG-ish noise from screenshots.
        val binarised = otsuBinarize(current)
        if (binarised != null && binarised !== current) {
            current.recycle()
            current = binarised
        }

        return current
    }

    /** Inverts every pixel. */
    private fun invert(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Otsu thresholding. The histogram is built from a 256 bucket sample of the
     * image, which is accurate enough and keeps this well under a millisecond
     * for the region sizes we deal with.
     */
    private fun otsuBinarize(source: Bitmap): Bitmap? {
        return try {
            val width = source.width
            val height = source.height
            val step = max(1, min(width, height) / 256)

            val histogram = IntArray(256)
            var samples = 0
            val row = IntArray(width)
            var y = 0
            while (y < height) {
                source.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width step step) {
                    val p = row[x]
                    val luma = ((0.299 * Color.red(p) + 0.587 * Color.green(p) +
                            0.114 * Color.blue(p)) + 0.5).toInt().coerceIn(0, 255)
                    histogram[luma]++
                }
                samples += width / step
                y += step
            }
            if (samples == 0) return null

            val threshold = otsuThreshold(histogram, samples)
            // A threshold pinned to the extremes means the image is already
            // effectively binary; skip the extra pass.
            if (threshold <= 2 || threshold >= 253) return null

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val luma = (0.299 * Color.red(p) + 0.587 * Color.green(p) +
                        0.114 * Color.blue(p)).toInt()
                pixels[i] = if (luma > threshold) Color.WHITE else Color.BLACK
            }
            output.setPixels(pixels, 0, width, 0, 0, width, height)
            output
        } catch (e: OutOfMemoryError) {
            // A very large screenshot crop can exceed the heap; better to hand
            // the greyscale image to Tesseract than to crash.
            Log.w("ImagePreprocessor", "binarisation ran out of memory", e)
            null
        }
    }

    /** Classic Otsu: pick the threshold that maximises between-class variance. */
    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in histogram.indices) sum += i.toDouble() * histogram[i]

        var sumBackground = 0.0
        var weightBackground = 0
        var best = 0.0
        var threshold = 127

        for (t in 0..255) {
            weightBackground += histogram[t]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumBackground += t.toDouble() * histogram[t]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val between = weightBackground.toDouble() * weightForeground.toDouble() *
                (meanBackground - meanForeground) * (meanBackground - meanForeground)

            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /** Below this average luma the image is treated as light text on dark. */
    private const val INVERT_BELOW_LUMA = 110.0
}
