package app.gamenative.ui.screen.wow

/**
 * Decides, look by look, when the Battle.net auto sign-in signs in, and when it stops for the session.
 *
 * Signing in (the rules that worked on the player's phone since 1.3.21):
 * - the exact colours of all six areas ([LoginScreenDetector.Look.exact]) for [EXACT_POLLS] looks in a row;
 * - colours of the right kinds AND the shape of the form ([LoginScreenDetector.Look.formLike]) for [FORM_POLLS] looks over at least
 *   [FORM_MS];
 * - colours of the right kinds alone ([LoginScreenDetector.Look.kindOfLogin]: another resolution, UI scale or client build moves the
 *   exact averages and can defeat the shape test) for [KINDS_POLLS] looks over at least [KINDS_MS].
 * 1.2.0's first build demanded the form shape on every path, and on the current login screen it then never signed in.
 *
 * Stopping for good (auto sign-in only acts on the login screen of this start): after a sign-in, after a sign-in from the menu
 * ([stop]), and once the login screen was clearly seen ([SEEN_POLLS] login looks in a row) and then [GONE_POLLS] readable looks in a row
 * (about 15 s) show something else (the player signed in by hand, or the game moved on). A look that could not be read (score -1) breaks the
 * streaks but never counts as "something else"; a few login-like frames during a fade do not count as "clearly seen".
 */
internal class AutoSignInGate {
    enum class Decision { WAIT, SIGN_IN, STOP }

    private var exactPolls = 0
    private var formPolls = 0
    private var formSince = 0L
    private var kindsPolls = 0
    private var kindsSince = 0L
    private var loginStreak = 0
    private var goneLooks = 0
    private var sawLogin = false
    var closed = false
        private set

    fun stop() {
        closed = true
    }

    /** The detector is switched off or there is no saved login: start counting again from zero. */
    fun reset() {
        exactPolls = 0
        formPolls = 0
        kindsPolls = 0
        loginStreak = 0
        goneLooks = 0
    }

    fun observe(look: LoginScreenDetector.Look, nowMs: Long): Decision {
        if (closed) return Decision.STOP
        if (look.score < 0) {
            exactPolls = 0
            formPolls = 0
            kindsPolls = 0
            loginStreak = 0
            return Decision.WAIT
        }
        if (look.exact || look.kindOfLogin) {
            goneLooks = 0
            if (++loginStreak >= SEEN_POLLS) sawLogin = true
            exactPolls = if (look.exact) exactPolls + 1 else 0
            if (look.formLike) { if (formPolls == 0) formSince = nowMs; formPolls++ } else formPolls = 0
            if (look.kindOfLogin) { if (kindsPolls == 0) kindsSince = nowMs; kindsPolls++ } else kindsPolls = 0
            val stable = exactPolls >= EXACT_POLLS ||
                (formPolls >= FORM_POLLS && nowMs - formSince >= FORM_MS) ||
                (kindsPolls >= KINDS_POLLS && nowMs - kindsSince >= KINDS_MS)
            return if (stable) finish(Decision.SIGN_IN) else Decision.WAIT
        }
        exactPolls = 0
        formPolls = 0
        kindsPolls = 0
        loginStreak = 0
        if (sawLogin && ++goneLooks >= GONE_POLLS) return finish(Decision.STOP)
        return Decision.WAIT
    }

    private fun finish(decision: Decision): Decision {
        closed = true
        return decision
    }

    companion object {
        const val EXACT_POLLS = 3
        const val FORM_POLLS = 4
        const val FORM_MS = 500L
        const val KINDS_POLLS = 6
        const val KINDS_MS = 2000L
        const val SEEN_POLLS = 3
        /** About 15 s of other screens at the slow poll rate: a dialog over the login screen for a moment does not end it. */
        const val GONE_POLLS = 20
    }
}
