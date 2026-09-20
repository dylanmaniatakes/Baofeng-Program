package com.ticnitsi.baofengprogram

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ticnitsi.baofengprogram.ble.*
import com.ticnitsi.baofengprogram.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ProgramState(
    val model: RadioModel = RadioModels.mini,
    val connection: ConnectionState = ConnectionState(),
    val deviceName: String = "",
    val busy: Boolean = false,
    val progress: TransferProgress? = null,
    val image: RadioImage? = null,
    val memories: List<MemoryChannel> = emptyList(),
    val changedMemories: Set<Int> = emptySet(),
    val changedSettings: Set<String> = emptySet(),
    val message: String? = null,
    val error: Boolean = false,
    val theme: String = "System",
    val canWrite: Boolean = false,
) {
    val dirty get() = changedMemories.isNotEmpty() || changedSettings.isNotEmpty()
}

class ProgramViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("programmer", 0)
    private val mutable = MutableStateFlow(ProgramState(theme = preferences.getString("theme", "System")!!))
    val state = mutable.asStateFlow()
    val scanner = BleScanner(application, viewModelScope)
    private var transport: BleRadioTransport? = null
    private var connectionJob: Job? = null
    private var operation: Job? = null
    private var baseline: RadioImage? = null
    private var sourceDevice: String? = null
    private val workspace = AtomicFile(File(application.filesDir, "workspace.bin"))
    private val backups = File(application.filesDir, "backups").apply { mkdirs() }

    init {
        runCatching {
            if (workspace.baseFile.exists()) DataInputStream(workspace.openRead()).use { input ->
                fun image(): RadioImage {
                    val size = input.readInt()
                    require(size in 64..BackupCodec.MAX_SIZE)
                    return BackupCodec.decode(ByteArray(size).also(input::readFully))
                }
                baseline = image()
                val edited = image()
                require(edited.modelId == baseline!!.modelId && edited.identity == baseline!!.identity)
                sourceDevice = input.readUTF()
                publish(edited)
                mutable.update { it.copy(message = "Saved workspace restored") }
            }
        }.onFailure { baseline = null; sourceDevice = null; report("Saved workspace could not be opened: ${it.message}") }
    }

    fun report(message: String, error: Boolean = true) { mutable.update { it.copy(message = message, error = error) } }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun theme(value: String) {
        preferences.edit().putString("theme", value).apply()
        mutable.update { it.copy(theme = value) }
    }
    fun selectModel(model: RadioModel) {
        if (state.value.busy || model.id == state.value.model.id) return
        disconnect()
        baseline = null
        sourceDevice = null
        workspace.delete()
        mutable.update { ProgramState(model = model, theme = it.theme) }
    }

    fun connect(radio: NearbyRadio) = runOperation {
        scanner.stop()
        disconnectInternal()
        val client = BleRadioTransport(getApplication(), radio.address)
        transport = client
        mutable.update { it.copy(deviceName = radio.name) }
        connectionJob = viewModelScope.launch {
            client.state.collect { connection ->
                mutable.update { it.copy(connection = connection,
                    canWrite = connection.connected && sourceDevice == client.deviceId && baseline != null) }
            }
        }
        client.connect()
        report("Connected to ${radio.name}", false)
    }

    fun disconnect() { if (!state.value.busy) disconnectInternal() }
    private fun disconnectInternal() {
        connectionJob?.cancel()
        transport?.close()
        transport = null
        mutable.update { it.copy(connection = ConnectionState(), canWrite = false) }
    }
    fun cancel() {
        operation?.cancel()
        disconnectInternal()
    }
    private fun driver(): ColorRadioDriver = ColorRadioDriver(
        transport ?: error("Connect to a radio first"),
        progress = { progress -> mutable.update { it.copy(progress = progress) } },
        log = { Log.i("RadioProtocol", it) },
    )

    fun read() = runOperation {
        val image = driver().read(state.value.model)
        ChannelCodec.readAll(image)
        withContext(Dispatchers.IO) { saveBackup(image, "read") }
        baseline = image
        sourceDevice = transport?.deviceId
        publish(image)
        persist()
        Log.i("RadioProtocol", "Read complete: ${image.model.name}, ${state.value.memories.count { !it.empty }} programmed memories; original backup saved")
        report("Read complete. Original radio backup saved.", false)
    }

    fun write() = runOperation {
        check(state.value.canWrite) { "Read this connected radio before writing" }
        val before = baseline ?: error("Read the radio first")
        val edited = state.value.image ?: error("No radio image")
        withContext(Dispatchers.IO) { saveBackup(before, "before-write"); saveBackup(edited, "planned") }
        try {
            val blocks = driver().write(before, edited)
            baseline = edited
            publish(edited)
            persist()
            Log.i("RadioProtocol", "Write verified: $blocks changed blocks")
            report("Write verified: $blocks changed blocks", false)
        } catch (e: Exception) {
            sourceDevice = null
            mutable.update { it.copy(canWrite = false) }
            throw e
        }
    }

    fun saveMemory(memory: MemoryChannel): String? = try {
        check(!state.value.busy)
        publish(ChannelCodec.patch(state.value.image ?: error("Read the radio first"), memory))
        persist()
        null
    } catch (e: Exception) { e.message ?: "Unable to save memory" }

    fun setting(key: String, value: Int) {
        if (state.value.busy) return
        runCatching {
            publish(SettingsCodec.patch(state.value.image ?: error("Read the radio first"), key, value))
            persist()
        }.onFailure { report(it.message ?: "Unable to change setting") }
    }
    fun discard() {
        if (state.value.busy) return
        baseline?.let { publish(it); runCatching { persist() }.onFailure { e -> report(e.message ?: "Save failed") } }
    }

    fun export(uri: Uri, original: Boolean = false) = runOperation {
        val image = (if (original) baseline else state.value.image) ?: error("Read a radio first")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use {
                checkNotNull(it) { "Cannot open destination" }.write(BackupCodec.encode(image))
            }
        }
        report("Backup exported", false)
    }

    fun restore(uri: Uri) = runOperation {
        val current = state.value.image ?: error("Read your radio before restoring a backup")
        val imported = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri).use {
                val stream = checkNotNull(it)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= BackupCodec.MAX_SIZE) { "Backup is too large" }
                    output.write(buffer, 0, size)
                }
                BackupCodec.decode(output.toByteArray())
            }
        }
        require(imported.modelId == current.modelId) { "Backup is for a different radio model" }
        // Restore editable data onto this radio's image; never transplant calibration or unknown fields.
        var edited = current
        for (channel in ChannelCodec.readAll(imported)) edited = ChannelCodec.patch(edited, channel)
        for (field in SettingsCodec.definitions(current.model)) {
            val value = field.value(imported)
            if (field.choices.any { it.value == value }) edited = SettingsCodec.patch(edited, field.key, value)
        }
        publish(edited)
        persist()
        report("Backup staged. Review changes before writing.", false)
    }

    private fun publish(image: RadioImage) {
        val memories = ChannelCodec.readAll(image)
        val original = baseline
        val changed = if (original == null) emptySet() else memories.filter {
            !image.block((it.number - 1) * 32, 32).contentEquals(original.block((it.number - 1) * 32, 32))
        }.map { it.number }.toSet()
        val settings = if (original == null) emptySet() else SettingsCodec.definitions(image.model)
            .filter { it.value(image) != it.value(original) }.map { it.key }.toSet()
        mutable.update { it.copy(model = image.model, image = image, memories = memories,
            changedMemories = changed, changedSettings = settings,
            canWrite = it.connection.connected && sourceDevice == transport?.deviceId && sourceDevice != null) }
    }

    private fun persist() {
        val original = baseline ?: return
        val edited = state.value.image ?: return
        val stream = workspace.startWrite()
        try {
            val output = DataOutputStream(stream)
            for (image in listOf(original, edited)) {
                val bytes = BackupCodec.encode(image)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            output.writeUTF(sourceDevice.orEmpty())
            output.flush()
            workspace.finishWrite(stream)
        } catch (e: Exception) { workspace.failWrite(stream); throw e }
    }

    private fun saveBackup(image: RadioImage, kind: String) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        File(backups, "${image.modelId}-$stamp-$kind.bfp").writeBytes(BackupCodec.encode(image))
    }
    private fun runOperation(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, message = null, progress = null) }
        operation = viewModelScope.launch {
            try { action() }
            catch (e: TimeoutCancellationException) { report("Radio timed out. Reconnect and try again."); disconnectInternal() }
            catch (e: CancellationException) { report("Transfer cancelled. Read the radio again before writing."); throw e }
            catch (e: Exception) { Log.e("RadioProtocol", "Operation failed", e); report(e.message ?: "Operation failed") }
            finally { mutable.update { it.copy(busy = false, progress = null) } }
        }
    }
    override fun onCleared() { scanner.stop(); transport?.close(); super.onCleared() }
}
