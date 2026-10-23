package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePathConventions
import com.mezze.language.ast.TypeSharing
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.typechecker.PhaseTiming
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Checked project modules, kept across runs: a module unchanged since it was last
 * checked is read back, not checked again.
 *
 * Keyed by the module's path, identity and source hash. The identity covers
 * everything its declarations resolve to — a changed dependency changes it — and
 * the source hash covers what the identity leaves out (comments, layout, the
 * spans of the typed tree). The directory is per compiler build, so a result
 * from another compiler is never read.
 *
 * Kept as two files: the INTERFACE (exports and their types — what importing the
 * module needs), and the BODY (its typed and parsed modules), read only if
 * something asks for it. Decoding every body was most of a cached run.
 *
 * Off (null) unless a command sets [directory]: `mez check` does. The stdlib is
 * never kept here — it is precompiled into the binary.
 */
@OptIn(ExperimentalSerializationApi::class)
object CheckedModuleCache {
    @Volatile
    var directory: Path? = null

    @Serializable
    private class Body(val typed: TypedModule, val parsed: ParsedModule?)

    private val cbor = Cbor { ignoreUnknownKeys = true }

    /** [module] as last checked, if it is unchanged since. */
    fun load(module: LoadedModule): TypedModuleResult? {
        val base = fileFor(module) ?: return null
        val interfaceFile = base.resolveSibling(base.fileName.toString() + ".i")
        val bodyFile = base.resolveSibling(base.fileName.toString() + ".b")
        if (!Files.isRegularFile(interfaceFile) || !Files.isRegularFile(bodyFile)) return null
        val cached = PhaseTiming.phase(module.modulePath, "cache-interface") { read(interfaceFile, CachedModule.serializer()) } ?: return null
        if (cached.identity != module.identity || cached.modulePath != module.modulePath) return null
        return cached.toResult(lazy {
            // The interface was there, so the body is: a body that cannot be read
            // now is a broken cache, said so rather than taken as an empty module.
            val body = PhaseTiming.phase(module.modulePath, "cache-body") { read(bodyFile, Body.serializer()) }
                ?: error("the checked-module cache entry ${bodyFile.fileName} is unreadable; delete .mez/checked")
            body.typed to body.parsed
        })
    }

    /** Keep [result], checked from [module], for later runs. */
    fun save(module: LoadedModule, result: TypedModuleResult) {
        val base = fileFor(module) ?: return
        val typed = result.typedModule ?: return
        val cached = CachedModule.of(result) ?: return
        Files.createDirectories(base.parent)
        // Body first: an interface is only ever there with its body.
        write(base.resolveSibling(base.fileName.toString() + ".b"), Body.serializer(), Body(typed, result.parsedModule))
        write(base.resolveSibling(base.fileName.toString() + ".i"), CachedModule.serializer(), cached.copy(parsedModule = null))
    }

    private fun <T> read(file: Path, serializer: KSerializer<T>): T? = try {
        val bytes = java.util.zip.InflaterInputStream(Files.newInputStream(file)).use { it.readBytes() }
        TypeSharing.read { cbor.decodeFromByteArray(serializer, bytes) }
    } catch (e: Exception) {
        // Unreadable (a partial write survived a crash, or a schema this build
        // cannot read): it is a cache — the module is checked instead.
        null
    }

    /** Compressed: CBOR repeats every field name and every location's file. */
    private fun <T> write(file: Path, serializer: KSerializer<T>, value: T) {
        val bytes = TypeSharing.write { cbor.encodeToByteArray(serializer, value) }
        val partial = Files.createTempFile(file.parent, "module", ".partial")
        java.util.zip.DeflaterOutputStream(Files.newOutputStream(partial), java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)).use { it.write(bytes) }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fileFor(module: LoadedModule): Path? {
        val dir = directory ?: return null
        if (ModulePathConventions.isStdlib(module.modulePath)) return null
        val identity = module.identity ?: return null
        val source = module.sourceHash ?: return null
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${module.modulePath}\u0000${identity.hex}\u0000${source.hex}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return dir.resolve(key.take(2)).resolve(key)
    }
}
