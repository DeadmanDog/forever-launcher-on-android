package app.gamenative.ui.screen.wow

/** Bounded storage, independent of newline placement; secrets are redacted across read boundaries. */
internal class ShellOutput(private val secrets: List<String>, private val displayLimit: Int = 262144) {
    private val output = StringBuilder()
    private val retainLimit = displayLimit + (secrets.maxOfOrNull { it.length } ?: 0)
    @Synchronized fun append(buffer: CharArray, count: Int) {
        output.append(buffer, 0, minOf(count, (retainLimit - output.length).coerceAtLeast(0)))
    }
    @Synchronized fun text(): String {
        var text = output.toString()
        secrets.forEach { text = text.replace(it, "<redacted>") }
        return text.take(displayLimit)
    }
}
