package com.safekeyboard.media

import android.net.Uri

/**
 * ImageHandoff - tiny singleton bridge used to hand a picked image [Uri]
 * back from the transparent [com.safekeyboard.ui.ImagePickerActivity] to the
 * running [com.safekeyboard.ime.SafeKeyboardIME].
 *
 * IMEs cannot call startActivityForResult directly, so the IME launches a
 * transparent picker Activity (new task) and registers a one-shot listener
 * here. The Activity calls [deliver] with the picked Uri (or null on
 * cancel) and then finishes itself.
 */
object ImageHandoff {
    var listener: ((Uri?) -> Unit)? = null

    fun deliver(uri: Uri?) {
        listener?.invoke(uri)
        listener = null
    }
}
