package app.gamenative.ui.screen.wow

/** Reserve on the UI thread, before request preparation can suspend. */
internal class ShellExecution {
    class Ticket internal constructor() { internal var cancelled = false; internal var process: Process? = null }
    private var current: Ticket? = null
    @Synchronized fun reserve(): Ticket {
        check(current == null) { "A shell command is already running." }
        return Ticket().also { current = it }
    }
    @Synchronized fun publish(ticket: Ticket, process: Process): Boolean {
        if (current !== ticket || ticket.cancelled) { process.destroyForcibly(); return false }
        ticket.process = process
        return true
    }
    @Synchronized fun cancel(): Process? {
        val ticket = current ?: return null
        ticket.cancelled = true
        return ticket.process
    }
    @Synchronized fun cancelled(ticket: Ticket) = ticket.cancelled || current !== ticket
    @Synchronized fun complete(ticket: Ticket) { if (current === ticket) current = null }
}
