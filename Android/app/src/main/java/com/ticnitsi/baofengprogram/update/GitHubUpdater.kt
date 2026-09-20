@file:Suppress("DEPRECATION")
package com.ticnitsi.baofengprogram.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.ticnitsi.baofengprogram.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

class NoPublishedRelease : IOException("No published release is available yet")

class GitHubUpdater(private val context: Context) {
    private val directory get() = File(context.cacheDir, "updates").apply { mkdirs() }

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = open(UpdatePolicy.LATEST_URL, metadata = true)
        try {
            if (connection.responseCode == 404) throw NoPublishedRelease()
            checkStatus(connection)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 1024 * 1024) { "Release metadata is too large" }
                    output.write(buffer, 0, count)
                }
            }
            UpdatePolicy.newerRelease(output.toString("UTF-8"), BuildConfig.VERSION_NAME)
        } finally { connection.disconnect() }
    }

    suspend fun download(release: AppRelease, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val pending = File(directory, "update.apk.part")
        val complete = File(directory, "update.apk")
        pending.delete()
        complete.delete()
        var connection: HttpsURLConnection? = null
        try {
            var url = release.downloadUrl
            for (redirects in 0..5) {
                require(UpdatePolicy.allowedRedirect(url)) { "Untrusted update download address" }
                connection = open(url)
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("Missing download redirect")
                    url = URL(URL(url), location).toString()
                    connection.disconnect()
                    connection = null
                } else break
            }
            val response = connection ?: error("Too many download redirects")
            checkStatus(response)
            val declared = response.contentLengthLong
            require(declared < 0 || declared == release.size) { "Update size does not match the release" }
            var bytes = 0L
            response.inputStream.use { stream ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = stream.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= release.size && bytes <= UpdatePolicy.MAX_APK_BYTES) { "Update download exceeded its expected size" }
                        output.write(buffer, 0, count)
                        progress(bytes)
                    }
                }
            }
            require(bytes == release.size) { "Update download was incomplete" }
            verify(pending, release)
            check(pending.renameTo(complete)) { "Unable to save the verified update" }
            complete
        } finally { connection?.disconnect(); pending.delete() }
    }

    suspend fun installIntent(file: File, release: AppRelease): Intent = withContext(Dispatchers.IO) {
        verify(file, release)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }

    private fun verify(file: File, release: AppRelease) {
        require(file.isFile && file.length() == release.size) { "Update file is missing or incomplete. Download again." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hexDigest() == release.sha256) { "Update checksum failed. The file will not be installed." }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val downloaded = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Downloaded file is not a valid APK")
        fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
        fun PackageInfo.signers(): Set<String> {
            val values = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
            return values.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hexDigest() }.toSet()
        }
        UpdatePolicy.verifyPackage(context.packageName, downloaded.packageName, installed.code(), downloaded.code(),
            downloaded.versionName.orEmpty(), release, installed.signers(), downloaded.signers(),
            downloaded.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    private fun open(url: String, metadata: Boolean = false) = (URL(url).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 15000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "Baofeng-Programmer/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", if (metadata) "application/vnd.github+json" else "application/octet-stream")
        setRequestProperty("Accept-Encoding", "identity")
    }
    private fun checkStatus(connection: HttpsURLConnection) {
        val status = connection.responseCode
        if (status == 403 || status == 429) throw IOException("GitHub is limiting update requests. Try again later.")
        if (status != 200) throw IOException("GitHub update request failed ($status)")
    }
    private fun ByteArray.hexDigest() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
