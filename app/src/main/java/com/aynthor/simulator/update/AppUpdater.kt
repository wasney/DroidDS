package com.aynthor.simulator.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.aynthor.simulator.prefs.SimulatorPrefs
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Checks GitHub Releases (wasney/DroidDS) for newer DroiDS builds (both prereleases and
 * stable releases), downloads the APK on its own dedicated thread (never blocking service
 * or focus calls), verifies HTTPS GitHub host, Content-Length, and matching signing
 * certificate, then hands off to Android's native package installer via FileProvider.
 */
class AppUpdater(
    private val activity: Activity,
    private val prefs: SimulatorPrefs
) {
    companion object {
        const val GITHUB_REPO_URL = "https://github.com/wasney/DroidDS"
        const val GITHUB_RELEASES_URL = "https://github.com/wasney/DroidDS/releases"
        const val SHIZUKU_URL = "https://shizuku.rikka.app/"
        private const val RELEASES_API_URL = "https://api.github.com/repos/wasney/DroidDS/releases?per_page=10"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
    }

    data class ReleaseInfo(
        val tagName: String,
        val displayName: String,
        val notes: String,
        val htmlUrl: String,
        val isPrerelease: Boolean,
        val apkDownloadUrl: String?,
        val apkFileName: String?
    )

    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DroiDS-Updater").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isChecking = AtomicBoolean(false)

    fun shutdown() {
        updateExecutor.shutdownNow()
    }

    fun getCurrentVersionName(): String {
        return try {
            val pm = activity.packageManager
            val info = pm.getPackageInfo(activity.packageName, 0)
            info.versionName ?: "2.9.6-pre"
        } catch (_: Throwable) {
            "2.9.6-pre"
        }
    }

    /**
     * Checks GitHub Releases for a newer version.
     * @param manual true when triggered by the user from Settings -> About (always shows status);
     *               false for silent startup auto-check (only prompts when an unskipped update exists).
     */
    fun checkForUpdates(manual: Boolean) {
        if (!isChecking.compareAndSet(false, true)) {
            if (manual) {
                Toast.makeText(activity, "Already checking for updates…", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (manual) {
            Toast.makeText(activity, "Checking GitHub Releases…", Toast.LENGTH_SHORT).show()
        }

        updateExecutor.execute {
            try {
                val latest = fetchLatestRelease()
                val current = getCurrentVersionName()

                mainHandler.post {
                    isChecking.set(false)
                    if (activity.isFinishing || activity.isDestroyed) return@post

                    if (latest == null) {
                        if (manual) {
                            Toast.makeText(activity, "No releases found on GitHub.", Toast.LENGTH_SHORT).show()
                        }
                        return@post
                    }

                    val newer = isRemoteNewer(current, latest.tagName)
                    if (newer) {
                        if (!manual && prefs.getSkippedUpdateTag() == latest.tagName) {
                            return@post
                        }
                        showUpdateAvailableDialog(current, latest, manual)
                    } else if (manual) {
                        AlertDialog.Builder(activity)
                            .setTitle("Up to Date")
                            .setMessage("You're running the latest version of DroiDS (v$current).")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Open Releases") { _, _ ->
                                openUrl(GITHUB_RELEASES_URL)
                            }
                            .show()
                    }
                }
            } catch (e: Throwable) {
                mainHandler.post {
                    isChecking.set(false)
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    if (manual) {
                        AlertDialog.Builder(activity)
                            .setTitle("Update Check Failed")
                            .setMessage("Couldn't reach GitHub Releases (${e.message ?: "network error"}). Make sure you're online and try again.")
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Open GitHub") { _, _ ->
                                openUrl(GITHUB_RELEASES_URL)
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DroiDS-Android-Updater")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("draft", false)) continue
                val tag = obj.optString("tag_name", "").trim()
                if (tag.isEmpty()) continue
                val name = obj.optString("name", tag).ifBlank { tag }
                val notes = obj.optString("body", "").trim()
                val htmlUrl = obj.optString("html_url", GITHUB_RELEASES_URL).ifBlank { GITHUB_RELEASES_URL }
                val isPre = obj.optBoolean("prerelease", false)

                var apkUrl: String? = null
                var apkName: String? = null
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        val aName = asset.optString("name", "")
                        if (aName.endsWith(".apk", ignoreCase = true)) {
                            apkName = aName
                            apkUrl = asset.optString("browser_download_url", "").ifBlank { null }
                            break
                        }
                    }
                }

                return ReleaseInfo(
                    tagName = tag,
                    displayName = name,
                    notes = notes,
                    htmlUrl = htmlUrl,
                    isPrerelease = isPre,
                    apkDownloadUrl = apkUrl,
                    apkFileName = apkName
                )
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpdateAvailableDialog(current: String, release: ReleaseInfo, manual: Boolean) {
        val notesPreview = if (release.notes.isNotBlank()) {
            val trimmed = release.notes.take(600)
            val suffix = if (release.notes.length > 600) "…" else ""
            "\n\nWhat's new:\n$trimmed$suffix"
        } else {
            ""
        }
        val badge = if (release.isPrerelease) " (Prerelease)" else ""
        val message = "A newer version of DroiDS is available!\n\n" +
            "Installed: v$current\n" +
            "Available: ${release.displayName}$badge" +
            notesPreview

        val builder = AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setMessage(message)

        if (!release.apkDownloadUrl.isNullOrBlank()) {
            builder.setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallApk(release)
            }
        } else {
            builder.setPositiveButton("Open GitHub") { _, _ ->
                openUrl(release.htmlUrl)
            }
        }

        if (!manual) {
            builder.setNeutralButton("Skip Version") { _, _ ->
                prefs.setSkippedUpdateTag(release.tagName)
                Toast.makeText(activity, "Skipped ${release.tagName}", Toast.LENGTH_SHORT).show()
            }
        } else {
            builder.setNeutralButton("View on GitHub") { _, _ ->
                openUrl(release.htmlUrl)
            }
        }

        builder.setNegativeButton("Later", null)
        builder.show()
    }

    private fun isTrustedGitHubDownloadUrl(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        val host = url.host.lowercase(Locale.US)
        return host == "github.com" ||
            host == "api.github.com" ||
            host.endsWith(".github.com") ||
            host.endsWith(".githubusercontent.com")
    }

    private fun openVerifiedDownloadConnection(initialUrl: String): HttpURLConnection {
        var currentUrl = URL(initialUrl)
        for (hop in 0..MAX_REDIRECTS) {
            if (!isTrustedGitHubDownloadUrl(currentUrl)) {
                throw SecurityException("Refusing untrusted download host: ${currentUrl.protocol}://${currentUrl.host}")
            }
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = 30_000
                setRequestProperty("User-Agent", "DroiDS-Android-Updater")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect missing Location header (HTTP $code)")
                }
                currentUrl = URL(currentUrl, location)
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("HTTP $code")
            }
            return conn
        }
        throw IllegalStateException("Too many redirects while downloading APK")
    }

    private fun extractSigningDigests(info: PackageInfo?): Set<String> {
        val signingInfo = info?.signingInfo ?: return emptySet()
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { b -> "%02x".format(b) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun verifyApkSignatureMatchesInstalled(apkFile: File) {
        val pm = activity.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val installedInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(activity.packageName, flags)
        }
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: throw SecurityException("Downloaded file is not a valid Android APK.")

        if (archiveInfo.packageName != activity.packageName) {
            throw SecurityException("APK package ID (${archiveInfo.packageName}) does not match ${activity.packageName}.")
        }
        val installedDigests = extractSigningDigests(installedInfo)
        val archiveDigests = extractSigningDigests(archiveInfo)
        if (installedDigests.isEmpty() || archiveDigests.isEmpty() || installedDigests != archiveDigests) {
            throw SecurityException("APK signing certificate does not match the installed DroiDS app.")
        }
    }

    private fun downloadAndInstallApk(release: ReleaseInfo) {
        val apkUrl = release.apkDownloadUrl ?: run {
            openUrl(release.htmlUrl)
            return
        }

        val cancelled = AtomicBoolean(false)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()

        val statusText = TextView(activity).apply {
            text = "Connecting to GitHub…"
            textSize = 13.5f
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(8))
            addView(statusText)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading ${release.apkFileName ?: "DroiDS Update"}")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                cancelled.set(true)
            }
            .create()
        dialog.show()

        updateExecutor.execute {
            var conn: HttpURLConnection? = null
            var outFile: File? = null
            try {
                val updatesDir = File(activity.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { it.delete() }
                val targetFile = File(updatesDir, "DroiDS-update.apk")
                outFile = targetFile

                conn = openVerifiedDownloadConnection(apkUrl)

                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                val buffer = ByteArray(16 * 1024)
                var lastUiUpdate = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            if (cancelled.get()) {
                                targetFile.delete()
                                return@execute
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 120L) {
                                lastUiUpdate = now
                                val dlMb = downloaded / (1024f * 1024f)
                                mainHandler.post {
                                    if (totalBytes > 0) {
                                        val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                        val totalMb = totalBytes / (1024f * 1024f)
                                        progressBar.isIndeterminate = false
                                        progressBar.progress = pct
                                        statusText.text = String.format("Downloading… %d%% (%.1f / %.1f MB)", pct, dlMb, totalMb)
                                    } else {
                                        statusText.text = String.format("Downloading… %.1f MB", dlMb)
                                    }
                                }
                            }
                        }
                    }
                }

                if (downloaded <= 0L) {
                    targetFile.delete()
                    throw IllegalStateException("Downloaded APK is empty.")
                }
                if (totalBytes > 0L && downloaded != totalBytes) {
                    targetFile.delete()
                    throw IllegalStateException("Incomplete download ($downloaded / $totalBytes bytes).")
                }

                mainHandler.post {
                    statusText.text = "Verifying APK signature…"
                    progressBar.isIndeterminate = true
                }
                verifyApkSignatureMatchesInstalled(targetFile)

                mainHandler.post {
                    if (dialog.isShowing) dialog.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed && !cancelled.get()) {
                        triggerApkInstall(targetFile)
                    }
                }
            } catch (e: Throwable) {
                outFile?.delete()
                mainHandler.post {
                    if (dialog.isShowing) dialog.dismiss()
                    if (!activity.isFinishing && !activity.isDestroyed && !cancelled.get()) {
                        AlertDialog.Builder(activity)
                            .setTitle("Download Failed")
                            .setMessage("Couldn't install the APK (${e.message ?: "error"}). You can still grab it directly from GitHub Releases.")
                            .setPositiveButton("Open GitHub") { _, _ -> openUrl(release.htmlUrl) }
                            .setNegativeButton("Close", null)
                            .show()
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun triggerApkInstall(apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow App Installs")
                .setMessage("To install updates directly, enable \"Allow from this source\" for DroiDS, then return and tap Install.")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                        activity.startActivity(intent)
                    } catch (_: Throwable) {
                        Toast.makeText(activity, "Unable to open install permission settings", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("Install Now") { _, _ ->
                    launchPackageInstaller(apkFile)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        launchPackageInstaller(apkFile)
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Throwable) {
            Toast.makeText(activity, "Install launch failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(activity, "No browser app found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Semantic version comparator that ignores GitHub Actions `-b<run_number>` suffixes so
     * an installed `2.9.0-pre` never false-triggers on its own `v2.9.0-pre-b60` tag, while
     * correctly recognizing `2.9.0-pre2`, `2.9.0`, `2.9.1`, or `3.0.0` as newer.
     */
    internal fun isRemoteNewer(currentRaw: String, remoteRaw: String): Boolean {
        val cur = parseVersion(currentRaw)
        val rem = parseVersion(remoteRaw)
        if (rem.major != cur.major) return rem.major > cur.major
        if (rem.minor != cur.minor) return rem.minor > cur.minor
        if (rem.patch != cur.patch) return rem.patch > cur.patch
        if (rem.stageRank != cur.stageRank) return rem.stageRank > cur.stageRank
        return rem.stageNum > cur.stageNum
    }

    private data class ParsedVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val stageRank: Int, // 0=alpha, 1=beta, 2=pre, 3=rc, 4=stable
        val stageNum: Int
    )

    private fun parseVersion(raw: String): ParsedVersion {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .replace(Regex("-b\\d+$", RegexOption.IGNORE_CASE), "")

        val parts = cleaned.split("-", limit = 2)
        val nums = parts[0].split(".")
        val major = nums.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val minor = nums.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val patch = nums.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        val qual = parts.getOrNull(1)?.lowercase() ?: ""
        val stageRank = when {
            qual.isEmpty() -> 4
            qual.startsWith("rc") -> 3
            qual.startsWith("pre") -> 2
            qual.startsWith("beta") -> 1
            qual.startsWith("alpha") -> 0
            else -> 2
        }
        val stageNum = if (qual.isEmpty()) {
            0
        } else {
            Regex("\\d+").find(qual)?.value?.toIntOrNull() ?: 1
        }
        return ParsedVersion(major, minor, patch, stageRank, stageNum)
    }
}
