package com.mezze.language.typechecker.module

import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.Hash

/**
 * The names a module sees from outside itself, for [DeclIdentity].
 *
 * Built by the same rules the import resolver applies, so a name's identity here
 * is the declaration the typechecker will bind it to:
 *  - a selective import names items, under their alias if given;
 *  - a module import names a qualifier (`use std::int` → `int::add`);
 *  - a `pub use` re-exports, so what an import sees includes it;
 *  - every performer, ability, effect and impl of a directly imported module is
 *    visible — the import resolver brings them all, not just the named ones;
 *  - a non-stdlib module also sees the prelude's abilities and impls.
 */
internal object ModuleIdentity {

    /**
     * A module a scope can see: one already stamped ([member] null), or a member
     * of the [IdentityGroup] being stamped, whose declarations have no identity yet.
     */
    class Visible(val module: ParsedModule, val member: Int?)

    /** The module at a path, or null when it cannot be loaded. */
    fun interface Loader {
        fun load(segments: List<String>): Visible?
    }

    /** What a module offers one that imports it. */
    class Surface(
        /** Public names, own and re-exported. */
        val names: Map<Pair<RefKind, String>, Referent>,
        /** Its own abilities, effects and performers, public or not — imports bring all of them. */
        val brought: Map<Pair<RefKind, String>, Referent>,
        /** What its own abilities, effects and impls answer, by member or operation name. */
        val provides: Map<String, Set<Referent>>,
    )

    private fun referent(v: Visible, index: Int, group: IdentityGroup?): Referent {
        val member = v.member ?: return Referent.Known(identityOf(v.module.decls[index])
            ?: error("internal: ${v.module.path} is visible as stamped but declaration $index has no identity"))
        return Referent.Pending(
            (group ?: error("internal: a group member is visible without its group")).node(member, index)
        )
    }

    fun surface(v: Visible, loader: Loader, group: IdentityGroup?): Surface = surface(v, loader, group, mutableSetOf())

    private fun surface(v: Visible, loader: Loader, group: IdentityGroup?, visiting: MutableSet<String>): Surface {
        val names = LinkedHashMap<Pair<RefKind, String>, Referent>()
        val brought = LinkedHashMap<Pair<RefKind, String>, Referent>()
        val provides = HashMap<String, MutableSet<Referent>>()
        fun provide(member: String, r: Referent) { provides.getOrPut(member) { LinkedHashSet() }.add(r) }

        v.module.decls.forEachIndexed { i, d ->
            when (d) {
                is ParsedLetDecl ->
                    if (d.visibility == Visibility.PUBLIC && d.isSimpleBinding) names[RefKind.VALUE to d.variableName] = referent(v, i, group)
                is ParsedTypeDecl -> if (d.visibility == Visibility.PUBLIC) {
                    val r = referent(v, i, group)
                    names[RefKind.TYPE to d.name] = r
                    (d.definition as? ParsedVariantDef)?.constructors?.forEach { names[RefKind.CONSTRUCTOR to it.name] = r }
                }
                is ParsedAbilityDecl -> {
                    val r = referent(v, i, group)
                    names[RefKind.ABILITY to d.name] = r
                    brought[RefKind.ABILITY to d.name] = r
                    d.operations.forEach { provide(it.name, r) }
                }
                is ParsedEffectDecl -> {
                    val r = referent(v, i, group)
                    names[RefKind.EFFECT to d.name] = r
                    brought[RefKind.EFFECT to d.name] = r
                    d.operations.forEach { provide(it.name, r) }
                }
                is ParsedPerformerDecl -> {
                    // Performers are exported as values too: a handler can be passed around.
                    val r = referent(v, i, group)
                    names[RefKind.PERFORMER to d.performerName] = r
                    names.putIfAbsent(RefKind.VALUE to d.performerName, r)
                    brought[RefKind.PERFORMER to d.performerName] = r
                }
                is ParsedImplAbilityDecl -> { val r = referent(v, i, group); d.operations.forEach { provide(it.name, r) } }
                is ParsedUseDecl, is ParsedPubUseDecl, is ParsedTopLevelExpr -> {}
            }
        }

        // Re-exports. The module's own names win a clash, as they do for importers.
        if (visiting.add(v.module.path)) {
            for (pub in v.module.decls.filterIsInstance<ParsedPubUseDecl>()) {
                val source = loader.load(segments(pub.path)) ?: continue
                val from = surface(source, loader, group, visiting).names
                when (val p = pub.path) {
                    is ParsedUsePath.Selective -> for (item in p.items) {
                        for ((key, r) in from) if (key.second == item.name) {
                            names.putIfAbsent(key.first to (item.alias ?: item.name), r)
                        }
                    }
                    is ParsedUsePath.Module -> for ((key, r) in from) names.putIfAbsent(key, r)
                }
            }
        }
        return Surface(names, brought, provides)
    }

    /**
     * The scope [module] sees. [prelude] is empty for a stdlib module, which never
     * gets one. [group] is the group being stamped, when [module] is a member.
     */
    fun scope(module: ParsedModule, loader: Loader, prelude: List<Visible>, group: IdentityGroup?): IdentityScope {
        val unqualified = HashMap<Pair<RefKind, String>, Referent>()
        val qualified = HashMap<String, Map<Pair<RefKind, String>, Referent>>()
        val providers = HashMap<String, MutableSet<Referent>>()
        fun provideAll(s: Surface) {
            for ((member, rs) in s.provides) providers.getOrPut(member) { LinkedHashSet() }.addAll(rs)
        }

        val imported = ArrayList<Surface>()
        for (d in module.decls) {
            val (path, alias) = when (d) {
                is ParsedUseDecl -> d.path to d.alias
                is ParsedPubUseDecl -> d.path to d.alias
                else -> continue
            }
            val target = loader.load(segments(path)) ?: continue
            val s = surface(target, loader, group)
            imported.add(s)
            provideAll(s)
            when (path) {
                is ParsedUsePath.Module -> qualified[alias ?: path.path.segments.last()] = s.names + s.brought
                // Named items first: an explicit import is what the name means.
                is ParsedUsePath.Selective -> for (item in path.items) {
                    for ((key, r) in s.names) if (key.second == item.name) {
                        unqualified[key.first to (item.alias ?: item.name)] = r
                    }
                }
            }
        }
        // Then everything an import brings without naming it.
        for (s in imported) for ((key, r) in s.brought) unqualified.putIfAbsent(key, r)
        for (p in prelude) {
            val s = surface(p, loader, group)
            provideAll(s)
            for ((key, r) in s.brought) if (key.first == RefKind.ABILITY) unqualified.putIfAbsent(key, r)
        }

        return object : IdentityScope {
            override fun resolve(kind: RefKind, qualifier: List<String>?, name: String): Referent? =
                if (qualifier == null) unqualified[kind to name]
                else qualified[qualifier.joinToString("::")]?.get(kind to name)

            override fun providers(name: String): Set<Referent> = providers[name].orEmpty()
        }
    }

    /** One hash for everything a module declares — changes when any identity in it does. */
    fun fingerprint(module: ParsedModule): Hash = MezzeHashBuilder().apply {
        val ids = module.decls.mapNotNull { identityOf(it)?.hex }.sorted()
        appendInt(ids.size)
        ids.forEach { append(it) }
    }.buildHash()

    /** Whether every declaration carries an identity — true only of a module the resolver stamped. */
    fun isStamped(module: ParsedModule): Boolean =
        module.decls.all { it is ParsedUseDecl || it is ParsedPubUseDecl || identityOf(it) != null }

    private fun identityOf(d: ParsedDecl): Hash? = when (d) {
        is ParsedLetDecl -> d.contentHash
        is ParsedTypeDecl -> d.contentHash
        is ParsedAbilityDecl -> d.contentHash
        is ParsedEffectDecl -> d.contentHash
        is ParsedPerformerDecl -> d.contentHash
        is ParsedImplAbilityDecl -> d.contentHash
        is ParsedTopLevelExpr -> d.contentHash
        is ParsedUseDecl, is ParsedPubUseDecl -> null
    }

    /** The module paths a module's identities depend on: its imports, then the prelude. */
    fun dependencies(module: ParsedModule): List<List<String>> =
        module.decls.mapNotNull { d ->
            when (d) {
                is ParsedUseDecl -> segments(d.path)
                is ParsedPubUseDecl -> segments(d.path)
                else -> null
            }
        }

    fun segments(path: ParsedUsePath): List<String> = when (path) {
        is ParsedUsePath.Module -> path.path.segments
        is ParsedUsePath.Selective -> path.modulePath.segments
    }
}
