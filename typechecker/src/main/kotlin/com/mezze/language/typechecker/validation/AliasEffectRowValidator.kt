package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.*

/**
 * A `type`/`newtype` naming a function type may carry NO effect row.
 *
 * ```mezze
 * type Gen 'a = {} -> 'a            # fine
 * type Gen 'a = {} -> 'a <Sample>   # refused
 * type Gen 'a = {} -> 'a <...>      # refused
 * ```
 *
 * The alias names a SHAPE. What a particular function of that shape performs is
 * the business of the signature that uses it, where a reader can see it.
 *
 * Neither row breaks soundness behind the name — measured both ways: an alias
 * behaves exactly like the spelled-out type, propagating where that propagates
 * and refusing where that refuses. The objection is readability, and it lands on
 * the two spellings for different reasons:
 *
 *  - A NAMED effect is invisible at the use site. `{} -> Gen Str <Other>` shows
 *    one row where there are two, and the only way to find the second is to open
 *    the declaration. It cannot be moved to the use site either, because a row
 *    needs an ARROW to own it and `Gen Str` is not one — so either the effect
 *    hides behind the name or the name goes.
 *  - An OPEN row says "may perform anything". It hides no PARTICULAR effect, but
 *    it quietly widens every use of the name to accept any effectful function —
 *    and against a closed enclosing row those effects are absorbed, so a
 *    function can call an effectful callback while declaring itself pure. A
 *    permissive default is worse behind a name than written out.
 *
 * SCOPE — only an alias whose TARGET IS a function type. A record FIELD holding
 * an effectful callback is untouched:
 *
 * ```mezze
 * type Runner = { run: {} -> Int <Tick> }   # fine — the arrow is the FIELD's
 * ```
 *
 * That is how `Routes`, `RouteEntry` and `SelectBranch` are written, and routing
 * and `select` are impossible without it. A closed named row on a field also
 * propagates properly — calling it without declaring the effect is refused,
 * which `AliasEffectRowTest` pins.
 *
 * The stdlib had exactly ONE alias of the refused kind, `Sampler 'a = {} -> 'a
 * <Sample>`, now written out at its use sites. Its own doc had already argued the
 * generator "is just a thunk"; spelling the thunk is that claim without the
 * indirection.
 */
internal class AliasEffectRowValidator {
    fun validate(module: ParsedModule): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        for (decl in module.decls) {
            if (decl !is ParsedTypeDecl) continue
            val target = when (val def = decl.definition) {
                is ParsedTypeAliasDef -> def.aliasedType
                is ParsedNewtypeDef -> def.wrappedType
                else -> null
            } ?: continue
            val fn = target as? ParsedFunctionType ?: continue
            // No row written at all is the only accepted form. A row present but
            // EMPTY of names is still a row: `<...>` arrives non-null with no
            // names on it.
            val row = fn.effects ?: continue
            errors.add(ValidationError.EffectRowInTypeAlias(
                typeName = decl.name,
                row = render(row),
                loc = decl.loc,
            ))
        }
        return errors
    }

    /**
     * The row as written, for the message. Both shapes count: `effects` holds
     * plain names (`<Tick>`) and `effectAnnotations` the instance form carrying
     * associated types (`<Exception having 'error = E>`). Reading only the latter
     * saw `<Tick>` as EMPTY and let the rule pass everything — the refusal tests
     * caught it.
     */
    private fun render(row: ParsedEffectRow): String {
        val names = (row.effects + row.effectAnnotations.map { it.name }).distinct().sorted()
        val parts = names + if (row.isPolymorphic) listOf("...") else emptyList()
        return "<" + parts.joinToString(", ") + ">"
    }
}
