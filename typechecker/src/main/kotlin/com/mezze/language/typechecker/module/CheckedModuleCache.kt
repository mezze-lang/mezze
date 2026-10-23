package com.mezze.language.typechecker.module

import com.mezze.language.ast.ModulePathConventions
import com.mezze.language.ast.TypeSharing
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.PhaseTiming
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * Checked project modules, kept across runs: a module unchanged since it was last
 * checked is read back, not checked again.
 *
 * Keyed by the module's identity and source hash. The identity covers everything
 * its declarations resolve to — a changed dependency changes it — and the source
 * hash covers what the identity leaves out (comments, layout, the spans of the
 * typed tree). A store is wiped when a different compiler opens it, so a result
 * from another compiler is never read.
 *
 * Kept as two parts: the INTERFACE (exports and their types — what importing the
 * module needs), and the BODY (its typed and parsed modules), read only if
 * something asks for it. Decoding every body was most of a cached run.
 *
 * Kept in [ModuleResolver.cacheStore]; off unless a command sets it, as `mez
 * check` does. The stdlib is never kept here — it is precompiled into the binary.
 */
@OptIn(ExperimentalSerializationApi::class)
object CheckedModuleCache {
    @Serializable
    private class Body(val typed: TypedModule, val parsed: ParsedModule?)

    private val cbor = Cbor { ignoreUnknownKeys = true }

    /** [module] as last checked, if it is unchanged since. */
    fun load(module: LoadedModule): TypedModuleResult? {
        val (store, key) = entryFor(module) ?: return null
        val cached = PhaseTiming.phase(module.modulePath, "cache-interface") {
            store.getCheckedInterface(module.modulePath, key)?.let { read(it, CachedModule.serializer()) }
        } ?: return null
        if (cached.identity != module.identity || cached.modulePath != module.modulePath) return null
        return cached.toResult(lazy {
            // The interface was there, so the body is: one that cannot be read now
            // is a broken store, said so rather than taken as an empty module.
            val body = PhaseTiming.phase(module.modulePath, "cache-body") {
                store.getCheckedBody(module.modulePath, key)?.let { read(it, Body.serializer()) }
            } ?: error("the checked body of ${module.modulePath} is missing or unreadable; delete .mez/store")
            body.typed to body.parsed
        })
    }

    /** Keep [result], checked from [module], for later runs. */
    fun save(module: LoadedModule, result: TypedModuleResult) {
        val (store, key) = entryFor(module) ?: return
        val typed = result.typedModule ?: return
        val cached = CachedModule.of(result) ?: return
        store.putCheckedModule(
            module.modulePath, key,
            write(CachedModule.serializer(), cached.copy(parsedModule = null)),
            write(Body.serializer(), Body(typed, result.parsedModule)),
        )
    }

    private fun <T> read(bytes: ByteArray, serializer: KSerializer<T>): T? = try {
        TypeSharing.read { cbor.decodeFromByteArray(serializer, bytes) }
    } catch (e: Exception) {
        // A schema this build cannot read: it is a cache — the module is checked instead.
        null
    }

    private fun <T> write(serializer: KSerializer<T>, value: T): ByteArray =
        TypeSharing.write { cbor.encodeToByteArray(serializer, value) }

    private fun entryFor(module: LoadedModule): Pair<MezzeStore, String>? {
        if (ModulePathConventions.isStdlib(module.modulePath)) return null
        val identity = module.identity ?: return null
        val source = module.sourceHash ?: return null
        val store = ModuleResolver.cacheStore?.value ?: return null
        return store to "${identity.hex}:${source.hex}"
    }
}
