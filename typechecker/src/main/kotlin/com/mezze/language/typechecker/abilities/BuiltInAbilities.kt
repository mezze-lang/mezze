package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.isTypeVarName
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.util.WellKnownTypeApp

object BuiltInAbilities {
    private val boolType = MezzeType.BoolType

        private val abilities: Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> by lazy {
        buildAbilitiesFromMzFiles()
    }

    /**
     * The raw parsed ability declarations for the built-in abilities, keyed by
     * name. Unlike [abilities] (which flattens every op signature onto a SINGLE
     * type var and so cannot represent a class parameter like `NumAdd 'r for 'l`),
     * these are the untouched `ParsedAbilityDecl`s straight from the ability
     * `.mz` files, with their real `typeParams` / `receiverTypeParam` /
     * `associatedTypes` and a valid `contentHash` (the parser runs the hasher).
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
    )

    /** Read + parse each ability file, invoking [handle] for every ability decl.
     *  Shared by [buildAbilitiesFromMzFiles] and [parseBuiltInAbilityDecls] so the
     *  flattened method-type map and the raw-decl map stay in lock-step. */
    private fun forEachBuiltInAbilityDecl(handle: (ParsedAbilityDecl) -> Unit) {
        val parser = MezzeParser()
        for (name in abilityFileNames) {
            val embeddedPath = "stdlib/std/$name"
            val diskPath = "std/$name"
            try {
                val source = ModuleResolver.readStdlibFile(embeddedPath)
                    ?: ModuleResolver.readStdlibFile(diskPath)
                    ?: readFromFilesystem(diskPath)
                    ?: continue
                val module = parser.parseParsedModule(source, embeddedPath)
                for (decl in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
                    handle(decl)
                }
            } catch (e: Exception) {
                // warning so it's at least visible in CI logs. (#308)
                System.err.println(
                    "[BuiltInAbilities] WARNING: failed to parse $embeddedPath: " +
                    "${e.javaClass.simpleName}: ${e.message}. " +
                    "Affected abilities will appear missing to user code."
                )
                if (System.getenv("MEZZE_DEBUG") != null) {
                    e.printStackTrace(System.err)
                }
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

        private fun readFromFilesystem(relativePath: String): String? {
        val projectRoot = System.getProperty("mezze.project.root") ?: return null
        val file = java.io.File(projectRoot, "stdlib/$relativePath")
        return if (file.exists()) file.readText() else null
    }

        private fun convertAnnotationToType(annotation: ParsedTypeAnnotation, typeVar: MezzeType.VarType): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)
                    ?: if (annotation.name.isTypeVarName()) {
                        typeVar
                    } else {
                        WellKnownTypeApp.of(annotation.name, emptyList())
                    }
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertAnnotationToType(entry.type, typeVar)
                    },
                    rowVar = null
                )
            }
            is ParsedFunctionType -> {
                MezzeType.FunctionType(
                    paramType = convertAnnotationToType(annotation.paramType, typeVar),
                    returnType = convertAnnotationToType(annotation.returnType, typeVar)
                )
            }
            else -> typeVar
        }
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
