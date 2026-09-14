package com.mezze.language.cli.commands

import com.mezze.language.cli.BuildInfo
import com.mezze.language.cli.GraalvmResolver
import com.mezze.language.cli.MezzeHome
import com.mezze.language.cli.MezzeCliError
import com.mezze.language.cli.`package`.ArchiveHandler
import com.mezze.language.cli.`package`.PackageDownloader
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * `mez setup graalvm` — the OPT-IN, EXPLICIT provisioner. mez does not fetch a
 * GraalVM on its own (it detects and degrades); this command is the "just get me
 * one" convenience for a machine with no package manager, and the "register the
 * one I already have" path for Nix / manual installs.
 *
 * It NEVER runs automatically. It is a plain HTTPS download of a relocatable
 * GraalVM CE tarball from the official GitHub releases + an untar into
 * ~/.mezze/graalvm/<line> — no system package manager, no root. Same mechanism
 * as rustup/sdkman. The user's own GraalVM always wins at resolution time
 * (precedence in GraalvmResolver); this managed dir is the last resort.
 *
 * Modes:
 *   mez setup graalvm                 fetch CE <line> for this os/arch → ~/.mezze/graalvm/<line>
 *   mez setup graalvm --path <dir>    register an existing GraalVM (prints the MEZZE_GRAALVM_HOME to set)
 *   mez setup graalvm --url <url>     fetch from an explicit tarball URL (internal mirror / air-gapped)
 *   mez setup graalvm --line <x.y>    override the GraalVM line to fetch (default: BuildInfo.GRAAL_LINE)
 */
class SetupGraalvmCommand {

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    }

    fun execute(args: List<String>) {
        val pathArg = valueOf(args, "--path")
        val urlArg = valueOf(args, "--url")
        val line = valueOf(args, "--line") ?: BuildInfo.GRAAL_LINE

        when {
            pathArg != null -> registerExisting(File(pathArg))
            else -> fetchAndInstall(line, urlArg)
        }
    }

    /** `--path`: point mez at a GraalVM the user already installed (Nix/manual). */
    private fun registerExisting(dir: File) {
        val g = Glyphs
        val java = File(dir, "bin/java")
        if (!java.canExecute()) {
            throw MezzeCliError(
                "no bin/java under ${dir.absolutePath}",
                hint = "pass the GraalVM home directory (the one containing bin/java)",
            )
        }
        // Classify it via the resolver's own probe by temporarily pointing at it
        // would require env mutation; instead just confirm java runs and tell the
        // user the one thing to set. Resolution + version-check happens on the
        // next real command (and `mez doctor`).
        println("${g.OK} found a JDK at ${dir.absolutePath}")
        println()
        println("Point mez at it by exporting (add to your shell rc, or your Nix shell):")
        println("     export MEZZE_GRAALVM_HOME=\"${dir.absolutePath}\"")
        println()
        println("Then verify:  mez doctor")
    }

    /** Default / `--url`: download a relocatable GraalVM CE tarball and untar it. */
    private fun fetchAndInstall(line: String, explicitUrl: String?) {
        val g = Glyphs
        val dest = GraalvmResolver.managedGraalvmDir(line)
        if (File(dest, "bin/java").canExecute()) {
            println("${g.OK} GraalVM (line $line) already installed at ${dest.absolutePath}")
            println("   mez uses it automatically when no other GraalVM is found. See: mez doctor")
            return
        }

        val (os, arch) = detectPlatform()
        if (explicitUrl == null && arch !in setOf("x64", "aarch64")) {
            throw MezzeCliError(
                "no prebuilt GraalVM CE for your platform ($os-$arch)",
                hint = "register your own with `mez setup graalvm --path <dir>`, " +
                    "or run in the interpreter on any JDK 21+ (see `mez doctor`)",
            )
        }
        val tarballUrl = explicitUrl ?: resolveTarballUrl(line, os, arch)
            ?: throw MezzeCliError(
                "couldn't find a GraalVM CE release for line $line ($os-$arch)",
                hint = "check github.com/graalvm/graalvm-ce-builds/releases and pass one with " +
                    "`--url <tarball>`, or register your own with `--path <dir>`",
            )

        println("${g.DOWNLOAD} fetching GraalVM CE (line $line) for $os-$arch")
        println("   $tarballUrl")

        // Verification stays mandatory: fetch the published .sha256 sibling and
        // pass it to the downloader, which refuses bytes that don't match.
        val checksum = fetchChecksum("$tarballUrl.sha256")
            ?: throw MezzeCliError(
                "couldn't fetch the checksum for $tarballUrl.sha256",
                hint = "check your network, or use `mez setup graalvm --path <dir>` with a GraalVM you trust",
            )

        val cacheDir = File(MezzeHome.home, "cache/graalvm").apply { mkdirs() }
        val downloader = PackageDownloader(cacheDir)
        // Lightweight single-file byte progress on a sticky line (TTY only).
        val tty = System.console() != null
        var totalBytes = -1L
        val tarball = try {
            downloader.downloadPackage(
                packageName = "graalvm-ce-$line",
                url = tarballUrl,
                expectedChecksum = checksum,
                onStart = { total -> totalBytes = total },
                onProgress = { received ->
                    if (tty) {
                        val pct = if (totalBytes > 0) " (${received * 100 / totalBytes}%)" else ""
                        System.err.print("\r\u001b[2K   ${humanBytes(received)}$pct")
                    }
                },
            )
        } catch (e: Exception) {
            throw MezzeCliError(
                "download failed: ${e.message}",
                hint = "retry, use `--url <mirror>` for an internal mirror, or `--path <dir>` to register your own",
                cause = e,
            )
        } finally {
            if (tty) System.err.print("\r\u001b[2K")
        }

        println("${g.PACKAGE} extracting to ${dest.absolutePath}")
        val staging = File(dest.parentFile, ".staging-$line").also { if (it.exists()) it.deleteRecursively(); it.mkdirs() }
        try {
            ArchiveHandler().extractTarGz(tarball, staging, showProgress = false)
            // GraalVM tarballs unpack to a single top-level dir; find the one
            // that actually contains bin/java and move it into place.
            val root = staging.walkTopDown().firstOrNull { File(it, "bin/java").isFile }?.let {
                // it is <root>/bin/java's grandparent chain — normalize to the home
                if (it.name == "Home") it else it  // (macOS bundles use Contents/Home; bin/java found there)
            } ?: throw MezzeCliError("extracted archive has no bin/java", hint = "the tarball may be corrupt; retry or use --url")
            dest.parentFile.mkdirs()
            if (dest.exists()) dest.deleteRecursively()
            if (!root.renameTo(dest)) root.copyRecursively(dest, overwrite = true)
        } finally {
            staging.deleteRecursively()
        }

        // macOS: clear the quarantine flag so Gatekeeper doesn't block the JDK.
        if (detectPlatform().first == "macos") {
            runCatching {
                ProcessBuilder("xattr", "-dr", "com.apple.quarantine", dest.absolutePath)
                    .start().waitFor()
            }
        }

        println()
        println("${g.OK} installed GraalVM (line $line) at ${dest.absolutePath}")
        println("   mez uses it automatically when no other GraalVM is found.")
        println("   Verify:  mez doctor")
    }

    /** Fetch the sha256 hex from a `.sha256` sibling (first token on the line). */
    private fun fetchChecksum(url: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI(url)).GET().timeout(Duration.ofSeconds(30)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return null
        resp.body().trim().substringBefore(' ').takeIf { it.length == 64 }
    }.getOrNull()

    /** (os, arch) in GraalVM-release naming: os ∈ {macos,linux,windows}, arch ∈ {x64,aarch64}. */
    private fun detectPlatform(): Pair<String, String> {
        val osName = System.getProperty("os.name").lowercase()
        val os = when {
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("win") -> "windows"
            else -> "linux"
        }
        val archName = System.getProperty("os.arch").lowercase()
        val arch = when {
            archName.contains("aarch64") || archName.contains("arm64") -> "aarch64"
            archName.contains("amd64") || archName.contains("x86_64") || archName == "x64" -> "x64"
            else -> archName
        }
        return os to arch
    }

    /**
     * Resolve the GraalVM CE release tarball URL for a line + platform by asking
     * the GitHub releases API for the newest `jdk-<line>.*` tag (the CE
     * distribution patch stream lags the Maven artifact version, so we must
     * discover what's actually published rather than assume BuildInfo.GRAAL_VERSION
     * has a matching build). GraalVM CE 25.x ships one self-contained tarball per
     * platform (JDK + native-image + libgraal). Returns null if no matching
     * release is found (→ caller advises --url / --path). A specific patch can
     * always be forced with --url.
     */
    private fun resolveTarballUrl(line: String, os: String, arch: String): String? {
        val ext = if (os == "windows") "zip" else "tar.gz"
        val version = latestCePatch(line) ?: return null
        return "https://github.com/graalvm/graalvm-ce-builds/releases/download/" +
            "jdk-$version/graalvm-community-jdk-${version}_${os}-${arch}_bin.$ext"
    }

    /**
     * Newest published GraalVM CE patch version for a line (e.g. "25.0" → "25.0.2"),
     * from the releases API `jdk-<line>.<patch>` tags. Null on network failure or
     * no matching tag. Note: modern GraalVM tags 25.1+ use `graal-<v>` not
     * `jdk-<v>`; those lines dropped the portable jargraal and are out of scope
     * for mez's bundled line anyway — we resolve the `jdk-` stream mez targets.
     */
    private fun latestCePatch(line: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/graalvm/graalvm-ce-builds/releases?per_page=100"))
            .header("Accept", "application/vnd.github+json")
            .GET().timeout(Duration.ofSeconds(30)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return null
        // Cheap parse: pull every "tag_name": "jdk-<line>.<patch>" and pick the max patch.
        val re = Regex("\"tag_name\"\\s*:\\s*\"jdk-${Regex.escape(line)}\\.(\\d+)\"")
        re.findAll(resp.body())
            .map { it.groupValues[1].toInt() }
            .maxOrNull()
            ?.let { "$line.$it" }
    }.getOrNull()

    private fun valueOf(args: List<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
