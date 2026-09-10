package com.mezze.language.cli.toolchain

/**
 * Resolves *which* Mezze toolchain version a command should use — the rustup-style
 * precedence, first match wins:
 *
 *   1. explicit `+<ver>`                     `mez +0.1.3 build`
 *   2. `MEZ_VERSION` env                     (CI / scripts)
 *   3. nearest `Mezze.toml` `[toolchain] channel`   (per-project pin)
 *   4. global default                        (`~/.mezze/settings.toml`)
 *   5. latest installed                      (fallback)
 *
 * A **channel** is either a *track* (`"0.1"`, `"nightly"`) or an exact *pin*
 * (`"0.1.3"`). A track resolves to the newest installed version on it.
 *
 * This is pure logic — no filesystem, no process. Callers supply the resolved
 * inputs (env value, project channel, default, installed list); tests drive it
 * directly.
 */
object ToolchainResolver {

    enum class Source { EXPLICIT, ENV, PROJECT, DEFAULT, LATEST }

    /** What the user asked for, and where that ask came from. */
    data class Request(val channel: String?, val source: Source)

    sealed interface Resolution {
        /** A concrete installed version was selected. */
        data class Ok(val version: String, val request: Request) : Resolution
        /** A version/channel was requested but nothing installed matches it. */
        data class NotInstalled(val request: Request, val installed: List<String>) : Resolution
        /** No toolchains are installed at all. */
        data class None(val request: Request) : Resolution
    }

    /** Apply the precedence to pick the effective request (no matching yet). */
    fun request(explicit: String?, env: String?, projectChannel: String?, default: String?): Request = when {
        !explicit.isNullOrBlank() -> Request(explicit.removePrefix("+"), Source.EXPLICIT)
        !env.isNullOrBlank() -> Request(env, Source.ENV)
        !projectChannel.isNullOrBlank() -> Request(projectChannel, Source.PROJECT)
        !default.isNullOrBlank() -> Request(default, Source.DEFAULT)
        else -> Request(null, Source.LATEST)
    }

    /** Resolve a request against the set of installed versions. */
    fun resolve(request: Request, installed: List<String>): Resolution {
        if (installed.isEmpty()) return Resolution.None(request)
        val newestFirst = installed.sortedWith(VERSION_DESC)

        // No specific channel → newest installed.
        if (request.channel.isNullOrBlank()) {
            return Resolution.Ok(newestFirst.first(), Request(newestFirst.first(), Source.LATEST))
        }
        // Exact version pin.
        installed.firstOrNull { it == request.channel }?.let { return Resolution.Ok(it, request) }
        // Track: newest installed version whose id is on that track.
        newestFirst.firstOrNull { onTrack(it, request.channel) }
            ?.let { return Resolution.Ok(it, request) }

        return Resolution.NotInstalled(request, newestFirst)
    }

    /** Convenience: precedence + matching in one call. */
    fun resolve(
        explicit: String?,
        env: String?,
        projectChannel: String?,
        default: String?,
        installed: List<String>,
    ): Resolution = resolve(request(explicit, env, projectChannel, default), installed)

    /** `"0.1"` matches `0.1`, `0.1.3`, `0.1-rc1`; `"nightly"` matches `nightly-2026…`. */
    private fun onTrack(version: String, channel: String): Boolean =
        version == channel || version.startsWith("$channel.") || version.startsWith("$channel-")

    /**
     * Order versions newest-first. Releases (leading digit) rank above pre-release
     * `nightly-*`; releases compare by numeric components; nightlies compare by
     * their trailing timestamp/string lexically.
     */
    private val VERSION_DESC = Comparator<String> { a, b -> compare(b, a) }

    private fun compare(a: String, b: String): Int {
        val aRelease = a.firstOrNull()?.isDigit() == true
        val bRelease = b.firstOrNull()?.isDigit() == true
        if (aRelease != bRelease) return if (aRelease) 1 else -1   // releases > nightlies
        if (aRelease) {
            val an = numericParts(a); val bn = numericParts(b)
            for (i in 0 until maxOf(an.size, bn.size)) {
                val x = an.getOrElse(i) { 0 }; val y = bn.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return a.compareTo(b)
        }
        return a.compareTo(b)   // both nightly-… → lexical (timestamp-ordered)
    }

    private fun numericParts(v: String): List<Int> =
        Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()
}
