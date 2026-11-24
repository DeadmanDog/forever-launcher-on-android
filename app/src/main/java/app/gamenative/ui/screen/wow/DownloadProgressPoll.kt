package app.gamenative.ui.screen.wow

/**
 * 1.2.24: the in-game download line's polling. Until 1.2.23 every poll (2 s while WoW streamed its data, 30 s after) read
 * .build.info and the game locale (Config.wtf when no language is set in Settings) to find the cached total, and listed Data/data;
 * after the data was complete it kept doing so every 30 s for the whole session, for a line that is no longer drawn. Now the total
 * is looked up once it exists (it cannot change while the game runs: a new build needs a restart), each poll only lists the data
 * files, and the polling ends once the data is complete (it only grows during a session).
 */
internal class DownloadProgressPoll(private val total: () -> Long?, private val downloaded: () -> Long) {
    private var knownTotal: Long? = null

    /** The current progress, or null while the total is not known yet. */
    fun poll(): WowGameData.Progress? {
        val t = knownTotal ?: total()?.also { knownTotal = it } ?: return null
        return WowGameData.Progress(downloaded(), t)
    }

    companion object {
        const val STREAMING_MS = 2_000L
        const val UNKNOWN_TOTAL_MS = 30_000L

        /** How long to wait before the next poll; null = stop (the data is complete for this session). */
        fun nextDelayMs(progress: WowGameData.Progress?): Long? = when {
            progress == null -> UNKNOWN_TOTAL_MS
            progress.complete -> null
            else -> STREAMING_MS
        }
    }
}
