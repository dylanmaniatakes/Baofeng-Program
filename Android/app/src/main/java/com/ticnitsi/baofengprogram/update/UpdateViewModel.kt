package com.ticnitsi.baofengprogram.update

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ticnitsi.baofengprogram.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UpdateState(val busy: Boolean = false, val downloading: Boolean = false, val bytes: Long = 0,
    val release: AppRelease? = null, val file: File? = null, val message: String = "", val error: Boolean = false)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val updater = GitHubUpdater(application)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    fun check() {
        if (state.value.busy) return
        mutable.value = UpdateState(busy = true, message = "Checking GitHub releases...")
        operation = viewModelScope.launch {
            try {
                val release = updater.check()
                mutable.value = UpdateState(release = release,
                    message = if (release == null) "Version ${BuildConfig.VERSION_NAME} is up to date." else "${release.tag} is available.")
            } catch (e: CancellationException) { throw e }
            catch (e: NoPublishedRelease) { mutable.value = UpdateState(message = e.message.orEmpty()) }
            catch (e: Exception) { report(e.message ?: "Unable to check for updates") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, downloading = true, bytes = 0, error = false, message = "Downloading ${release.tag}...") }
        operation = viewModelScope.launch {
            try {
                val file = updater.download(release) { bytes -> mutable.update { it.copy(bytes = bytes) } }
                mutable.update { it.copy(file = file, message = "Download verified. Ready to install.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e.message ?: "Update download failed") }
            finally { mutable.update { it.copy(busy = false, downloading = false) } }
        }
    }

    fun install(launch: (Intent) -> Unit) {
        val release = state.value.release ?: return
        val file = state.value.file ?: return
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, error = false, message = "Verifying update...") }
        operation = viewModelScope.launch {
            try {
                val intent = updater.installIntent(file, release)
                launch(intent)
                mutable.update { it.copy(message = "Confirm installation in Android.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(file = null) }
                report(e.message ?: "Unable to open Android installer")
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun report(message: String) { mutable.update { it.copy(message = message, error = true) } }
    fun cancel() { operation?.cancel() }
}
