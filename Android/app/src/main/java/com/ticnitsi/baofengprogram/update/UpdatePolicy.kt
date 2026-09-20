package com.ticnitsi.baofengprogram.update

import org.json.JSONObject
import java.net.URI

data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    companion object {
        fun parse(value: String): AppVersion {
            require(value.matches(Regex("v?[0-9]+(\\.[0-9]+){0,2}"))) { "Unsupported release version: $value" }
            val parts = value.removePrefix("v").split('.').map { it.toInt() }
            return AppVersion(parts[0], parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
        }
    }
}

data class AppRelease(val tag: String, val version: AppVersion, val notes: String, val downloadUrl: String,
    val size: Long, val sha256: String)

object UpdatePolicy {
    const val REPOSITORY = "dylanmaniatakes/Baofeng-Program"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    const val RELEASE_CERTIFICATE = "632cf3bdc318913a8a1a4b4cef0390d68f84c2f6abdfff78deee8f637b8bbb66"

    fun newerRelease(json: String, installedVersion: String): AppRelease? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        val version = AppVersion.parse(tag)
        if (version <= AppVersion.parse(installedVersion)) return null
        val expectedName = "Baofeng-Programmer-${tag.removePrefix("v")}.apk"
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == expectedName && it.optString("state") == "uploaded" }
        require(matches.size == 1) { "This release does not have a compatible APK" }
        val asset = matches.single()
        val uri = URI(asset.getString("browser_download_url"))
        require(uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
            uri.rawPath == "/$REPOSITORY/releases/download/$tag/$expectedName" && uri.query == null && uri.fragment == null) {
            "The release download is not from this project's GitHub repository"
        }
        val size = asset.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "Invalid update size" }
        val digest = asset.optString("digest")
        require(digest.matches(Regex("sha256:[0-9a-fA-F]{64}"))) { "The release has no valid SHA-256 checksum" }
        return AppRelease(tag, version, release.optString("body").take(4000), uri.toString(), size,
            digest.substringAfter(':').lowercase())
    }

    fun allowedRedirect(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            uri.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)

    fun verifyPackage(expectedPackage: String, actualPackage: String, installedCode: Long, downloadedCode: Long,
        downloadedName: String, release: AppRelease, installedSigners: Set<String>, downloadedSigners: Set<String>, debuggable: Boolean) {
        require(actualPackage == expectedPackage) { "Downloaded APK is for another application" }
        require(downloadedCode > installedCode && AppVersion.parse(downloadedName) == release.version) { "Downloaded APK is not the expected newer version" }
        require(!debuggable) { "Development APKs cannot be installed as release updates" }
        require(downloadedSigners == setOf(RELEASE_CERTIFICATE)) { "APK signing certificate does not match this project's release key" }
        require(installedSigners == downloadedSigners) {
            "This installation uses a different signing key. Export backups before replacing a development build with the official release."
        }
    }
}
