package com.screencirclesearch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.screencirclesearch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Setup screen. Its only jobs are to check the two special permissions, send
 * the user to the right settings page and to show/hide the floating button.
 *
 * All of the actual work happens in [CircleSearchService].
 */
class MainActivity : AppCompatActivity(), CircleSearchService.ConnectionListener {

    private lateinit var binding: ActivityMainBinding

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var captureHelper: ScreenCaptureHelper? = null
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleCaptureResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.overlayPermissionButton.setOnClickListener {
            startSettings(SettingsCompat.overlaySettingsIntent(this), R.string.status_overlay_missing)
        }

        binding.accessibilityButton.setOnClickListener {
            startSettings(SettingsCompat.accessibilitySettingsIntent(), R.string.status_service_disabled)
        }

        binding.bubbleButton.setOnClickListener {
            if (!SettingsCompat.canDrawOverlays(this)) {
                startSettings(
                    SettingsCompat.overlaySettingsIntent(this),
                    R.string.status_overlay_missing,
                )
                return@setOnClickListener
            }
            val enabled = !preferences.getBoolean(KEY_BUBBLE_ENABLED, true)
            preferences.edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
            CircleSearchService.instance?.setBubbleEnabled(enabled)
            renderState()
        }

        binding.appSettingsButton.setOnClickListener {
            startSettings(SettingsCompat.appDetailsIntent(this), 0)
        }

        captureHelper = ScreenCaptureHelper(this)
        CircleSearchService.addListener(this)

        // Report whether the OCR model is in place, so a missing traineddata file
        // is visible without having to look at logcat.
        lifecycleScope.launch {
            val ready = OCRManager(applicationContext).isReady()
            if (!ready) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.result_ocr_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    override fun onDestroy() {
        CircleSearchService.removeListener(this)
        captureHelper?.release()
        captureHelper = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // CircleSearchService.ConnectionListener
    // ------------------------------------------------------------------

    override fun onServiceConnected(service: CircleSearchService) {
        service.setBubbleEnabled(preferences.getBoolean(KEY_BUBBLE_ENABLED, true))

        // The bubble tap is handled here (not in the service) because taking a
        // screenshot requires the MediaProjection permission dialog, which can
        // only be launched from an Activity.
        service.onCaptureRequested = { startScreenCapture() }
        renderState()
    }

    private fun startScreenCapture() {
        val helper = captureHelper ?: return
        helper.requestCapture(
            onResult = ::onCaptureResult,
            launchIntent = { intent -> captureLauncher.launch(intent) },
        )
    }

    private fun handleCaptureResult(result: ActivityResult) {
        captureHelper?.handleActivityResult(
            ScreenCaptureHelper.REQUEST_CODE_CAPTURE,
            result.resultCode,
            result.data,
        )
    }

    private fun onCaptureResult(outcome: ScreenCaptureHelper.Outcome) {
        val service = CircleSearchService.instance
        when (outcome) {
            is ScreenCaptureHelper.Outcome.Success -> service?.presentSelection(outcome.bitmap)
            is ScreenCaptureHelper.Outcome.Failure -> service?.onCaptureFailed(outcome.reason)
        }
    }

    override fun onServiceDestroyed() {
        renderState()
    }

    // ------------------------------------------------------------------
    // UI state
    // ------------------------------------------------------------------

    /** Reflects the current permission state in the cards and the banner. */
    private fun renderState() {
        val overlayGranted = SettingsCompat.canDrawOverlays(this)
        val serviceEnabled = SettingsCompat.isAccessibilityServiceEnabled(this)
        val service = CircleSearchService.instance
        val bubbleEnabled = preferences.getBoolean(KEY_BUBBLE_ENABLED, true)

        binding.overlayPermissionButton.setText(
            if (overlayGranted) R.string.action_granted else R.string.action_grant
        )
        binding.overlayPermissionButton.isEnabled = !overlayGranted

        binding.accessibilityButton.setText(
            if (serviceEnabled && service != null) R.string.action_granted
            else R.string.action_enable
        )
        binding.accessibilityButton.isEnabled = !serviceEnabled || service == null

        binding.bubbleButton.setText(
            if (bubbleEnabled) R.string.action_hide else R.string.action_show
        )
        binding.bubbleButton.isEnabled = overlayGranted && service != null

        binding.statusText.text = when {
            !overlayGranted -> getString(R.string.status_overlay_missing)
            !serviceEnabled -> getString(R.string.status_service_disabled)
            service == null -> getString(R.string.status_service_disabled)
            else -> getString(R.string.status_ready)
        }

        binding.statusText.setBackgroundColor(
            when {
                overlayGranted && serviceEnabled && service != null -> getColor(R.color.brand_accent)
                else -> getColor(R.color.brand_primary)
            }
        )
    }

    private fun startSettings(intent: Intent, errorMessageRes: Int) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (errorMessageRes != 0) {
                Toast.makeText(this, errorMessageRes, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
    }
}
