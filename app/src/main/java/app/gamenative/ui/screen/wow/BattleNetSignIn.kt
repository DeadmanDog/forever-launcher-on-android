package app.gamenative.ui.screen.wow

import android.content.Context
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
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.SecretStore
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

    /** The saved login, or null. An e-mail saved unsealed by an older version is sealed on this first read (see [SecretStore]). */
    fun load(context: Context): Login? {
        val prefs = prefs(context)
        val sealedPassword = prefs.getString(KEY_PASSWORD, null) ?: return null
        if (prefs.getString(KEY_EMAIL_SEALED, null) == null && prefs.getString(KEY_EMAIL, null) == null) return null
        val email = SecretStore.get(prefs, KEY_EMAIL_SEALED, KEY_EMAIL)
        val password = SecretStore.openString(sealedPassword)
        if (email.isNullOrEmpty() || password == null) {
            forget(context)
            return null
        }
        return Login(email, password)
    }

    fun hasSaved(context: Context): Boolean {
        val prefs = prefs(context)
        return (prefs.getString(KEY_EMAIL_SEALED, null) != null || prefs.getString(KEY_EMAIL, null) != null) &&
            prefs.getString(KEY_PASSWORD, null) != null
    }

    /** Saves the login with both the e-mail and the password sealed; throws when the device cannot seal them (nothing is saved then). */
    fun save(context: Context, login: Login) {
        val sealer = SecretStore.sealer() ?: throw IllegalStateException("the Android KeyStore is not available")
        val password = SecretStore.sealToString(login.password, sealer) ?: throw IllegalStateException("cannot seal the password")
        val email = SecretStore.sealToString(login.email, sealer) ?: throw IllegalStateException("cannot seal the e-mail")
        prefs(context).edit().putString(KEY_EMAIL_SEALED, email).remove(KEY_EMAIL).putString(KEY_PASSWORD, password).apply()
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Fills in WoW's login form and signs in (Enter at the end). */
    suspend fun typeInto(receiver: IMEInputReceiver, login: Login, passwordOnly: Boolean = false) {
        val centerX = receiver.screenWidth / 2
        val emailY = (receiver.screenHeight * 0.505f).toInt()
        val passwordY = (receiver.screenHeight * 0.648f).toInt()
        val end = "\n"

        if (passwordOnly) {
            // "Remember Account" already filled in the e-mail: just the password.
            receiver.clickAt(centerX, passwordY)
            delay(100)
            receiver.typeText(login.password + end)
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
        receiver.typeText(login.password + end)
        delay(KEY_SETTLE_MS)
        receiver.movePointerTo(centerX, receiver.screenHeight / 2)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
    /** Where versions before the sealed e-mail kept it unsealed; read once for the migration, then removed. */
    private const val KEY_EMAIL = "email"
    private const val KEY_EMAIL_SEALED = "email_sealed"
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
        title = { Text(tx("Battle.net login", "Battle.net belépés")) },
        text = {
            Column {
                Text(tx("Saved encrypted on this device only. Select WoW's email field before signing in.",
                    "Titkosítva, csak ezen az eszközön tárolódik. Belépés előtt válaszd ki a WoW e-mail mezőjét."))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(tx("Email", "E-mail")) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(emailFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tx("Password", "Jelszó")) },
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
            ) { Text(tx("Save & sign in", "Mentés és belépés")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Cancel", "Mégse")) } },
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

    // Closed for good after the first sign-in of this session (from the menu or automatic), see AutoSignInGate.
    val gate = remember { AutoSignInGate() }

    // passwordOnlyHint: known from the frame the auto sign-in just recognised; null = look at the screen now.
    fun signIn(login: BattleNetSignIn.Login, delayMs: Long = MENU_CLOSE_MS, passwordOnlyHint: Boolean? = null) {
        val receiver = PluviaApp.imeInputReceiver ?: run {
            // Without the input receiver nothing can be typed: say so (it used to return without a trace).
            android.util.Log.w("WowAutoSignIn", "cannot sign in: the game input receiver is not ready")
            return
        }
        gate.stop()
        onBeforeTyping()
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            val passwordOnly = passwordOnlyHint ?: run {
                val view = gameView()
                view != null && LoginScreenCapture.withFrame(view) { frame ->
                    frame != null && (LoginScreenDetector.matches(frame) || LoginScreenDetector.looksLikeLoginForm(frame)) &&
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

    // Auto sign-in: once per game session, only on the login form itself (exact colours and the form's shape; see AutoSignInGate and
    // LoginScreenDetector). It stops for good after the first sign-in, or once the login form has been seen and then gone.
    LaunchedEffect(Unit) {
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
        while (System.currentTimeMillis() - startedAt < AUTO_TIMEOUT_MS && !gate.closed) {
            // Rarely while the feature is off or has no saved login (it only reads two settings then); slow while the screen is
            // clearly something else (and slower still after a few minutes: each look copies the live game picture, which must not go
            // on for long during play); quick once the login form is showing up (most areas match, or the kind-based test does).
            delay(autoPollDelayMs(enabled, lastScore, lastKind, System.currentTimeMillis() - startedAt))
            if (gate.closed) break
            val on = PrefManager.bnetAutoSignIn
            val saved = BattleNetSignIn.hasSaved(context)
            enabled = on && saved
            if (!announced) {
                announced = true
                // In the default diagnostics (warnings and above): why auto sign-in does or does not do anything.
                android.util.Log.w("WowAutoSignIn", "armed: switch=$on savedLogin=$saved gameView=${gameView() != null}")
            }
            if (!enabled) {
                gate.reset()
                continue
            }
            val view = gameView() ?: continue
            val look = LoginScreenCapture.withFrame(view) { frame ->
                if (frame == null) ScreenLook(LoginScreenDetector.Look(-1, false, false, false)) else {
                    // One pass over the six areas. The text for the log is built only when the log line will really be written.
                    val seen = LoginScreenDetector.look(frame)
                    val logDue = System.currentTimeMillis() - lastLogAt >= LOG_GAP_MS &&
                        (seen.score != lastLogged || seen.kindOfLogin != lastLoggedSoft || seen.shape != lastLoggedShape)
                    val details = when {
                        !logDue -> ""
                        (seen.exact || seen.kindOfLogin) && !seen.shape -> LoginScreenDetector.shapeDetails(frame)    // colours match, shape does not: show why
                        !seen.exact && !seen.kindOfLogin && seen.score >= 2 -> LoginScreenDetector.details(frame)
                        else -> ""
                    }
                    ScreenLook(seen, (seen.exact || seen.kindOfLogin) && LoginScreenDetector.emailFieldHasText(frame), details)
                }
            }
            val seen = look.seen
            lastScore = seen.score
            lastKind = seen.kindOfLogin
            val now = System.currentTimeMillis()
            if ((seen.score != lastLogged || seen.kindOfLogin != lastLoggedSoft || seen.shape != lastLoggedShape) && now - lastLogAt >= LOG_GAP_MS) {
                lastLogged = seen.score; lastLoggedSoft = seen.kindOfLogin; lastLoggedShape = seen.shape; lastLogAt = now
                android.util.Log.w("WowAutoSignIn", "capture=${LoginScreenCapture.lastResult} matchedAreas=${seen.score}/${LoginScreenDetector.regions.size} " +
                    "kindOfLogin=${seen.kindOfLogin} formShape=${seen.shape}" + if (look.details.isNotEmpty()) " ${look.details}" else "")
            }
            when (gate.observe(seen, now)) {
                AutoSignInGate.Decision.WAIT -> Unit
                AutoSignInGate.Decision.STOP -> {
                    finished = true
                    android.util.Log.w("WowAutoSignIn", "the login form was seen and is gone: auto sign-in is off for this session")
                }
                AutoSignInGate.Decision.SIGN_IN -> {
                    finished = true
                    val login = BattleNetSignIn.load(context)
                    if (login == null) {
                        android.util.Log.w("WowAutoSignIn", "login screen recognised but the saved login could not be read")
                        break
                    }
                    android.util.Log.w("WowAutoSignIn", "login screen recognised (exact=${seen.exact} formShape=${seen.shape}); signing in (passwordOnly=${look.passwordOnly})")
                    app.gamenative.ui.util.SnackbarManager.show(tr("Battle.net auto-login…", "Battle.net automatikus belépés…"))
                    signIn(login, delayMs = 0L, passwordOnlyHint = look.passwordOnly)
                }
            }
        }
        if (enabled && !finished && !gate.closed) {
            android.util.Log.w("WowAutoSignIn", "gave up: no login screen was recognised within ${AUTO_TIMEOUT_MS / 60000} minutes")
        }
        gate.stop()
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

/** What one look at the game view found, plus whether the e-mail is already filled in and the numbers for the diagnostics log. */
private class ScreenLook(
    val seen: LoginScreenDetector.Look,
    val passwordOnly: Boolean = false,
    /** Region averages of a partly matching frame (or the shape numbers of a colour match), for the diagnostics. */
    val details: String = "",
)

private const val MENU_CLOSE_MS = 600L
private const val AUTO_POLL_MS = 700L
private const val AUTO_POLL_FAST_MS = 200L
private const val AUTO_POLL_OFF_MS = 3000L
private const val AUTO_POLL_LATE_MS = 2500L
private const val AUTO_SLOW_AFTER_MS = 3 * 60 * 1000L
private const val LOG_GAP_MS = 5000L
/** The login screen comes up within the first minutes of a session; the detector does not watch the game for longer than this. */
private const val AUTO_TIMEOUT_MS = 10 * 60 * 1000L
private const val DIALOG_SETTLE_MS = 300L
