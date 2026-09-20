@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ticnitsi.baofengprogram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import com.ticnitsi.baofengprogram.ble.*
import com.ticnitsi.baofengprogram.core.*
import com.ticnitsi.baofengprogram.update.UpdateDialog
import com.ticnitsi.baofengprogram.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Programmer() }
    }
}

private val Light = lightColorScheme(primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFB9EEE0), onPrimaryContainer = Color(0xFF00251E),
    secondary = Color(0xFF725700), secondaryContainer = Color(0xFFE4EAE7), onSecondaryContainer = Color(0xFF25342F),
    background = Color(0xFFF8FAF9), surface = Color(0xFFF8FAF9), surfaceContainer = Color(0xFFEDF1EF),
    surfaceVariant = Color(0xFFE1E8E4), surfaceContainerHighest = Color(0xFFE1E8E4),
    onSurface = Color(0xFF18201D), onSurfaceVariant = Color(0xFF4C5953), outline = Color(0xFF748078))
private val Dark = darkColorScheme(primary = Color(0xFF7DD9C5), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF174F43), onPrimaryContainer = Color(0xFFB9EEE0),
    secondary = Color(0xFFE2C36A), secondaryContainer = Color(0xFF33433C), onSecondaryContainer = Color(0xFFE0EAE4),
    background = Color(0xFF111514), surface = Color(0xFF111514), surfaceContainer = Color(0xFF1C2320),
    surfaceVariant = Color(0xFF303A35), surfaceContainerHighest = Color(0xFF303A35),
    onSurface = Color(0xFFE1E7E3), onSurfaceVariant = Color(0xFFB8C3BD), outline = Color(0xFF84958A))

@Composable
private fun Programmer(vm: ProgramViewModel = viewModel()) {
    val updates: UpdateViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val scan by vm.scanner.state.collectAsStateWithLifecycle()
    val dark = state.theme == "Dark" || (state.theme == "System" && isSystemInDarkTheme())
    val view = LocalView.current
    val window = (LocalContext.current as? android.app.Activity)?.window
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    DisposableEffect(state.busy) {
        view.keepScreenOn = state.busy
        onDispose { view.keepScreenOn = false }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var menu by remember { mutableStateOf(false) }
        var appearance by remember { mutableStateOf(false) }
        var updateDialog by rememberSaveable { mutableStateOf(false) }
        var confirm by remember { mutableStateOf<String?>(null) }
        var editor by rememberSaveable { mutableStateOf<Int?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val context = LocalContext.current
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (context.hasBluetoothPermissions()) vm.scanner.start() else vm.report("Allow Bluetooth and location access to find radios")
        }
        var exportOriginal by remember { mutableStateOf(false) }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { vm.export(it, exportOriginal) }
        }
        val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restore) }
        LaunchedEffect(state.message) {
            state.message?.let {
                snackbar.showSnackbar(it, withDismissAction = true,
                    duration = if (state.error) SnackbarDuration.Indefinite else SnackbarDuration.Short)
                vm.dismissMessage()
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(title = { Column {
                    Text("Baofeng Programmer", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.model.name + "  /  " + state.connection.message, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }, actions = {
                    Box {
                        ToolButton("More options", Icons.Default.MoreVert, onClick = { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Appearance") }, leadingIcon = { Icon(Icons.Default.Palette, null) }, onClick = { menu = false; appearance = true })
                            DropdownMenuItem(text = { Text("Check for updates") }, enabled = !state.busy,
                                leadingIcon = { Icon(Icons.Default.SystemUpdate, null) },
                                onClick = { menu = false; updateDialog = true; updates.check() })
                            DropdownMenuItem(text = { Text("Export edited backup") }, enabled = state.image != null && !state.busy,
                                onClick = { menu = false; exportOriginal = false; export.launch("${state.model.id}-edited.bfp") })
                            DropdownMenuItem(text = { Text("Export original backup") }, enabled = state.image != null && !state.busy,
                                onClick = { menu = false; exportOriginal = true; export.launch("${state.model.id}-original.bfp") })
                            DropdownMenuItem(text = { Text("Restore backup") }, enabled = state.image != null && !state.busy,
                                onClick = { menu = false; confirm = "restore" })
                            DropdownMenuItem(text = { Text("Discard local changes") }, enabled = state.dirty && !state.busy,
                                onClick = { menu = false; confirm = "discard" })
                        }
                    }
                })
            },
            bottomBar = {
                Column {
                    TransferBar(state, onRead = { if (state.dirty) confirm = "read" else vm.read() },
                        onWrite = { confirm = "write" }, onCancel = vm::cancel)
                    NavigationBar {
                        listOf("Radio" to Icons.Default.Bluetooth, "Memories" to Icons.Default.List, "Radio settings" to Icons.Default.Tune)
                            .forEachIndexed { index, item ->
                                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                    icon = { Icon(item.second, null) }, label = { Text(item.first, maxLines = 1) })
                            }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> RadioPage(state, scan, onScan = {
                        if (context.hasBluetoothPermissions()) vm.scanner.start() else permissions.launch(bluetoothPermissions())
                    }, onStop = vm.scanner::stop, onConnect = vm::connect, onDisconnect = vm::disconnect,
                        onModel = { model -> if (state.image == null) vm.selectModel(model) else confirm = "model:${model.id}" })
                    1 -> MemoryPage(state) { editor = it }
                    2 -> SettingsPage(state, vm::setting)
                }
            }
        }
        if (updateDialog) UpdateDialog(updates) { updateDialog = false }
        editor?.let { number ->
            state.memories.firstOrNull { it.number == number }?.let { channel ->
                MemoryEditor(channel, state.model, onDismiss = { editor = null }, onSave = vm::saveMemory)
            }
        }
        if (appearance) AlertDialog(onDismissRequest = { appearance = false }, title = { Text("Appearance") }, text = {
            Column { listOf("System", "Light", "Dark").forEach { theme ->
                Row(Modifier.fillMaxWidth().clickable { vm.theme(theme) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.theme == theme, onClick = { vm.theme(theme) }); Text(theme)
                }
            } }
        }, confirmButton = { TextButton(onClick = { appearance = false }) { Text("Done") } })
        confirm?.let { action ->
            val title = when (action) { "write" -> "Write changes to radio?"; "restore" -> "Restore a backup?"; else -> "Discard local changes?" }
            val description = when (action) {
                "write" -> "${state.changedMemories.size} memories and ${state.changedSettings.size} radio settings changed. Keep the radio powered on and close to your phone until verification finishes."
                "restore" -> "Choose a backup for ${state.model.name}. Memories and supported settings replace local edits. Nothing is written until you review and choose Write."
                else -> "Unsaved radio changes will be replaced. Your original radio backup is kept."
            }
            AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = {
                Column {
                    Text(description)
                    if (action == "write") {
                        if (state.changedMemories.isNotEmpty()) Text("\nChannels: " + state.changedMemories.take(15).joinToString() + if (state.changedMemories.size > 15) " ..." else "")
                        Text(SettingsCodec.definitions(state.model).filter { it.key in state.changedSettings }.joinToString("\n") { it.label })
                    }
                }
            }, confirmButton = { TextButton(onClick = {
                confirm = null
                when {
                    action == "write" -> vm.write()
                    action == "read" -> vm.read()
                    action == "restore" -> restore.launch(arrayOf("*/*"))
                    action.startsWith("model:") -> vm.selectModel(RadioModels.byId(action.substringAfter(':')))
                    else -> vm.discard()
                }
            }) { Text(if (action == "write") "Write and verify" else if (action == "restore") "Choose backup" else "Discard") } },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun TransferBar(state: ProgramState, onRead: () -> Unit, onWrite: () -> Unit, onCancel: () -> Unit) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.progress?.stage ?: "Connecting", style = MaterialTheme.typography.labelLarge)
                        val progress = state.progress
                        if (progress != null && progress.total > 0) {
                            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        } else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    ToolButton("Cancel transfer", Icons.Default.Close, onClick = onCancel)
                }
            } else {
                if (state.dirty) Text("${state.changedMemories.size} memories, ${state.changedSettings.size} settings changed",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onRead, enabled = state.connection.connected, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Read radio")
                    }
                    Button(onClick = onWrite, enabled = state.canWrite && state.dirty, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Write")
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioPage(state: ProgramState, scan: ScanState, onScan: () -> Unit, onStop: () -> Unit,
    onConnect: (NearbyRadio) -> Unit, onDisconnect: () -> Unit, onModel: (RadioModel) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            SectionTitle("Your radio")
            ChoiceField("Model", state.model.name, RadioModels.supported.map { it.id to it.name }, enabled = !state.busy,
                modifier = Modifier.padding(horizontal = 16.dp), onSelect = { onModel(RadioModels.byId(it)) })
            if (state.model != RadioModels.mini) Text("Experimental profile. Not tested on a physical UV-5RH.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            ListItem(headlineContent = { Text(if (state.connection.connected) state.deviceName else "Bluetooth") },
                supportingContent = { Text(state.connection.message) }, leadingContent = { Icon(Icons.Default.Bluetooth, null) },
                trailingContent = { if (state.connection.connected) ToolButton("Disconnect", Icons.Default.LinkOff, enabled = !state.busy, onClick = onDisconnect) })
            state.image?.let { image ->
                HorizontalDivider()
                ListItem(headlineContent = { Text("${state.memories.count { !it.empty }} programmed memories") },
                    supportingContent = { Text(if (state.dirty) "Local edits pending" else "Saved radio image") },
                    leadingContent = { Icon(Icons.Default.Storage, null) })
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (scan.scanning) ToolButton("Stop scan", Icons.Default.Stop, onClick = onStop)
                else ToolButton("Scan for radios", Icons.Default.Refresh, enabled = !state.busy && !state.connection.connected, onClick = onScan)
            }
            if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            scan.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (scan.radios.isEmpty()) {
                Text(if (scan.scanning) "Looking for Bluetooth devices..." else "No devices found", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!scan.scanning) Button(onClick = onScan, enabled = !state.busy && !state.connection.connected, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Icon(Icons.Default.BluetoothSearching, null); Spacer(Modifier.width(8.dp)); Text("Scan")
                }
            }
        }
        items(scan.radios, key = { it.address }) { radio ->
            ListItem(modifier = Modifier.clickable(enabled = !state.busy && !state.connection.connected) { onConnect(radio) },
                headlineContent = { Text(radio.name, maxLines = 2) }, supportingContent = { Text(radio.address + "  /  ${radio.rssi} dBm") },
                leadingContent = { Icon(if (radio.likelyRadio) Icons.Default.Radio else Icons.Default.Bluetooth, null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, "Connect") })
        }
    }
}

@Composable
private fun MemoryPage(state: ProgramState, onEdit: (Int) -> Unit) {
    if (state.image == null) { EmptyRadio("No memories loaded"); return }
    var search by rememberSaveable { mutableStateOf("") }
    var showEmpty by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(state.memories, search, showEmpty) {
        state.memories.filter { (showEmpty || !it.empty) && (search.isBlank() || it.name.contains(search, true) ||
            it.number.toString() == search || Frequencies.format(it.receiveHz).contains(search)) }
    }
    Column {
        OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search memories") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            trailingIcon = { if (search.isNotEmpty()) ToolButton("Clear search", Icons.Default.Close, onClick = { search = "" }) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} channels", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("Show empty", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp)); Switch(checked = showEmpty, onCheckedChange = { showEmpty = it })
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            if (filtered.isEmpty()) item { Text("No matching memories", Modifier.padding(24.dp)) }
            items(filtered, key = { it.number }) { channel ->
                ListItem(modifier = Modifier.clickable(enabled = !state.busy) { onEdit(channel.number) },
                    leadingContent = { Text("%03d".format(channel.number), style = MaterialTheme.typography.labelLarge,
                        color = if (channel.number in state.changedMemories) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(channel.name.ifBlank { if (channel.empty) "Empty" else "Channel ${channel.number}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { if (!channel.empty) Column {
                        Text("RX ${Frequencies.format(channel.receiveHz)} MHz")
                        Text(if (channel.transmitHz == null) "TX disabled" else "TX ${Frequencies.format(channel.transmitHz)} MHz", style = MaterialTheme.typography.bodySmall)
                    } },
                    trailingContent = { Column(horizontalAlignment = Alignment.End) {
                        if (!channel.empty) Text(if (channel.narrow) "NFM" else "FM", style = MaterialTheme.typography.labelSmall)
                        if (channel.number in state.changedMemories) Icon(Icons.Default.Edit, "Changed", Modifier.size(16.dp))
                        else Icon(Icons.Default.ChevronRight, "Edit memory")
                    } })
                HorizontalDivider(Modifier.padding(start = 64.dp), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun SettingsPage(state: ProgramState, onChange: (String, Int) -> Unit) {
    val image = state.image ?: run { EmptyRadio("No radio settings loaded"); return }
    val groups = remember(state.model) { SettingsCodec.definitions(state.model).groupBy { it.group } }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        groups.forEach { (group, fields) ->
            item { SectionTitle(group) }
            items(fields, key = { it.key }) { field ->
                val binary = field.choices.size == 2 && field.choices.map { it.label }.toSet() == setOf("Off", "On")
                if (binary && field.choices.any { it.value == field.value(image) }) {
                    ListItem(headlineContent = { Text(field.label + if (field.key in state.changedSettings) " *" else "") },
                        trailingContent = { Switch(checked = field.label(image) == "On", enabled = !state.busy,
                            onCheckedChange = { on -> onChange(field.key, field.choices.first { it.label == if (on) "On" else "Off" }.value) }) })
                } else ChoiceField(field.label + if (field.key in state.changedSettings) " *" else "", field.label(image),
                    field.choices.map { it.value to it.label }, enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp), onSelect = { onChange(field.key, it) })
            }
        }
    }
}

@Composable
private fun EmptyRadio(title: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Radio, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp)); Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text("Connect and read your radio first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionTitle(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun ToolButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}

@Composable
internal fun <T> ChoiceField(label: String, value: String, choices: List<Pair<T, String>>, modifier: Modifier = Modifier,
    enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(value = value, onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (key, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onSelect(key) }) }
        }
    }
}
