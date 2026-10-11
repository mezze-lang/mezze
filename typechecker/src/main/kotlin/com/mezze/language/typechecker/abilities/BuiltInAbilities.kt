package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.isTypeVarName
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.util.TypeAnnotationConverter

object BuiltInAbilities {
    private val boolType = PrimitiveTypes.BOOL_TYPE

        private val abilities: Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> by lazy {
        buildAbilitiesFromMzFiles()
    }

    /**
     * The raw parsed ability declarations for the built-in abilities, keyed by
     * name. Unlike [abilities] (which flattens every op signature onto a SINGLE
     * type var and so cannot represent a class parameter like `NumAdd 'r for 'l`),
     * these are the untouched `ParsedAbilityDecl`s straight from the ability
     * `.mz` files, with their real `typeParams` / `receiverTypeParam` /
     * `associatedTypes` and their identity (stamped by the module resolver).
     *
     * These are seeded into the base typing context / registry so a bare `2 + 3`
     * (which never `use`s NumAdd) resolves `.add` through the SAME class-param-
     * aware ability path as a user-defined ability — instead of the single-var
     * flattening in [getAbilityMethodType]. See [getRawAbilityDeclarations].
     */
    private val rawAbilityDecls: Map<String, ParsedAbilityDecl> by lazy {
        parseBuiltInAbilityDecls()
    }

        private val allBuiltInAbilityNames: Set<String> by lazy {
        abilities.keys + "NumNeg"
    }

    /** The ability `.mz` files whose decls back the built-in abilities. */
    private val abilityFileNames = listOf(
        "abilities/numeric.mz",
        "abilities/compare.mz",
        "abilities/logic.mz",
        "abilities/inspect.mz",
        "abilities/convert.mz",
        "abilities/default.mz",
        // NO `abilities/arbitrary.mz`, deliberately, and it was tried.
        //
        // Being nameable without an import is not enough for `Arbitrary`, because a
        // derived body bottoms out in `impl Arbitrary for Int` — and an IMPL still
        // has to be import-visible. Putting the ability here made the name resolve
        // and the instances not, which turns a clean "not imported" into "no impl in
        // scope" raised from a body nobody wrote. `std::abilities::arbitrary` is in
        // the prelude instead, which carries the impls too.
    )

    /** Each ability file's decls, invoking [handle] for every ability decl.
     *  Shared by [buildAbilitiesFromMzFiles] and [parseBuiltInAbilityDecls] so the
     *  flattened method-type map and the raw-decl map stay in lock-step.
     *
     *  Loaded through the module resolver, not parsed here: a built-in ability
     *  must have the SAME identity as the one an import of its module brings, and
     *  identity is stamped once names are resolved — which only the resolver does. */
    private fun forEachBuiltInAbilityDecl(handle: (ParsedAbilityDecl) -> Unit) {
        val resolver = ModuleResolver()
        for (name in abilityFileNames) {
            val modulePath = "std::" + name.removeSuffix(".mz").replace("/", "::")
            val module = resolver.resolveModule(modulePath).source
                ?: error("Compiler internal error: built-in ability module $modulePath has no stamped source")
            for (decl in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
                handle(decl)
            }
        }
    }

    /** The raw parsed decls, keyed by ability name (used by [rawAbilityDecls]). */
    private fun parseBuiltInAbilityDecls(): Map<String, ParsedAbilityDecl> {
        val decls = mutableMapOf<String, ParsedAbilityDecl>()
        forEachBuiltInAbilityDecl { decl -> decls[decl.name] = decl }
        return decls
    }

        private fun buildAbilitiesFromMzFiles(): Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> {
        val result = mutableMapOf<String, Map<String, (MezzeType.VarType) -> MezzeType>>()

        forEachBuiltInAbilityDecl { decl ->
            val methods = mutableMapOf<String, (MezzeType.VarType) -> MezzeType>()
            for (op in decl.operations) {
                methods[op.name] = { typeVar: MezzeType.VarType ->
                    val paramType = convertAnnotationToType(op.paramType, typeVar)
                    val returnType = convertAnnotationToType(op.returnType, typeVar)
                    MezzeType.FunctionType(paramType = paramType, returnType = returnType)
                }
            }
            result[decl.name] = methods
        }

        if ("Inspect" !in result) {
            result["Inspect"] = mapOf(
                "to_dbg" to { _: MezzeType.VarType ->
                    MezzeType.FunctionType(
                        paramType = MezzeType.RecordType(fields = emptyMap(), rowVar = null),
                        returnType = MezzeType.StringType
                    )
                }
            )
        }

        return result
    }

        /**
         * Delegates to the one converter with every type variable pinned to
         * [typeVar] — a built-in ability's operations are written against the
         * ability's own self type, so `'a` here means "the type this ability is
         * implemented for".
         *
         * The copy this replaced handled only named types, records and function
         * types, with `else -> typeVar` swallowing everything else: a sequence,
         * a variant or a type application in a built-in operation's signature
         * silently became the bare self type. Now they convert.
         */
        private fun convertAnnotationToType(annotation: ParsedTypeAnnotation, typeVar: MezzeType.VarType): MezzeType {
            // Every type-variable NAME here means the same thing — the type the
            // ability is implemented for — so pin them all to [typeVar] through the
            // substitution knob the converter already has, rather than by being a
            // separate walker that hard-codes it. Both spellings are registered
            // because the substitution is consulted by bare and ticked name.
            val pinned: Map<String, MezzeType> = annotation.selfAndDescendants()
                .mapNotNull { (it as? ParsedNamedType)?.name?.takeIf { n -> n.isTypeVarName() } }
                .flatMap { sequenceOf(it, it.removePrefix("'")) }
                .distinct()
                .associateWith { typeVar }
            return TypeAnnotationConverter.convertWith(
                annotation,
                config = TypeAnnotationConverter.ConversionConfig(typeParamSubst = pinned),
            )
        }

        fun getAbilityMethodType(
        abilityName: String,
        methodName: String,
        typeVar: MezzeType.VarType
    ): MezzeType? {
        return abilities[abilityName]?.get(methodName)?.invoke(typeVar)
    }

        fun isBuiltInAbility(name: String): Boolean {
        return name in allBuiltInAbilityNames
    }

        fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

        fun getBoolType(): MezzeType = boolType

        fun getAvailableMethods(abilityName: String): List<String> {
        return abilities[abilityName]?.keys?.toList() ?: emptyList()
    }

        fun getAllAbilityNames(): Set<String> {
        return allBuiltInAbilityNames
    }

    /** The raw parsed built-in ability decls, keyed by name. Seeded into the base
     *  typing context + registry so `.add` etc. resolve through the class-param-
     *  aware ability path (not the single-var flattening in [getAbilityMethodType]). */
    fun getRawAbilityDeclarations(): Map<String, ParsedAbilityDecl> {
        return rawAbilityDecls
    }
}
