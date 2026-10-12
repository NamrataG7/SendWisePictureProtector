package com.safekeyboard.ime

/**
 * SendIntentDetector - Infers when user is about to send a message
 *
 * CRITICAL: We cannot access the Send button, so we must infer intent through signals:
 * 1. Enter key press (many apps map Enter → Send)
 * 2. Keyboard hidden (user tapped outside → Send)
 * 3. Typing pause > 1.5s (cognitive completion)
 * 4. Cursor moved away (message finalized)
 * 5. App context = chat app (reduce false positives)
 *
 * Each signal contributes weight. If score ≥ threshold → intent_to_send = true
 */
class SendIntentDetector {

    // Signal weights
    private val WEIGHT_ENTER_KEY = 0.6f
    private val WEIGHT_KEYBOARD_HIDDEN = 0.5f
    private val WEIGHT_TYPING_PAUSE = 0.3f
    private val WEIGHT_CURSOR_MOVE = 0.2f
    private val WEIGHT_CHAT_APP = 0.2f

    // Threshold for determining send intent
    private val SEND_INTENT_THRESHOLD = 0.7f

    // Current signals
    private var enterKeyPressed = false
    private var keyboardHidden = false
    private var typingPaused = false
    private var cursorMovedAway = false
    private var isChatApp = false

    // Known social media, messaging, gaming, and communication app packages
    // SCOPE: Social media DMs, gaming app DMs, YouTube comments only
    // NOT productivity apps (Google Docs, Word, Notes, etc.)
    private val chatAppPackages = setOf(
        // Direct Messaging Apps
        "com.whatsapp",
        "com.facebook.orca", // Messenger
        "com.instagram.android",
        "org.telegram.messenger",
        "com.discord",
        "com.viber.voip",
        "jp.naver.line.android",
        "com.tencent.mm", // WeChat
        "com.google.android.apps.messaging", // Google Messages
        "com.android.mms", // Default SMS

        // Social Media Platforms
        "com.twitter.android",
        "com.snapchat.android",
        "com.facebook.katana", // Facebook
        "com.linkedin.android",
        "com.reddit.frontpage",
        "com.tumblr",
        "com.pinterest",
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok alternate

        // YouTube (for comments)
        "com.google.android.youtube",
        "com.google.android.youtube.tv", // YouTube TV

        // Gaming Apps with Chat/DMs
        "com.roblox.client", // Roblox
        "tv.twitch.android.app", // Twitch
        "com.epicgames.fortnite", // Fortnite
        "com.pubg.imobile", // PUBG Mobile
        "com.mobile.legends", // Mobile Legends
        "com.garena.game.fctw", // Free Fire
        "com.dts.freefireth",
        "com.supercell.clashofclans", // Clash of Clans
        "com.supercell.brawlstars", // Brawl Stars
        "com.activision.callofduty.shooter", // Call of Duty Mobile
        "com.ea.gp.fifamobile", // FIFA Mobile
        "com.riotgames.league.wildrift", // League of Legends
        "com.tencent.ig", // PUBG Mobile (Tencent)
        "com.miHoYo.GenshinImpact", // Genshin Impact
        "com.ea.gp.apexlegendsmobilefps", // Apex Legends Mobile
        "com.blizzard.messenger", // Battle.net
        "com.valvesoftware.android.steam.community", // Steam

        // Dating Apps (high risk for harassment)
        "com.tinder",
        "com.bumble.app",
        "com.match.android.matchmobile",
        "com.hinge.app",
        "com.pof.android",
        "com.okcupid.okcupid",

        // Other Communication Platforms
        "com.skype.raider",
        "us.zoom.videomeetings",
        "com.microsoft.teams"
    )

    /**
     * Records that Enter key was pressed
     */
    fun recordEnterKeyPress() {
        enterKeyPressed = true
    }

    /**
     * Records that keyboard was hidden
     */
    fun recordKeyboardHidden() {
        keyboardHidden = true
    }

    /**
     * Records that user paused typing
     */
    fun recordTypingPause() {
        typingPaused = true
    }

    /**
     * Records that cursor moved away from end
     */
    fun recordCursorMoveAway() {
        cursorMovedAway = true
    }

    /**
     * Updates the current app context
     */
    fun updateAppContext(packageName: String) {
        isChatApp = chatAppPackages.contains(packageName)
    }

    /**
     * Checks if the current app is a social/gaming/communication app
     * where pre-send interventions are appropriate.
     *
     * Returns true for: Social media DMs, gaming app chats, YouTube comments
     * Returns false for: Productivity apps (Google Docs, Word, Notes, Search)
     */
    fun isSocialCommunicationApp(): Boolean {
        return isChatApp
    }

    /**
     * Calculates the send intent score based on all signals
     */
    private fun calculateScore(): Float {
        var score = 0f

        if (enterKeyPressed) score += WEIGHT_ENTER_KEY
        if (keyboardHidden) score += WEIGHT_KEYBOARD_HIDDEN
        if (typingPaused) score += WEIGHT_TYPING_PAUSE
        if (cursorMovedAway) score += WEIGHT_CURSOR_MOVE
        if (isChatApp) score += WEIGHT_CHAT_APP

        return score
    }

    /**
     * Determines if the user is about to send the message
     */
    fun isUserAboutToSend(): Boolean {
        val score = calculateScore()
        return score >= SEND_INTENT_THRESHOLD
    }

    /**
     * Gets the current send intent score (for debugging/logging)
     */
    fun getCurrentScore(): Float {
        return calculateScore()
    }

    /**
     * Resets all signals
     */
    fun reset() {
        enterKeyPressed = false
        keyboardHidden = false
        typingPaused = false
        cursorMovedAway = false
        // Keep isChatApp as it's context-based, not action-based
    }

    /**
     * Gets a human-readable summary of active signals
     */
    fun getActiveSignals(): String {
        val signals = mutableListOf<String>()

        if (enterKeyPressed) signals.add("Enter")
        if (keyboardHidden) signals.add("Hidden")
        if (typingPaused) signals.add("Pause")
        if (cursorMovedAway) signals.add("Cursor")
        if (isChatApp) signals.add("ChatApp")

        return signals.joinToString(", ")
    }
}
