package com.mezze.language.typechecker.validation

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.isTypeVarName
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.renderParsedAnnotation

/**
 * ONE SHAPE, ONE NAME — so inference has exactly one answer.
 *
 *     type Person = { name: Str, age: Int }
 *     let x = { name = "Shriyas", age = 32 }      # infers Person
 *
 * That works only because the shape has exactly one name. A second name for the
 * same thing is what makes adoption a choice, and a choice is what inference
 * cannot make for the author. So a `type` may give a name to a shape that has
 * NONE, and may never give a second name to something already named.
 *
 * Records and variants never reach this check: `type P = { x: Int }` parses as
 * [ParsedRecordDef] and `type C = | R, G |` as [ParsedVariantDef], so an
 * alias's target is only ever a name, an application, a seq, or a function type.
 * That leaves four questions, and the answers come from the REPRESENTATION
 * rather than from taste:
 *
 *  - A FUNCTION TYPE is genuinely nameless — `MezzeType.FunctionType` has no
 *    identity field at all — so `type Handler = { req: Request } -> Response`
 *    gives it a first name and cannot create a second identity for anything.
 *    (`V0706` still refuses an impl on it, because that name does not survive
 *    to a use site; and `T0219` still refuses an effect row on it.)
 *  - A SEQ is `TypeApplication("Seq", …)` and its identity is NOT stamped by
 *    `withAliasIdentity`, so the alias name is dropped — `type Ids = [Int]`
 *    already prints as `[Int]`. Nothing can adopt it, so it cannot create an
 *    ambiguity either. It is allowed when the element mentions a type the
 *    author declared, where naming the collection says something; refused when
 *    the element is built only from builtins, where it renames plumbing.
 *  - An APPLIED name (`Result Response HttpError`, `Option Int`) resolves to a
 *    variant or record, whose identity `withAliasIdentity` DOES stamp. Two
 *    identities over one shape is exactly the ambiguity above, so this is
 *    refused however long the spelling.
 *  - A BARE name — a primitive, a builtin, a newtype, another alias — is
 *    already named. Refused, with one exception: the `__Native*` types are an
 *    internal reserved prefix an author cannot spell, so `type Foreign =
 *    __NativeForeign` is that type's FIRST usable name, not a second one.
 */
internal object TypeAliasTargetValidator {

    /** The opening clause the three RENAME refusals share. */
    private const val renames = "renames something that already has a name: "

    fun validate(module: ParsedModule, registry: DeclarationRegistry) {
        for (decl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            val def = decl.definition as? ParsedTypeAliasDef ?: continue
            refusalFor(decl.name, def.aliasedType, registry, decl.loc)?.let { throw it }
        }
    }

    private fun refusalFor(
        aliasName: String,
        target: ParsedTypeAnnotation,
        registry: DeclarationRegistry,
        loc: Loc,
    ): TypeCheckError? = when (target) {
        // Nameless shapes: naming them is what `type` is for.
        is ParsedFunctionType -> null
        is ParsedRecordType -> null
        is ParsedVariantType -> null

        is ParsedSeqType -> when {
            mentionsAuthorsType(target.elementType) -> null
            // An UNNAMED element is a different fault from a built-in one, and
            // wants different advice: the element is the thing that wanted
            // naming, and naming it makes the collection short enough to spell.
            isUnnamedShape(target.elementType) -> refuse(
                aliasName,
                renderedTarget = renderSeq(target),
                problem = "names the collection but not the thing it collects: its " +
                    "element is a shape with no name of its own",
                fix = "name the element first — `type Row = " +
                    "${renderParsedAnnotation(target.elementType)}`, then " +
                    "`type $aliasName = [Row]`",
                loc = loc,
            )
            else -> refuse(
                aliasName,
                renderedTarget = renderSeq(target),
                problem = "$renames`${renderSeq(target)}` is built only from " +
                    "built-in types",
                fix = "write `${renderSeq(target)}` where you need it, or " +
                    "`newtype $aliasName = ${renderSeq(target)}` for a distinct type that " +
                    "carries its own methods",
                loc = loc,
            )
        }

        is ParsedTypeApp -> refuse(
            aliasName,
            renderedTarget = renderParsedAnnotation(target),
            problem = "${renames}`${target.constructor}` is already a name, and an " +
                "applied one keeps its identity through an alias",
            fix = "use `${renderParsedAnnotation(target)}` directly, or `newtype " +
                "$aliasName = ${renderParsedAnnotation(target)}` for a type of your own",
            loc = loc,
        )

        is ParsedNamedType -> namedTargetRefusal(aliasName, target.name, registry, loc)
        is ParsedQualifiedType -> namedTargetRefusal(aliasName, target.typeName, registry, loc)
    }

    private fun namedTargetRefusal(
        aliasName: String,
        targetName: String,
        registry: DeclarationRegistry,
        loc: Loc,
    ): TypeCheckError? {
        // `type X 'a = 'a` names nothing and renames nothing.
        if (targetName.isTypeVarName()) return null
        // An internal handle an author cannot spell: the alias is its FIRST name.
        if (targetName.startsWith(BuiltinTypes.NATIVE_TYPE_PREFIX)) return null
        // A CYCLE is V0709's to report, and it says far more than this rule
        // could: `type T = T` is not "a second name for T", it is a definition
        // that never terminates. Reporting it here as a rename would replace a
        // precise diagnostic with a vaguer one, so the chain is walked first and
        // a cycle handed on.
        if (participatesInCycle(aliasName, targetName, registry)) return null

        val what = when {
            PrimitiveTypes.isPrimitiveName(targetName) -> "a built-in primitive"
            BuiltinTypes.isBuiltinTypeName(targetName) -> "a built-in type"
            else -> when (registry.lookupType(targetName)?.definition) {
                is ParsedNewtypeDef -> "a `newtype`, which is already a distinct type"
                is ParsedTypeAliasDef -> "another alias"
                is ParsedRecordDef, is ParsedVariantDef -> "already a declared type"
                // Unknown — T0213 reports that, and reporting a second thing
                // about a name that resolves to nothing would be noise.
                else -> return null
            }
        }
        return refuse(
            aliasName,
            renderedTarget = targetName,
            problem = "$renames`$targetName` is $what",
            fix = "use `$targetName` directly, or `newtype $aliasName = $targetName` for a " +
                "type that is genuinely distinct",
            loc = loc,
        )
    }

    /**
     * Does following the alias chain from [targetName] come back to
     * [aliasName]? Bounded by the names already seen, so a cycle that does not
     * include this alias still terminates.
     */
    private fun participatesInCycle(
        aliasName: String,
        targetName: String,
        registry: DeclarationRegistry,
    ): Boolean {
        val seen = mutableSetOf(aliasName)
        var current = targetName
        while (true) {
            if (current == aliasName) return true
            if (!seen.add(current)) return false
            val next = (registry.lookupType(current)?.definition as? ParsedTypeAliasDef)
                ?.aliasedType
            current = when (next) {
                is ParsedNamedType -> next.name
                is ParsedQualifiedType -> next.typeName
                else -> return false
            }
        }
    }

    /**
     * Does this element mention a type the AUTHOR declared, rather than only
     * built-ins? Asked of the whole tree, so `[Option Row]` counts through the
     * nesting. Resolution is not required — an element that names nothing is
     * T0213's to report.
     */
    private fun mentionsAuthorsType(annotation: ParsedTypeAnnotation): Boolean {
        var found = false
        fun walk(a: ParsedTypeAnnotation) {
            if (found) return
            when (a) {
                is ParsedNamedType -> if (isAuthors(a.name)) found = true
                is ParsedQualifiedType -> {
                    if (isAuthors(a.typeName)) found = true else a.args.forEach(::walk)
                }
                is ParsedTypeApp -> {
                    if (isAuthors(a.constructor)) found = true else a.args.forEach(::walk)
                }
                is ParsedSeqType -> walk(a.elementType)
                is ParsedRecordType -> a.entries.forEach { walk(it.type) }
                is ParsedVariantType -> a.constructors.forEach { c -> c.dataType?.let(::walk) }
                is ParsedFunctionType -> { walk(a.paramType); walk(a.returnType) }
            }
        }
        walk(annotation)
        return found
    }

    private fun isAuthors(name: String): Boolean =
        !name.isTypeVarName() &&
            !PrimitiveTypes.isPrimitiveName(name) &&
            !BuiltinTypes.isBuiltinTypeName(name) &&
            !name.startsWith(BuiltinTypes.NATIVE_TYPE_PREFIX)

    private fun renderSeq(target: ParsedSeqType): String =
        "[" + renderParsedAnnotation(target.elementType) + "]"

    /**
     * Context and direction, and no more.
     *
     * The reasoning — one shape one name, so that `let x = { … }` has one type
     * to adopt — is real but belongs in `mez explain V0713`, which carries it in
     * full. An inline diagnostic that recites it is one nobody finishes reading,
     * and reciting it here meant naming `Person` to an author who never wrote a
     * `Person`. Same call this codebase makes for T0219.
     */
    private fun refuse(
        aliasName: String,
        renderedTarget: String,
        problem: String,
        fix: String,
        loc: Loc,
    ) = TypeCheckError(
        "`type $aliasName = $renderedTarget` $problem. A `type` is transparent, so " +
            "`$aliasName` is erased before inference and reaches no signature, diagnostic " +
            "or hover.",
        loc,
        code = ErrorCode.V0713_TYPE_ALIAS_OF_NAMED_TYPE,
        hints = listOf(fix),
    )

    /** A shape with no name of its own — a record, variant or function spelling. */
    private fun isUnnamedShape(a: ParsedTypeAnnotation): Boolean =
        a is ParsedRecordType || a is ParsedVariantType || a is ParsedFunctionType
}
