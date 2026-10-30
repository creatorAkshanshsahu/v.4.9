package com.aktv.app

import android.view.KeyEvent

/**
 * A focused on-screen player action owns ordinary DPAD navigation and confirmation.
 * Otherwise the player's legacy shortcuts (channel list/server cycle) handle those keys.
 */
internal object PlayerRemoteKeyPolicy {
    fun delegateToFocusedAction(
        keyCode: Int,
        focusedAction: Boolean,
        firstActionFocused: Boolean = false,
        lastActionFocused: Boolean = false
    ): Boolean {
        if (!focusedAction) return false
        return when (keyCode) {
            // The first action is GUIDE; keep LEFT as the player's server-cycle shortcut there.
            KeyEvent.KEYCODE_DPAD_LEFT -> !firstActionFocused
            // The last action lets RIGHT keep its channel-list shortcut.
            KeyEvent.KEYCODE_DPAD_RIGHT -> !lastActionFocused
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
            else -> false
        }
    }
}
