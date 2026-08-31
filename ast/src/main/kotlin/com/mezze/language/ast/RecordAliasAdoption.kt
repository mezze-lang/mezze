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

    val rendered = record.fields.mapValues { (_, ty) -> prettyPrintType(ty) }
    val matches = aliases.filter { (_, _, aliasFields) ->
        aliasFields.keys == record.fields.keys &&
            aliasFields.all { (name, ty) -> rendered[name] == prettyPrintType(ty) }
    }
    val (aliasName, hash, _) = matches.singleOrNull() ?: return record
    return record.copy(identity = TypeIdentity.declared(aliasName, hash))
}
