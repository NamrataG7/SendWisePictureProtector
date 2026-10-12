package com.safekeyboard.ime

/**
 * MessageBuffer - Maintains full message context
 *
 * CRITICAL PRIVACY REQUIREMENTS:
 * - Never persisted to disk
 * - Never logged
 * - Cleared on app switch, field change, or send completion
 * - Max length: 500 characters
 *
 * Purpose: Full-message analysis requires complete context, not word-by-word analysis
 */
class MessageBuffer {

    private val buffer = StringBuilder()
    private val maxLength = 500

    /**
     * Appends text to the message buffer
     */
    fun append(text: String) {
        if (buffer.length + text.length <= maxLength) {
            buffer.append(text)
        } else {
            // Trim to max length
            val remaining = maxLength - buffer.length
            if (remaining > 0) {
                buffer.append(text.substring(0, remaining))
            }
        }
    }

    /**
     * Removes the last character from the buffer (backspace handling)
     */
    fun deleteLastChar() {
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
        }
    }

    /**
     * Returns the current message
     */
    fun getCurrentMessage(): String {
        return buffer.toString()
    }

    /**
     * Returns the length of the current message
     */
    fun length(): Int {
        return buffer.length
    }

    /**
     * Checks if the buffer is empty
     */
    fun isEmpty(): Boolean {
        return buffer.isEmpty()
    }

    /**
     * Clears the entire buffer
     * Called on:
     * - App switch
     * - Input field change
     * - Explicit send completion
     */
    fun clear() {
        buffer.clear()
    }

    /**
     * Gets the last N characters from the buffer
     */
    fun getLastChars(n: Int): String {
        val start = maxOf(0, buffer.length - n)
        return buffer.substring(start)
    }
}
