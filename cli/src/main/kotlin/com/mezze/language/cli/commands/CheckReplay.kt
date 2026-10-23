package com.mezze.language.cli.commands

import com.mezze.language.store.MezzeStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * A check none of whose inputs changed since the last one that passed: its
 * output again, without checking anything.
 *
 * The inputs are the compiler, the manifest, every source file the check read
 * — by content — and which `.mz` files the project has at all, since a new file
 * can change what an import resolves to. A passing check records them with
 * what it printed, in the project store; the next check compares them first.
 * Anything different and the check runs as always.
 */
internal object CheckReplay {
    private const val KEY = "check-replay:"
    private const val OUTPUT = "\u0000output"

    /** What a passing check printed, if nothing it read has changed since; null otherwise. */
    fun replay(store: MezzeStore, root: File, app: String, quiet: Boolean, manifest: String): String? {
        val record = store.getMetadata(key(app, quiet)) ?: return null
        val split = record.indexOf("\n$OUTPUT\n")
        if (split < 0) return null
        val lines = record.substring(0, split).lines()
        if (lines.size < 3) return null
        if (lines[0] != ProjectStore.binaryIdentity) return null
        if (lines[1] != sha256(manifest)) return null
        if (lines[2] != listing(root)) return null
        for (line in lines.drop(3)) {
            val tab = line.indexOf('\t')
            if (tab < 0) return null
            val file = File(line.substring(tab + 1))
            if (!file.isFile || sha256(Files.readString(file.toPath())) != line.substring(0, tab)) return null
        }
        return record.substring(split + OUTPUT.length + 2)
    }

    /** Record a passing check: [files] are the sources it read, [output] what it printed. */
    fun record(store: MezzeStore, root: File, app: String, quiet: Boolean, manifest: String, files: Collection<File>, output: String) {
        val text = buildString {
            appendLine(ProjectStore.binaryIdentity)
            appendLine(sha256(manifest))
            appendLine(listing(root))
            for (f in files.map { it.absoluteFile }.distinct().sortedBy { it.path }) {
                appendLine("${sha256(Files.readString(f.toPath()))}\t${f.path}")
            }
            append(OUTPUT).append('\n').append(output)
        }
        store.setMetadata(key(app, quiet), text)
    }

    private fun key(app: String, quiet: Boolean) = "$KEY$app:${if (quiet) "quiet" else "text"}"

    /** Every `.mz` file the project has, as one hash. */
    private fun listing(root: File): String {
        val skip = setOf(".mez", ".git", "build", "node_modules", "target")
        val names = root.walkTopDown()
            .onEnter { it == root || it.name !in skip }
            .filter { it.isFile && it.name.endsWith(".mz") }
            .map { it.relativeTo(root).path }
            .sorted()
            .joinToString("\n")
        return sha256(names)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
