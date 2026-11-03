package app.gamenative.ui.screen.wow

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.Crypto
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.externaldisplay.IMEInputReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

object BattleNetSignIn {
    val requested = MutableStateFlow(false)

    data class Login(val email: String, val password: String) {
        /** A data class would print the password in any log line; keep it out. */
        override fun toString() = "Login(email=$email, password=***)"
    }

    fun load(context: Context): Login? {
        val prefs = prefs(context)
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val sealed = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            Login(email, String(Crypto.decrypt(Base64.decode(sealed, Base64.NO_WRAP))))
        } catch (e: Exception) {

            forget(context)
            null
        }
    }

    fun hasSaved(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getString(KEY_EMAIL, null) != null && prefs.getString(KEY_PASSWORD, null) != null
    }

    fun save(context: Context, login: Login) {
        val sealed = Base64.encodeToString(Crypto.encrypt(login.password.toByteArray()), Base64.NO_WRAP)
        prefs(context).edit().putString(KEY_EMAIL, login.email).putString(KEY_PASSWORD, sealed).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    suspend fun typeInto(receiver: IMEInputReceiver, login: Login, passwordOnly: Boolean = false) {
        val centerX = receiver.screenWidth / 2
        val emailY = (receiver.screenHeight * 0.505f).toInt()
        val passwordY = (receiver.screenHeight * 0.648f).toInt()

        if (passwordOnly) {
            // "Remember Account" already filled in the e-mail: just the password.
            receiver.clickAt(centerX, passwordY)
            delay(100)
            receiver.typeText(login.password + "\n")
            delay(KEY_SETTLE_MS)
            receiver.movePointerTo(centerX, receiver.screenHeight / 2)
            return
        }

        receiver.clickAt(centerX, passwordY)
        delay(200)
        receiver.clickAt(centerX, emailY)
        delay(200)
        receiver.typeText(login.email + "\t")
        delay(KEY_SETTLE_MS)
        receiver.typeText(login.password + "\n")
        delay(KEY_SETTLE_MS)
        receiver.movePointerTo(centerX, receiver.screenHeight / 2)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "password"
    private const val KEY_SETTLE_MS = 300L
}

@Composable
fun BattleNetCredentialDialog(
    onDismiss: () -> Unit,
    onConfirm: (BattleNetSignIn.Login) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val emailFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(DIALOG_SETTLE_MS)
        emailFocus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle.net login") },
        text = {
            Column {
                Text("Saved encrypted on this device only. Select WoW's email field before signing in.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(emailFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = email.isNotBlank() && password.isNotEmpty(),
                onClick = { onConfirm(BattleNetSignIn.Login(email.trim(), password)) },
            ) { Text("Save & sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BattleNetSignInHost(
    onBeforeTyping: () -> Unit,
    gameView: () -> android.view.SurfaceView? = { null },
    onCursorHidden: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requested by BattleNetSignIn.requested.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    val tr = rememberTx()

    // passwordOnlyHint: known from the frame the auto sign-in just recognised; null = look at the screen now.
    fun signIn(login: BattleNetSignIn.Login, delayMs: Long = MENU_CLOSE_MS, passwordOnlyHint: Boolean? = null) {
        val receiver = PluviaApp.imeInputReceiver ?: run {
            // Without the input receiver nothing can be typed: say so (it used to return without a trace).
            android.util.Log.w("WowAutoSignIn", "cannot sign in: the game input receiver is not ready")
            return
        }
        onBeforeTyping()
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            val passwordOnly = passwordOnlyHint ?: run {
                val view = gameView()
                view != null && LoginScreenCapture.withFrame(view) { frame ->
                    frame != null && (LoginScreenDetector.matches(frame) || LoginScreenDetector.looksLikeLogin(frame)) &&
                        LoginScreenDetector.emailFieldHasText(frame)
                }
            }
            // The pointer jumps around while typing: keep it out of sight until the form is filled in.
            onCursorHidden(true)
            try {
                BattleNetSignIn.typeInto(receiver, login, passwordOnly)
            } finally {
                onCursorHidden(false)
            }
        }
    }

    // Auto sign-in: once per game session, only when the login screen is really on screen (see LoginScreenDetector).
    LaunchedEffect(Unit) {
        var matchedPolls = 0
        var softPolls = 0
        var softSince = 0L
        var loosePolls = 0
        var looseSince = 0L
        var lastLoggedShape = false
        var lastLogged = -2
        var lastLoggedSoft = false
        var lastLogAt = 0L
        var lastScore = 0
        var lastKind = false
        var enabled = true
        var announced = false
        var finished = false
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < AUTO_TIMEOUT_MS) {
            // Rarely while the feature is off or has no saved login (it only reads two settings then); slow while the screen is
            // clearly something else (and slower still after a few minutes: each look copies the live game picture, which must not go
            // on for long during play); quick once the login form is showing up (most areas match, or the kind-based test does).
            delay(autoPollDelayMs(enabled, lastScore, lastKind, System.currentTimeMillis() - startedAt))
            val on = PrefManager.bnetAutoSignIn
            val saved = BattleNetSignIn.hasSaved(context)
            enabled = on && saved
            if (!announced) {
                announced = true
                // In the default diagnostics (warnings and above): why auto sign-in does or does not do anything.
                android.util.Log.w("WowAutoSignIn", "armed: switch=$on savedLogin=$saved gameView=${gameView() != null}")
            }
            if (!enabled) {
                matchedPolls = 0; softPolls = 0; loosePolls = 0
                continue
            }
            val view = gameView() ?: continue
            val look = LoginScreenCapture.withFrame(view) { frame ->
                if (frame == null) ScreenLook(-1, false) else {
                    // One pass over the six areas. The text for the log is built only when the log line will really be written.
                    val seen = LoginScreenDetector.look(frame)
                    val logDue = System.currentTimeMillis() - lastLogAt >= LOG_GAP_MS &&
                        (seen.score != lastLogged || seen.kindOfLogin != lastLoggedSoft || seen.shape != lastLoggedShape)
                    val details = when {
                        !logDue -> ""
                        seen.kindOfLogin && !seen.shape -> LoginScreenDetector.shapeDetails(frame)    // kinds match, shape does not: show why
                        !seen.exact && !seen.kindOfLogin && seen.score >= 2 -> LoginScreenDetector.details(frame)
                        else -> ""
                    }
                    ScreenLook(seen.score, seen.exact, (seen.exact || seen.kindOfLogin) && LoginScreenDetector.emailFieldHasText(frame),
                        seen.kindOfLogin, seen.formLike, details)
                }
            }
            val score = look.score
            lastScore = score
            lastKind = look.kindOfLogin
            val now = System.currentTimeMillis()
            if ((score != lastLogged || look.kindOfLogin != lastLoggedSoft || look.formLike != lastLoggedShape) && now - lastLogAt >= LOG_GAP_MS) {
                lastLogged = score; lastLoggedSoft = look.kindOfLogin; lastLoggedShape = look.formLike; lastLogAt = now
                android.util.Log.w("WowAutoSignIn", "capture=${LoginScreenCapture.lastResult} matchedAreas=$score/${LoginScreenDetector.regions.size} " +
                    "kindOfLogin=${look.kindOfLogin} formShape=${look.formLike}" + if (look.details.isNotEmpty()) " ${look.details}" else "")
            }
            matchedPolls = if (look.loginScreen) matchedPolls + 1 else 0
            if (look.formLike) { if (softPolls == 0) softSince = now; softPolls++ } else softPolls = 0
            if (look.kindOfLogin) { if (loosePolls == 0) looseSince = now; loosePolls++ } else loosePolls = 0
            // The exact values need 3 looks in a row. Colours of the right kinds AND the shape of the form need 4 looks over at least half a
            // second (quick: the shape is what keeps a lookalike out). Colours of the right kinds alone, when the shape test does not
            // agree (another resolution or UI scale can change how the text is drawn), still count - as in 1.3.19 - but slowly: 6 looks
            // over at least 2 seconds. The shape numbers of such a frame are in the log line above.
            val stable = matchedPolls >= AUTO_STABLE_POLLS ||
                (softPolls >= AUTO_SOFT_STABLE_POLLS && now - softSince >= AUTO_SOFT_STABLE_MS) ||
                (loosePolls >= AUTO_LOOSE_STABLE_POLLS && now - looseSince >= AUTO_LOOSE_STABLE_MS)
            if (stable) {
                val login = BattleNetSignIn.load(context) ?: break
                val passwordOnly = look.passwordOnly
                android.util.Log.w("WowAutoSignIn", "login screen recognised (exact=${look.loginScreen}); signing in (passwordOnly=$passwordOnly)")
                app.gamenative.ui.util.SnackbarManager.show(tr("Battle.net auto-login…", "Battle.net automatikus belépés…"))
                finished = true
                signIn(login, delayMs = 0L, passwordOnlyHint = passwordOnly)
                break
            }
        }
        if (enabled && !finished) android.util.Log.w("WowAutoSignIn", "gave up: no login screen was recognised within ${AUTO_TIMEOUT_MS / 60000} minutes")
    }

    LaunchedEffect(requested) {
        if (!requested) return@LaunchedEffect
        BattleNetSignIn.requested.value = false
        val saved = BattleNetSignIn.load(context)
        if (saved != null) {
            signIn(saved)
        } else {
            showDialog = true
        }
    }

    if (showDialog) {
        BattleNetCredentialDialog(
            onDismiss = { showDialog = false },
            onConfirm = { login ->
                showDialog = false
                BattleNetSignIn.save(context, login)
                signIn(login)
            },
        )
    }
}

/**
 * How long the auto sign-in waits before the next look at the game picture. Off or no saved login: rarely (settings only, no picture).
 * A colour-kind login candidate: quickly, at any time (a login that appears late is still found within a fraction of a second once its
 * colours show). A partial exact match (3+ areas) is only a reason to hurry in the first minutes: later it is just a game scene, and
 * each look copies the live game picture, so the slow pace wins (the 1.3.21 order let a lasting partial match keep the fast pace for
 * the whole ten minutes).
 */
internal fun autoPollDelayMs(enabled: Boolean, lastScore: Int, lastKind: Boolean, sinceStartMs: Long): Long = when {
    !enabled -> AUTO_POLL_OFF_MS
    lastKind -> AUTO_POLL_FAST_MS
    sinceStartMs > AUTO_SLOW_AFTER_MS -> AUTO_POLL_LATE_MS
    lastScore >= 3 -> AUTO_POLL_FAST_MS
    else -> AUTO_POLL_MS
}

/** What one look at the game view found: how many login-form areas matched, whether it is the login screen, and whether the e-mail is already filled in. */
private class ScreenLook(
    val score: Int, val loginScreen: Boolean, val passwordOnly: Boolean = false,
    /** Not the exact colours, but the kind-based test says login screen. */
    val kindOfLogin: Boolean = false,
    /** ... and the frame also has the shape of the form. */
    val formLike: Boolean = false,
    /** Region averages of a partly matching frame (or the shape numbers of a kind match), for the diagnostics. */
    val details: String = "",
)

private const val MENU_CLOSE_MS = 600L
private const val AUTO_POLL_MS = 700L
private const val AUTO_POLL_FAST_MS = 200L
private const val AUTO_POLL_OFF_MS = 3000L
private const val AUTO_POLL_LATE_MS = 2500L
private const val AUTO_SLOW_AFTER_MS = 3 * 60 * 1000L
private const val AUTO_STABLE_POLLS = 3
private const val AUTO_SOFT_STABLE_POLLS = 4
private const val AUTO_SOFT_STABLE_MS = 500L
private const val AUTO_LOOSE_STABLE_POLLS = 6
private const val AUTO_LOOSE_STABLE_MS = 2000L
private const val LOG_GAP_MS = 5000L
private const val AUTO_TIMEOUT_MS = 10 * 60 * 1000L
private const val DIALOG_SETTLE_MS = 300L
