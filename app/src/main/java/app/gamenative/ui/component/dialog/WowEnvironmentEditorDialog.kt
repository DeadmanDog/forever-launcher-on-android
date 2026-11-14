package app.gamenative.ui.component.dialog

import app.gamenative.ui.screen.wow.tx
import app.gamenative.ui.screen.wow.rememberTx
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.ui.screen.wow.DeveloperShell
import app.gamenative.ui.screen.wow.WowEnvironmentEditor
import com.winlator.container.ContainerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WowEnvironmentEditorDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val t = rememberTx()
    var entries by remember { mutableStateOf<List<WowEnvironmentEditor.Entry>>(emptyList()) }
    var original by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val active = PluviaApp.keepAlive   // snapshot state: no polling
    var message by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            original = withContext(Dispatchers.IO) {
                ContainerManager(context).getContainerById("wow_forever")?.envVars ?: error("WoW container has not been created yet")
            }
            entries = WowEnvironmentEditor.load(original); ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message }
    }
    val enabled = ready && !saving && !active
    val dirty = ready && runCatching { WowEnvironmentEditor.serialize(entries) != original }.getOrDefault(true)
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(tx("Environment Variables", "Környezeti változók")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx("Saved user overrides take priority over automatic WoW/cache settings. Changes take effect on the next launch.", "A mentett saját értékek elsőbbséget élveznek az automatikus WoW/cache beállításokkal szemben. A következő indítástól érvényesek."))
                if (!ready || saving) CircularProgressIndicator()
                if (active) Text(tx("Editing is unavailable during an active session.", "Játék közben nem szerkeszthető."))
                entries.forEach { entry ->
                    Column {
                        Text(entry.name, style = MaterialTheme.typography.labelLarge)
                        Text(if (DeveloperShell.isSensitiveName(entry.name)) "<redacted>" else entry.value.ifEmpty { tx("(empty)", "(üres)") },
                            style = MaterialTheme.typography.bodySmall)
                        EnvironmentHandlingNote(entry.name)
                        Row {
                            TextButton(enabled = enabled, onClick = {
                                editing = entry.name; name = entry.name; value = entry.value; reveal = false; message = null
                            }) { Text(tx("Edit", "Szerkesztés")) }
                            TextButton(enabled = enabled, onClick = {
                                entries = WowEnvironmentEditor.delete(entries, entry.name)
                                if (editing == entry.name) { editing = null; name = ""; value = "" }
                            }) { Text(tx("Delete", "Törlés")) }
                        }
                    }
                }
                Text(if (editing == null) tx("Add variable", "Új változó") else tx("Edit variable", "Változó szerkesztése"), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(name, { name = it }, label = { Text(tx("Name", "Név")) }, singleLine = true, enabled = enabled)
                EnvironmentHandlingNote(name.trim())
                OutlinedTextField(value, { value = it }, label = { Text(tx("Value", "Érték")) }, enabled = enabled,
                    visualTransformation = if (DeveloperShell.isSensitiveName(name) && !reveal) PasswordVisualTransformation() else VisualTransformation.None)
                if (DeveloperShell.isSensitiveName(name)) TextButton(enabled = enabled, onClick = { reveal = !reveal }) {
                    Text(if (reveal) tx("Hide value", "Érték elrejtése") else tx("Show value", "Érték mutatása"))
                }
                Row {
                    TextButton(enabled = enabled, onClick = {
                        try {
                            val entry = WowEnvironmentEditor.Entry(name, value)
                            entries = editing?.let { WowEnvironmentEditor.edit(entries, it, entry) } ?: WowEnvironmentEditor.add(entries, entry)
                            editing = null; name = ""; value = ""; message = null
                        } catch (e: IllegalArgumentException) { message = e.message }
                    }) { Text(if (editing == null) tx("Add", "Hozzáadás") else tx("Update draft", "Vázlat frissítése")) }
                    TextButton(enabled = enabled, onClick = { editing = null; name = ""; value = ""; reveal = false }) { Text(tx("Clear", "Ürítés")) }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(enabled = enabled && dirty && name.isEmpty() && editing == null, onClick = {
            saving = true; message = null
            val draft = entries; val expected = original
            scope.launch {
                try {
                    original = withContext(Dispatchers.IO) { WowEnvironmentEditor.save(context, draft, expected) { PluviaApp.keepAlive } }
                    entries = WowEnvironmentEditor.load(original)
                    message = t("Environment variables saved.", "Környezeti változók mentve."); onSaved()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: t("Unable to save environment variables.", "A környezeti változók nem menthetők.") }
                finally { saving = false }
            }
        }) { Text(tx("Save", "Mentés")) } },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text(tx("Close", "Bezárás")) } },
    )
}

/** Says when a saved variable does not simply replace the launcher's value (see WowLaunchEnvironment.mergeUserOverrides). */
@Composable
private fun EnvironmentHandlingNote(name: String) {
    when (WowEnvironmentEditor.handling(name)) {
        WowEnvironmentEditor.Handling.IGNORED -> Text(
            tx("Ignored at launch: the launcher sets this variable itself.",
                "Indításkor figyelmen kívül marad: ezt a változót az indító állítja be."),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        WowEnvironmentEditor.Handling.APPENDED -> Text(
            tx("Added after the launcher's own entries at launch.",
                "Indításkor az indító saját bejegyzései után kerül."),
            style = MaterialTheme.typography.bodySmall)
        WowEnvironmentEditor.Handling.OVERRIDE -> Unit
    }
}
