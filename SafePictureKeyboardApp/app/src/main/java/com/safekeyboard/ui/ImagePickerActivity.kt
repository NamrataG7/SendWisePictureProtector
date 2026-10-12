package com.safekeyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.safekeyboard.media.ImageHandoff

/**
 * ImagePickerActivity - transparent, no-UI Activity that launches the
 * system image picker on behalf of the IME.
 *
 * Android IMEs cannot call startActivityForResult directly, so the IME
 * starts this Activity (FLAG_ACTIVITY_NEW_TASK) and registers a listener on
 * [ImageHandoff]. This Activity immediately launches a picker, forwards the
 * resulting Uri (or null on cancel) to [ImageHandoff.deliver], then finishes.
 *
 * Extends androidx.activity.ComponentActivity (NOT AppCompatActivity) —
 * registerForActivityResult requires ComponentActivity, and ComponentActivity
 * does not require an AppCompat theme, so the manifest-declared
 * android:style/Theme.Translucent.NoTitleBar works without crashing.
 *
 * Picker choice: Android 13+ Photo Picker (MediaStore.ACTION_PICK_IMAGES) is
 * used when available — it requires no storage/media permission at all and
 * is the Google-recommended modern API. On older APIs (< 33) we fall back to
 * ACTION_GET_CONTENT, which also requires no runtime permission on most
 * OEMs since it delegates to a document/gallery picker app.
 */
class ImagePickerActivity : ComponentActivity() {

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: Uri? = result.data?.data
        ImageHandoff.deliver(uri)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchPicker()
    }

    private fun launchPicker() {
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ Photo Picker: no READ_MEDIA_IMAGES permission needed.
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        try {
            pickImage.launch(intent)
        } catch (e: Exception) {
            // No picker available on this device/API level — fail open
            // (deliver null, as if the user cancelled).
            e.printStackTrace()
            ImageHandoff.deliver(null)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
