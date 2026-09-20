@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.ticnitsi.baofengprogram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.ticnitsi.baofengprogram.core.*

@Composable
internal fun MemoryEditor(original: MemoryChannel, model: RadioModel, onDismiss: () -> Unit, onSave: (MemoryChannel) -> String?) {
    var name by rememberSaveable { mutableStateOf(original.name) }
    var rx by rememberSaveable { mutableStateOf(Frequencies.format(original.receiveHz)) }
    var tx by rememberSaveable { mutableStateOf(Frequencies.format(original.transmitHz)) }
    var txEnabled by rememberSaveable { mutableStateOf(original.empty || original.transmitHz != null) }
    var simplex by rememberSaveable { mutableStateOf(original.empty || original.receiveHz == original.transmitHz) }
    var rxTone by rememberSaveable { mutableIntStateOf(original.receiveTone) }
    var txTone by rememberSaveable { mutableIntStateOf(original.transmitTone) }
    var power by rememberSaveable { mutableIntStateOf(original.power) }
    var narrow by rememberSaveable { mutableStateOf(original.narrow) }
    var scan by rememberSaveable { mutableStateOf(original.scan) }
    var lock by rememberSaveable { mutableStateOf(original.busyLock) }
    var signal by rememberSaveable { mutableIntStateOf(original.signal) }
    var ptt by rememberSaveable { mutableIntStateOf(original.pttId) }
    var dtmf by rememberSaveable { mutableIntStateOf(original.receiveDtmf) }
    var hopping by rememberSaveable { mutableStateOf(original.hopping) }
    var error by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    fun edited(): MemoryChannel = original.copy(name = name, receiveHz = Frequencies.parse(rx),
        transmitHz = if (!txEnabled) null else Frequencies.parse(if (simplex) rx else tx),
        receiveTone = rxTone, transmitTone = txTone, power = power, narrow = narrow, scan = scan, busyLock = lock,
        signal = signal, pttId = ptt, receiveDtmf = dtmf, hopping = hopping)
    fun close() {
        if (runCatching { edited() == original }.getOrDefault(false) ||
            (original.empty && rx.isBlank() && name.isBlank())) onDismiss() else discard = true
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
            TopAppBar(title = { Text("Memory %03d".format(original.number)) }, navigationIcon = {
                ToolButton("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = ::close)
            }, actions = { if (!original.empty) ToolButton("Delete memory", Icons.Default.Delete, onClick = { delete = true }) })
        }, bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                    Button(onClick = {
                        error = runCatching { onSave(edited()) }.getOrElse { it.message ?: "Invalid memory" }
                        if (error == null) onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save memory") }
                }
            }
        }) { padding ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") },
                    supportingText = { Text("${name.length}/12") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { FrequencyField("Receive frequency (MHz)", rx) { rx = it } }
                item { ToggleRow("Transmit enabled", txEnabled) { txEnabled = it } }
                if (txEnabled) {
                    item { ToggleRow("Simplex", simplex) { simplex = it; if (!it && tx.isBlank()) tx = rx } }
                    if (!simplex) item { FrequencyField("Transmit frequency (MHz)", tx) { tx = it } }
                    item { ChoiceField("Transmit tone", ToneCodes.label(txTone), ToneCodes.choices.map { it to ToneCodes.label(it) }, onSelect = { txTone = it }) }
                }
                item { ChoiceField("Receive tone", ToneCodes.label(rxTone), ToneCodes.choices.map { it to ToneCodes.label(it) }, onSelect = { rxTone = it }) }
                item { ChoiceField("Power", model.powerNames.getOrNull(power) ?: "Unknown ($power)", model.powerNames.mapIndexed { i, s -> i to s }, onSelect = { power = it }) }
                item {
                    Column {
                        Text("Bandwidth", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false to "Wide / FM", true to "Narrow / NFM").forEachIndexed { i, choice ->
                                SegmentedButton(selected = narrow == choice.first, onClick = { narrow = choice.first }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(choice.second) }
                            }
                        }
                    }
                }
                item { ToggleRow("Include in scan", scan) { scan = it } }
                item { ToggleRow("Busy channel lockout", lock) { lock = it } }
                item { HorizontalDivider(); Text("Signaling", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
                item { ChoiceField("PTT ID", listOf("Off", "Start", "End", "Both").getOrNull(ptt) ?: "Unknown ($ptt)",
                    listOf("Off", "Start", "End", "Both").mapIndexed { i, s -> i to s }, onSelect = { ptt = it }) }
                item { ChoiceField("Signaling group", (signal + 1).toString(), (0..19).map { it to (it + 1).toString() }, onSelect = { signal = it }) }
                if (model.mini) {
                    item { ChoiceField("Receive DTMF", listOf("Off", "On").getOrNull(dtmf) ?: "Unknown ($dtmf)",
                        listOf(0 to "Off", 1 to "On"), onSelect = { dtmf = it }) }
                    item { ToggleRow("Frequency hopping", hopping) { hopping = it } }
                }
            }
        }
        if (delete || discard) AlertDialog(onDismissRequest = { delete = false; discard = false },
            title = { Text(if (delete) "Delete memory ${original.number}?" else "Discard editor changes?") },
            text = { Text(if (delete) "Deletion is staged locally until you write to the radio." else "Changes in this editor have not been saved.") },
            confirmButton = { TextButton(onClick = {
                if (delete) { error = onSave(MemoryChannel(original.number)); delete = false; if (error == null) onDismiss() }
                else onDismiss()
            }) { Text(if (delete) "Delete" else "Discard") } },
            dismissButton = { TextButton(onClick = { delete = false; discard = false }) { Text("Cancel") } })
    }
}

@Composable
private fun FrequencyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Spacer(Modifier.width(8.dp)); Switch(checked = checked, onCheckedChange = onChange)
    }
}
