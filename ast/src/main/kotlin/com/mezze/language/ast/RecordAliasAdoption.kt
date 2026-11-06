package com.mezze.language.ast

import com.mezze.language.typedast.Hash

/**
 * THE canonical record-alias adoption rule (S3.1).
 *
 * "If a type alias matching a record shape is in scope, we use it. ALWAYS."
 *
 * A CLOSED record whose field set and field TYPES exactly match a UNIQUE in-scope
 * CLOSED `type X = { … }` declaration ADOPTS `X`'s nominal identity
 * ([TypeIdentity.Declared]). This is what makes `let p = { user = "a", body = "b" }`
 * carry `Post` identity so an ability method call `p.summarize {}` resolves
 * `impl Summary for Post` at dispatch. Transparency is preserved: the record keeps
 * its fields, so a `Post`-adopted value still unifies structurally everywhere a bare
 * `{ user, body }` is accepted (Anonymous↔Declared record unify).
 *
 * This rule USED to be implemented twice — once at constraint generation
 * (`adoptRecordAlias`) and once at emit (`labelRecordWithAlias`). S3.1 folds both
 * into this single function; each caller only supplies the in-scope alias list its
 * own scoping produces (local decls, imports, `pub`/visibility), then this decides.
 *
 * Guards (unchanged from both former copies):
 *  - never touches an OPEN record ([MezzeType.RecordType.rowVar] != null);
 *  - never touches a record that already carries a non-[TypeIdentity.Anonymous]
 *    identity;
 *  - never touches an empty record;
 *  - fires only on a UNIQUE structural match (same-shape closed aliases are rejected
 *    at declaration, #187, so a match is unique in practice; the guard is defensive).
 *
 * @param aliases in-scope CLOSED record aliases as (name, decl content hash,
 *   field-name→field-type). The caller materializes this respecting its own scoping.
 */
fun adoptRecordAlias(
    record: MezzeType.RecordType,
    aliases: List<Triple<String, Hash, Map<String, MezzeType>>>
): MezzeType.RecordType {
    if (record.rowVar != null) return record
    if (record.identity != TypeIdentity.Anonymous) return record
    if (record.fields.isEmpty()) return record

    // The field names first: almost no record has an alias with its names, and
    // rendering every field of every record literal to find that out was the
    // most of what printing types allocated during a check.
    val sameNames = aliases.filter { (_, _, aliasFields) -> aliasFields.keys == record.fields.keys }
    if (sameNames.isEmpty()) return record
    val matches = sameNames.filter { (_, _, aliasFields) ->
        // UP TO INSTANTIATION, not by rendered string.
        //
        // A generic alias declares its fields in terms of its own type
        // parameters: `type Box 'a = { v: 'a }` has field `v: 'a`. A literal's
        // fields are always concrete, so comparing printed forms (`'a` against
        // `Int`) could never match one -- no generic record was ever adopted, and
        // so none could reach a nominal impl or print as `Box Int`.
        //
        // The alias's field type is the PATTERN and the literal's is the subject.
        // A type variable in the pattern binds, consistently: `type Pair 'a =
        // { l: 'a, r: 'a }` adopts `{ l = 1, r = 2 }` but not `{ l = 1, r = "x" }`.
        val bindings = mutableMapOf<String, MezzeType>()
        aliasFields.all { (name, declared) ->
            val actual = record.fields[name] ?: return@all false
            matchesUpToInstantiation(declared, actual, bindings)
        }
    }
        // ONE declaration can reach us TWICE. Emit assembles its pool from the
        // declaration registry AND the import context, and a `use`d alias sits in
        // both — so an imported `type Boxed = { n: Int }` arrived as two entries
        // carrying the SAME content hash. Identity is the hash, so that is one
        // type seen twice, not two rivals; counting it as ambiguity made the
        // uniqueness guard below reject the only candidate there was.
        //
        // What that cost: the literal in `{ n = 2 }.tag {}` kept Anonymous, and
        // `impl Tag for Boxed` registers the nominal target `TypeApplication(Boxed)`
        // — a pair `typeMatchesTarget` has no arm for. So the method call compiled
        // to a plain field read and failed at RUNTIME with `Field not found: tag`,
        // while `let b: Boxed = { n = 2 }` (annotated) and `let b = { n = 2 }`
        // (solved through a type variable) both dispatched. Only an IMPORTED alias
        // was affected, which is every alias a library exposes.
        //
        // Dedupe on the hash alone: two genuinely DIFFERENT declarations that share
        // a shape keep their distinct hashes and stay ambiguous here, which is what
        // the guard is for.
        .distinctBy { (_, hash, _) -> hash }
    val (aliasName, hash, aliasFields) = matches.singleOrNull() ?: return record
    // Record HOW it was instantiated, so the type prints `Box Int` and not a bare
    // `Box` (11.16). The argument order is the order the alias's type variables
    // first appear across its declared fields, which is the declaration's own
    // order whenever the parameters are used in the order they are declared --
    // the ordinary case, and all that a single-parameter alias can mean.
    val bindings = mutableMapOf<String, MezzeType>()
    val order = mutableListOf<String>()
    for ((name, declared) in aliasFields) {
        val actual = record.fields[name] ?: continue
        collectVarOrder(declared, actual, bindings, order)
    }
    return record.copy(
        identity = TypeIdentity.declared(aliasName, hash),
        typeArgs = order.mapNotNull { bindings[it] },
    )
}

/**
 * Does [subject] match [pattern], letting the PATTERN's type variables bind?
 *
 * One-way: only the pattern (an alias's declared field type) may contain
 * variables to bind. A variable binds once and must agree thereafter, so a
 * two-parameter-shaped alias cannot be satisfied by mismatched fields.
 *
 * Recurses through a type application's arguments, so `type Wrap 'a =
 * { v: Option 'a }` is matched by `{ v = <Option Int> }`. Anything else must be
 * equal outright -- deliberately conservative: adoption gives a literal a
 * NOMINAL identity, and a loose match would hand it a name it is not.
 */
private fun matchesUpToInstantiation(
    pattern: MezzeType,
    subject: MezzeType,
    bindings: MutableMap<String, MezzeType>,
): Boolean = when (pattern) {
    is MezzeType.VarType -> {
        val held = bindings[pattern.variable.name]
        if (held == null) {
            bindings[pattern.variable.name] = subject
            true
        } else {
            held == subject
        }
    }
    is MezzeType.TypeApplication ->
        subject is MezzeType.TypeApplication &&
            pattern.constructor == subject.constructor &&
            pattern.args.size == subject.args.size &&
            pattern.args.indices.all {
                matchesUpToInstantiation(pattern.args[it], subject.args[it], bindings)
            }
    else -> pattern == subject
}


/**
 * Walk [pattern] against [subject] recording each of the pattern's type
 * variables once, in first-appearance order.
 *
 * Same traversal as [matchesUpToInstantiation], which has already succeeded by
 * the time this runs — so this only collects, never judges.
 */
private fun collectVarOrder(
    pattern: MezzeType,
    subject: MezzeType,
    bindings: MutableMap<String, MezzeType>,
    order: MutableList<String>,
) {
    when (pattern) {
        is MezzeType.VarType -> {
            val n = pattern.variable.name
            if (n !in bindings) {
                bindings[n] = subject
                order.add(n)
            }
        }
        is MezzeType.TypeApplication ->
            if (subject is MezzeType.TypeApplication && pattern.args.size == subject.args.size) {
                for (i in pattern.args.indices) {
                    collectVarOrder(pattern.args[i], subject.args[i], bindings, order)
                }
            }
        else -> {}
    }
}
