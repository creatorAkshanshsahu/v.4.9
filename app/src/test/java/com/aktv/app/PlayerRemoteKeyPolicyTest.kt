package com.aktv.app

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRemoteKeyPolicyTest {
    @Test fun `focused player actions receive navigation and confirm keys`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        ).forEach { key -> assertTrue(PlayerRemoteKeyPolicy.delegateToFocusedAction(key, focusedAction = true)) }
        assertTrue(PlayerRemoteKeyPolicy.delegateToFocusedAction(KeyEvent.KEYCODE_DPAD_LEFT, focusedAction = true))
    }

    @Test fun `player edge actions retain server and channel-list shortcuts`() {
        assertFalse(PlayerRemoteKeyPolicy.delegateToFocusedAction(
            KeyEvent.KEYCODE_DPAD_LEFT, focusedAction = true, firstActionFocused = true
        ))
        assertTrue(PlayerRemoteKeyPolicy.delegateToFocusedAction(
            KeyEvent.KEYCODE_DPAD_RIGHT, focusedAction = true, firstActionFocused = true
        ))
        assertTrue(PlayerRemoteKeyPolicy.delegateToFocusedAction(
            KeyEvent.KEYCODE_DPAD_CENTER, focusedAction = true, firstActionFocused = true
        ))
        assertFalse(PlayerRemoteKeyPolicy.delegateToFocusedAction(
            KeyEvent.KEYCODE_DPAD_RIGHT, focusedAction = true, lastActionFocused = true
        ))
    }

    @Test fun `player shortcuts remain active when focus is not on a player action`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER
        ).forEach { key -> assertFalse(PlayerRemoteKeyPolicy.delegateToFocusedAction(key, focusedAction = false)) }
    }

    @Test fun `guide and menu hardware keys are never swallowed by action focus`() {
        assertFalse(PlayerRemoteKeyPolicy.delegateToFocusedAction(KeyEvent.KEYCODE_GUIDE, focusedAction = true))
        assertFalse(PlayerRemoteKeyPolicy.delegateToFocusedAction(KeyEvent.KEYCODE_MENU, focusedAction = true))
    }
}
