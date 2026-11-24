package app.gamenative.externaldisplay

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer

/**
 * Invisible view that receives IME (soft keyboard) input from Android system keyboard
 * and forwards it to XServer as keyboard events for the game.
 *
 * This is needed when the system keyboard is pinned to the external display
 */
class IMEInputReceiver(
    context: Context,
    private val xServer: XServer,
    private val displayContext: Context = context,
) : FrameLayout(context) {

    val screenWidth: Int get() = xServer.screenInfo.width.toInt()
    val screenHeight: Int get() = xServer.screenInfo.height.toInt()

    suspend fun clickAt(x: Int, y: Int) {
        xServer.injectPointerMove(x, y)
        kotlinx.coroutines.delay(60)
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        kotlinx.coroutines.delay(80)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        kotlinx.coroutines.delay(60)
    }

    fun movePointerTo(x: Int, y: Int) {
        xServer.injectPointerMove(x, y)
    }

    companion object {
        /**
         * The text to paste: Android's own clipboard when it holds the pasted text and more (a keyboard may cut a very long paste
         * short), else what the keyboard committed. Carriage returns are ignored in the comparison (\r\n vs \n, a cut inside \r\n).
         */
        @JvmStatic
        fun fullClipboardText(committed: String, clipboard: String?): String {
            if (clipboard == null || clipboard.length <= committed.length) return committed
            val normal = { t: String -> t.replace("\r", "") }
            val clip = normal(clipboard)
            val got = normal(committed)
            return if (got.isNotEmpty() && clip.startsWith(got)) clipboard else committed
        }

        /** Commits this long (in characters) are pasted through the clipboard; typing is fine below (WoW chat takes 255). */
        const val PASTE_VIA_CLIPBOARD_MIN = 64
        /** Pieces of one paste that arrive within this time of each other are joined. */
        const val PASTE_GATHER_MS = 250L
        /** How long to wait for Wine to read the new clipboard before Ctrl+V (it checks every 2 s, at once after SelectionClear). */
        const val CLIPBOARD_READ_TIMEOUT_MS = 3000L

        private val SHIFTED_SYMBOLS = setOf(
            ')', '!', '@', '#', '$', '%', '^', '&', '*', '(',
            '_', '+', '{', '}', '|', ':', '"', '<', '>', '?', '~',
        )
    }

    private var imeSessionActive = false

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onCheckIsTextEditor(): Boolean {
        val isEditor = imeSessionActive && hasFocus()

        return isEditor
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        if (!imeSessionActive || !hasFocus()) {

            return BaseInputConnection(this, false)
        }

        // Disable autocomplete/suggestions so each key commits immediately
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
                             EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                             EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                              EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                              EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == null) return true
                // 1.2.15: a long paste (a RestedXP guide is tens of thousands of characters) went in as one key press per character
                // on the UI thread and froze everything; it now goes through the clipboard and one Ctrl+V.
                // 1.2.16: a keyboard may hand a very long paste over in several pieces (each one replaced the previous on the
                // clipboard, so only a part arrived): pieces that follow within PASTE_GATHER_MS are joined into one paste.
                if (pendingPaste.isNotEmpty() || Character.codePointCount(text, 0, text.length) >= PASTE_VIA_CLIPBOARD_MIN) {
                    pendingPaste.append(text)
                    removeCallbacks(flushPaste)
                    postDelayed(flushPaste, PASTE_GATHER_MS)
                }
                else typeText(text)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {

                if (beforeLength > 0) {
                    repeat(beforeLength) {
                        xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_BKSP, 0)
                        xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_BKSP)
                    }

                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_BKSP, 0)
                    xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_BKSP)

                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)

        if (hasWindowFocus && imeSessionActive && !hasFocus()) {
            post { requestFocus() }
        }
    }

    private data class KeyDispatch(
        val keyCode: Int,
        val requiresShift: Boolean,
    )

    private val pasteDeliveryLock = Any()
    private var pasteRevision = 0L
    private var pasteWorker: Thread? = null
    private val pendingPaste = StringBuilder()
    private val flushPaste = Runnable {
        val pasted = pendingPaste.toString()
        pendingPaste.setLength(0)
        if (imeSessionActive && isAttachedToWindow && pasted.isNotEmpty()) {
            pasteViaClipboard(fullClipboardText(pasted, androidClipboardText()))
        }
    }

    private fun androidClipboardText(): String? = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    /**
     * [text] goes to the X CLIPBOARD (owned by the X server); once Wine has read the new clipboard (or after
     * [CLIPBOARD_READ_TIMEOUT_MS]) the game gets Ctrl+V. Off the UI thread: the wait can take up to Wine's 2 s check.
     */
    fun pasteViaClipboard(text: String) {
        synchronized(pasteDeliveryLock) {
            pasteWorker?.interrupt()
            val revision = ++pasteRevision
            val generation = xServer.setClipboardText(text)
            pasteWorker = Thread({
                try {
                    val deadline = android.os.SystemClock.uptimeMillis() + CLIPBOARD_READ_TIMEOUT_MS
                    while (xServer.selectionManager.servedGeneration < generation && android.os.SystemClock.uptimeMillis() < deadline) {
                        Thread.sleep(20)
                    }
                    // Check and delivery are atomic against hide/detach or another paste.
                    synchronized(pasteDeliveryLock) delivery@ {
                        if (revision != pasteRevision || Thread.currentThread().isInterrupted) return@delivery
                        if (xServer.selectionManager.servedGeneration < generation) android.util.Log.w("IMEInputReceiver", "Clipboard not read by Wine yet; sending Ctrl+V anyway")
                        xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_CTRL_L, 0, false)
                        try {
                            try { xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_V, 0, false) }
                            finally { xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_V) }
                        } finally {
                            xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_CTRL_L)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    synchronized(pasteDeliveryLock) {
                        if (pasteWorker === Thread.currentThread()) pasteWorker = null
                    }
                }
            }, "clipboard-paste").apply { isDaemon = true; start() }
        }
    }

    /** A gathered paste goes out at once (its worker still waits for Wine before Ctrl+V). */
    private fun deliverPendingPaste() {
        if (pendingPaste.isEmpty()) return
        removeCallbacks(flushPaste)
        flushPaste.run()
    }

    /** No queued paste may cross a session boundary (the receiver leaving its window). */
    private fun cancelPendingPaste() {
        removeCallbacks(flushPaste)
        pendingPaste.setLength(0)
        synchronized(pasteDeliveryLock) {
            ++pasteRevision
            pasteWorker?.interrupt()
            pasteWorker = null
        }
    }

    override fun onDetachedFromWindow() {
        cancelPendingPaste()
        imeSessionActive = false
        super.onDetachedFromWindow()
    }

    fun typeText(text: CharSequence) {
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            when {
                codePoint <= 0xFFFF && charToKeyDispatch(codePoint.toChar()) != null -> sendCharacterToGame(codePoint.toChar())
                // jaredgei 634d1e9 (#43): a character with no key on the US layout (Hungarian ő, ű, á, é ..., Cyrillic) used to be
                // dropped; it is sent as the character itself.
                !Character.isISOControl(codePoint) -> xServer.injectTextCharacter(codePoint)
                else -> Unit // Never log input-derived codepoints: this path also types login secrets.
            }
        }
    }

    private fun sendCharacterToGame(char: Char) {
        val keyDispatch = charToKeyDispatch(char)
        if (keyDispatch != null) {
            val metaState = if (keyDispatch.requiresShift) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val downEvent = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_DOWN,
                keyDispatch.keyCode,
                0,
                metaState,
            )
            xServer.keyboard.onKeyEvent(downEvent)

            val upEvent = KeyEvent(
                0L,
                0L,
                KeyEvent.ACTION_UP,
                keyDispatch.keyCode,
                0,
                metaState,
            )
            xServer.keyboard.onKeyEvent(upEvent)

        } else {

        }
    }

    private fun charToKeyDispatch(char: Char): KeyDispatch? {
        val isUppercaseLetter = char in 'A'..'Z'
        val normalizedChar = if (isUppercaseLetter) char.lowercaseChar() else char

        val keyCode = when (normalizedChar) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'f' -> KeyEvent.KEYCODE_F
            'g' -> KeyEvent.KEYCODE_G
            'h' -> KeyEvent.KEYCODE_H
            'i' -> KeyEvent.KEYCODE_I
            'j' -> KeyEvent.KEYCODE_J
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'm' -> KeyEvent.KEYCODE_M
            'n' -> KeyEvent.KEYCODE_N
            'o' -> KeyEvent.KEYCODE_O
            'p' -> KeyEvent.KEYCODE_P
            'q' -> KeyEvent.KEYCODE_Q
            'r' -> KeyEvent.KEYCODE_R
            's' -> KeyEvent.KEYCODE_S
            't' -> KeyEvent.KEYCODE_T
            'u' -> KeyEvent.KEYCODE_U
            'v' -> KeyEvent.KEYCODE_V
            'w' -> KeyEvent.KEYCODE_W
            'x' -> KeyEvent.KEYCODE_X
            'y' -> KeyEvent.KEYCODE_Y
            'z' -> KeyEvent.KEYCODE_Z
            '0', ')' -> KeyEvent.KEYCODE_0
            '1', '!' -> KeyEvent.KEYCODE_1
            '2', '@' -> KeyEvent.KEYCODE_2
            '3', '#' -> KeyEvent.KEYCODE_3
            '4', '$' -> KeyEvent.KEYCODE_4
            '5', '%' -> KeyEvent.KEYCODE_5
            '6', '^' -> KeyEvent.KEYCODE_6
            '7', '&' -> KeyEvent.KEYCODE_7
            '8', '*' -> KeyEvent.KEYCODE_8
            '9', '(' -> KeyEvent.KEYCODE_9
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n' -> KeyEvent.KEYCODE_ENTER
            '\t' -> KeyEvent.KEYCODE_TAB
            '-', '_' -> KeyEvent.KEYCODE_MINUS
            '=', '+' -> KeyEvent.KEYCODE_EQUALS
            '[', '{' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']', '}' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\', '|' -> KeyEvent.KEYCODE_BACKSLASH
            ';', ':' -> KeyEvent.KEYCODE_SEMICOLON
            '\'', '"' -> KeyEvent.KEYCODE_APOSTROPHE
            ',', '<' -> KeyEvent.KEYCODE_COMMA
            '.', '>' -> KeyEvent.KEYCODE_PERIOD
            '/', '?' -> KeyEvent.KEYCODE_SLASH
            '`', '~' -> KeyEvent.KEYCODE_GRAVE
            else -> null
        } ?: return null

        val requiresShift = isUppercaseLetter || char in SHIFTED_SYMBOLS

        return KeyDispatch(keyCode = keyCode, requiresShift = requiresShift)
    }

    fun showKeyboard() {
        post {
            imeSessionActive = true
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            val imm = displayContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)

        }
    }

    fun hideKeyboard() {
        post {
            // Closing the keyboard (Back right after pasting a guide) sends the paste instead of dropping it; only a receiver
            // that leaves the window (game exit, screen gone) cancels it.
            deliverPendingPaste()
            val imm = displayContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
            imeSessionActive = false
            isFocusable = false
            isFocusableInTouchMode = false

        }
    }
}
