package com.mezze.language.typechecker

import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parsedast.ParsedAbilityDecl
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedModule
import com.mezze.language.parsedast.ParsedTypeDecl
import com.mezze.language.parsedast.Visibility
import com.mezze.language.parsedast.identity
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * The identities of the standard library's abilities, effects and public types,
 * by name — the compiler's anchors for the declarations it treats specially.
 *
 * These used to be generated at build time by hashing parsed stdlib source. An
 * identity now includes what each name in a declaration resolves to, which only
 * the module loader knows, so they are recorded where they are computed: when a
 * stdlib module is stamped, and when the precompiled stdlib is loaded.
 *
 * The stdlib is fixed for a process, so the registry is too. Both sources must
 * agree; a disagreement means the precompiled stdlib was built by a different
 * compiler than the one running, and it fails rather than mixing the two.
 */
object StdlibIdentities {

    private val abilities = ConcurrentHashMap<String, Hash>()
    private val abilityOps = ConcurrentHashMap<String, List<String>>()
    private val effects = ConcurrentHashMap<String, Hash>()
    private val types = ConcurrentHashMap<String, Hash>()
    /** Where each recorded identity came from, to name both sides of a disagreement. */
    private val sources = ConcurrentHashMap<String, String>()

    /** A stdlib module, just stamped. */
    fun register(module: ParsedModule) {
        val from = "stamping ${module.path}"
        for (decl in module.decls) when (decl) {
            is ParsedAbilityDecl -> {
                record(abilities, "ability", decl.name, decl.identity, from)
                abilityOps.putIfAbsent(decl.name, decl.operations.map { it.name })
            }
            is ParsedEffectDecl -> record(effects, "effect", decl.name, decl.identity, from)
            is ParsedTypeDecl -> if (decl.visibility == Visibility.PUBLIC) record(types, "type", decl.name, decl.identity, from)
            else -> {}
        }
    }

    /** A stdlib module from the precompiled snapshot. */
    fun register(module: TypedModule) {
        val from = "the precompiled stdlib (${module.path})"
        for (a in module.abilityDecls) {
            record(abilities, "ability", a.name, a.hash, from)
            abilityOps.putIfAbsent(a.name, a.operations.map { it.name })
        }
        for (e in module.effectDecls) record(effects, "effect", e.name, e.hash, from)
        for (d in module.decls) if (d is com.mezze.language.typedast.TypedTypeDecl && d.isPublic) {
            record(types, "type", d.name, d.hash, from)
        }
    }

    private fun record(table: ConcurrentHashMap<String, Hash>, kind: String, name: String, id: Hash, from: String) {
        val was = table.putIfAbsent(name, id)
        if (was == null) { sources["$kind $name"] = from; return }
        check(was == id) {
            "two identities for stdlib $kind '$name': ${was.hex.take(12)} from ${sources["$kind $name"]}, " +
                "${id.hex.take(12)} from $from. A precompiled stdlib built by a different compiler " +
                "disagrees with this one (rebuild it); two stamps of the same source disagreeing is a compiler bug"
        }
    }

    fun ability(name: String): Hash? = lookup(abilities, name)
    fun effect(name: String): Hash? = lookup(effects, name)
    fun type(name: String): Hash? = lookup(types, name)
    fun abilityOperations(name: String): List<String> { lookup(abilities, name); return abilityOps[name].orEmpty() }

    /**
     * An anchor that no declaration has yet: its stdlib module has not been
     * stamped in this process. Built in its own domain so nothing can equal it,
     * which is the truth — no declaration in play IS that one.
     */
    fun absent(name: String): Hash = MezzeHashBuilder().append("stdlib declaration not loaded").append(name).buildHash()

    private val loading = ThreadLocal.withInitial { false }
    @Volatile private var complete = false

    /**
     * A miss stamps the whole stdlib, once. Loading needs no typechecking, so this
     * never waits on the precompiled stdlib — which may itself be initialising and
     * asking. When that is loaded it registers the same identities, and [record]
     * holds the two to agreement.
     */
    private fun lookup(table: ConcurrentHashMap<String, Hash>, name: String): Hash? {
        table[name]?.let { return it }
        if (complete || loading.get()) return table[name]
        loading.set(true)
        try {
            stampAll()
            complete = true
        } finally {
            loading.set(false)
        }
        return table[name]
    }

    private fun stampAll() {
        val manifest = StdlibIdentities::class.java.classLoader.getResourceAsStream("std/MANIFEST.txt")
            ?.bufferedReader()?.use { it.readLines() }
            ?: error("Compiler internal error: std/MANIFEST.txt is not on the classpath — rebuild :stdlib")
        val resolver = ModuleResolver()
        for (file in manifest) {
            if (file.isBlank() || file.substringAfterLast('/').startsWith("_")) continue
            val modulePath = file.removeSuffix(".mz").replace('/', ':').replace(":", "::")
            resolver.resolveModule(modulePath)
        }
    }
}
