package com.ticnitsi.baofengprogram.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private fun metadata(tag: String = "v1.1") = JSONObject().put("tag_name", tag).put("body", "New release")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray().put(JSONObject()
            .put("name", "Baofeng-Programmer-${tag.removePrefix("v")}.apk").put("state", "uploaded")
            .put("size", 12345).put("digest", "sha256:" + "ab".repeat(32))
            .put("browser_download_url", "https://github.com/${UpdatePolicy.REPOSITORY}/releases/download/$tag/Baofeng-Programmer-${tag.removePrefix("v")}.apk")))
    private fun release() = UpdatePolicy.newerRelease(metadata().toString(), "1.0")!!

    @Test fun versionsAreComparedNumerically() {
        assertTrue(AppVersion.parse("v1.10") > AppVersion.parse("1.9"))
        assertTrue(AppVersion.parse("1.0.1") > AppVersion.parse("1.0"))
        assertEquals(AppVersion.parse("v1.0"), AppVersion.parse("1.0.0"))
        assertThrows(IllegalArgumentException::class.java) { AppVersion.parse("1.1-beta") }
    }
    @Test fun ignoresOldCurrentDraftAndPrereleaseVersions() {
        assertNull(UpdatePolicy.newerRelease(metadata().toString(), "1.1"))
        assertNull(UpdatePolicy.newerRelease(metadata().toString(), "2.0"))
        assertNull(UpdatePolicy.newerRelease(metadata().put("draft", true).toString(), "1.0"))
        assertNull(UpdatePolicy.newerRelease(metadata().put("prerelease", true).toString(), "1.0"))
    }
    @Test fun selectsTheSingleOfficialReleaseApk() {
        assertEquals("v1.1", release().tag)
        assertEquals("ab".repeat(32), release().sha256)
        val missing = metadata().put("assets", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.newerRelease(missing.toString(), "1.0") }
        val duplicate = metadata()
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.newerRelease(duplicate.toString(), "1.0") }
    }
    @Test fun rejectsInvalidSizesAndDigests() {
        for (size in listOf(0L, -1L, UpdatePolicy.MAX_APK_BYTES + 1)) {
            val json = metadata()
            json.getJSONArray("assets").getJSONObject(0).put("size", size)
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.newerRelease(json.toString(), "1.0") }
        }
        for (digest in listOf("", "md5:" + "ab".repeat(16), "sha256:wrong")) {
            val json = metadata()
            json.getJSONArray("assets").getJSONObject(0).put("digest", digest)
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.newerRelease(json.toString(), "1.0") }
        }
    }
    @Test fun rejectsOtherRepositoriesAndUnsafeDownloadUrls() {
        for (url in listOf("http://github.com/test.apk", "https://evil.example/test.apk",
            "https://github.com/another/repo/releases/download/v1.1/app.apk", "https://user@github.com/test.apk")) {
            val json = metadata()
            json.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url)
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.newerRelease(json.toString(), "1.0") }
        }
    }
    @Test fun redirectsAreRestrictedToHttpsGithubDownloadHosts() {
        assertTrue(UpdatePolicy.allowedRedirect("https://release-assets.githubusercontent.com/asset?signature=test"))
        assertTrue(UpdatePolicy.allowedRedirect("https://objects.githubusercontent.com/asset"))
        assertFalse(UpdatePolicy.allowedRedirect("http://github.com/asset"))
        assertFalse(UpdatePolicy.allowedRedirect("https://github.com.evil.example/asset"))
        assertFalse(UpdatePolicy.allowedRedirect("https://user:pass@github.com/asset"))
        assertFalse(UpdatePolicy.allowedRedirect("https://github.com:8080/asset"))
    }
    @Test fun acceptsOnlyNewerOfficialSameSignaturePackage() {
        verify()
        assertThrows(IllegalArgumentException::class.java) { verify(actualPackage = "another.app") }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 100) }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 99) }
        assertThrows(IllegalArgumentException::class.java) { verify(name = "1.2") }
        assertThrows(IllegalArgumentException::class.java) { verify(debuggable = true) }
    }
    @Test fun rejectsUnexpectedOrMissingSigningCertificates() {
        assertThrows(IllegalArgumentException::class.java) { verify(signers = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf("attacker")) }
        assertThrows(IllegalArgumentException::class.java) { verify(installed = setOf("debug-certificate")) }
        assertThrows(IllegalArgumentException::class.java) { verify(signers = setOf(UpdatePolicy.RELEASE_CERTIFICATE, "extra")) }
    }
    private fun verify(actualPackage: String = "com.ticnitsi.baofengprogram", code: Long = 110, name: String = "1.1",
        installed: Set<String> = setOf(UpdatePolicy.RELEASE_CERTIFICATE), signers: Set<String> = setOf(UpdatePolicy.RELEASE_CERTIFICATE),
        debuggable: Boolean = false) = UpdatePolicy.verifyPackage("com.ticnitsi.baofengprogram", actualPackage, 100, code, name,
            release(), installed, signers, debuggable)
}
