package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperShellDialog(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var container by remember { mutableStateOf<Container?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var wineMode by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    val shell = remember { DeveloperShell() }
    DisposableEffect(Unit) { onDispose { shell.stop() } }
    LaunchedEffect(Unit) {
        try {
            container = withContext(Dispatchers.IO) {
                ContainerManager(context).getContainerById("wow_forever") ?: error("Container missing.")
            }
        } catch (e: Exception) { error = e.message }
    }
    Dialog(onDismissRequest = { shell.stop(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx("Developer shell", "Fejlesztői shell"), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { shell.stop(); onClose() }) { Text(tx("Close", "Bezárás")) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !running, onClick = { wineMode = false }) { Text(if (!wineMode) "● Android/Bionic shell" else "Android/Bionic shell") }
                TextButton(enabled = !running, onClick = { wineMode = true }) { Text(if (wineMode) "● Wine CMD" else "Wine CMD") }
                Text(tx("No command runs until Run is pressed. Timeout: 30 seconds.", "Semmi nem fut, amíg nem nyomod meg a Futtatást. Időkorlát: 30 mp."))
                OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text(tx("Command", "Parancs")) }, enabled = !running, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = !running && command.isNotBlank() && container != null, onClick = {
                    val c = container ?: return@TextButton
                    val text = command; history.add(text); running = true
                    val ticket = shell.prepareRun()
                    scope.launch {
                        try { output = withContext(Dispatchers.IO) {
                            val request = if (wineMode) shell.wineRequest(context, c, text) else shell.androidRequest(c, text)
                            shell.run(request, ticket = ticket)
                        } } catch (e: Exception) { output = e.message.orEmpty() }
                        finally { shell.finishRun(ticket); running = false }
                    }
                }) { Text(tx("Run", "Futtatás")) }
                TextButton(enabled = running, onClick = { shell.stop() }) { Text(tx("Stop", "Leállítás")) }
                Text("stdout / stderr:\n$output")
                Text(tx("Local history", "Előzmények"))
                history.takeLast(20).reversed().forEach { text -> TextButton(enabled = !running, onClick = { command = text }) { Text(text) } }
            }
        }
    }
}
