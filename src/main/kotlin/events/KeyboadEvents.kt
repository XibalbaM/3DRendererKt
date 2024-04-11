package fr.xibalba.renderer.events

import fr.xibalba.renderer.CancellableEvent
import fr.xibalba.renderer.KeyboardManager

open class KeyboardEvent(val keyboardManager: KeyboardManager, val keyCode: Int) : CancellableEvent() {

    class KeyPressedEvent(keyboardManager: KeyboardManager, keyCode: Int) : KeyboardEvent(keyboardManager, keyCode)

    class KeyReleasedEvent(keyboardManager: KeyboardManager, keyCode: Int) : KeyboardEvent(keyboardManager, keyCode)
}