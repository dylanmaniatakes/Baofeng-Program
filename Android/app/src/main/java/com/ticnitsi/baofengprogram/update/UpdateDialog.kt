package com.ticnitsi.baofengprogram.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ticnitsi.baofengprogram.BuildConfig
import java.util.Locale

@Composable
fun UpdateDialog(vm: UpdateViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) vm.install(installer::launch)
        else vm.report("Android has not allowed update installation from this app.")
    }
    fun close() { vm.cancel(); onDismiss() }
    AlertDialog(onDismissRequest = ::close,
        title = { Text("App updates") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Installed: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                Text(state.message, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if (state.busy) {
                    val release = state.release
                    if (state.downloading && release != null) LinearProgressIndicator(progress = { state.bytes.toFloat() / release.size }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.release?.let { release ->
                    if (state.file == null && !state.busy) Text(String.format(Locale.US, "Download size: %.1f MB", release.size / 1048576.0))
                    if (release.notes.isNotBlank()) {
                        Text("Release notes", style = MaterialTheme.typography.titleSmall)
                        Text(release.notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (!state.busy) {
                when {
                    state.file != null -> TextButton(onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) vm.install(installer::launch)
                        else runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                            .onFailure { vm.report("Unable to open Android installation settings") }
                    }) { Text("Install update") }
                    state.release != null -> TextButton(onClick = vm::download) { Text("Download") }
                    state.error -> TextButton(onClick = vm::check) { Text("Retry") }
                    else -> TextButton(onClick = ::close) { Text("Done") }
                }
            }
        },
        dismissButton = {
            if (state.busy || state.release != null || state.error) TextButton(onClick = ::close) { Text(if (state.busy) "Cancel" else "Not now") }
        })
}
