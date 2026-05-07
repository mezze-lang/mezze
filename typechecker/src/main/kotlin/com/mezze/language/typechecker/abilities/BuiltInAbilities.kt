package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.parsedast.*

/**
 * Built-in ability registry for TypeChecker.
 *
 * Ability method signatures are loaded from stdlib .mz files (not hardcoded).
 * Only Inspect is compiler-provided (universally available for all types).
 *
 * Operators are desugared to method-call syntax (receiver is implicit `it`):
 * - x + y  ->  x.add {r = y}   (NumAdd ability)
 * - x == y ->  x.eq {r = y}    (Eq ability)
 * - x < y  ->  x.lt {r = y}    (Ord ability)
 */
object BuiltInAbilities {
    private val boolType = MezzeType.BoolType

    /**
     * Map of ability names to their methods, loaded from stdlib .mz files.
     * Each method is a function that takes a type variable and returns the method's function type.
     */
    private val abilities: Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> by lazy {
        buildAbilitiesFromMzFiles()
    }

    /**
     * All built-in ability names (includes NumNeg for backward compat).
     */
    private val allBuiltInAbilityNames: Set<String> by lazy {
        // NumNeg is referenced by some code but it's actually a method of NumSub.
        // Keep it in the names set for isBuiltInAbility() checks.
        abilities.keys + "NumNeg"
    }

    /**
     * Parse ability declarations from stdlib .mz files and build method type generators.
     */
    private fun buildAbilitiesFromMzFiles(): Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> {
        val parser = com.mezze.language.parser.MezzeParser()
        // Try both paths: without prefix (dev/test) and with stdlib/ prefix (JAR/native)
        val abilityFileNames = listOf(
            "abilities/numeric.mz",
            "abilities/compare.mz",
            "abilities/logic.mz",
            "abilities/inspect.mz",
            "abilities/convert.mz",
            "abilities/default.mz",
        )
        val abilityFiles = abilityFileNames.map { name ->
            // Try stdlib/std/ prefix first (embedded resources), then std/ (disk fallback)
            "stdlib/std/$name" to "std/$name"
        }

        val result = mutableMapOf<String, Map<String, (MezzeType.VarType) -> MezzeType>>()

        for ((embeddedPath, diskPath) in abilityFiles) {
            try {
                val source = com.mezze.language.typechecker.ModuleResolver.readStdlibFile(embeddedPath)
                    ?: com.mezze.language.typechecker.ModuleResolver.readStdlibFile(diskPath)
                    ?: readFromFilesystem(diskPath)
                    ?: continue
                val module = parser.parseParsedModule(source, embeddedPath)
                for (decl in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
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
            } catch (e: Exception) {
                // A parse failure here means an ability declaration file in
                // stdlib is malformed. User code will see confusing
                // "NumAdd not found" errors with no clue why. Surface it
                // loudly under MEZZE_DEBUG; in normal mode print a one-line
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

        // Ensure Inspect is always present (compiler-provided).
        // Check by name since the hash-based lookup (WellKnownHashes) is itself computed
        // by parsing this same file — no circular dependency here, but if the parse failed
        // above we'd have no hash yet.  Name-based check is the right sentinel here.
        if ("Inspect" !in result) {
            result["Inspect"] = mapOf(
                "to_dbg_str" to { _: MezzeType.VarType ->
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
     * Read an ability file from the filesystem using mezze.project.root system property.
     * Fallback for when classpath resources aren't available (e.g., test context).
     */
    private fun readFromFilesystem(relativePath: String): String? {
        val projectRoot = System.getProperty("mezze.project.root") ?: return null
        val file = java.io.File(projectRoot, "stdlib/$relativePath")
        return if (file.exists()) file.readText() else null
    }

    /**
     * Convert a ParsedTypeAnnotation to a MezzeType, replacing type variables with the shared typeVar.
     */
    private fun convertAnnotationToType(annotation: ParsedTypeAnnotation, typeVar: MezzeType.VarType): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)
                    ?: if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) {
                        typeVar
                    } else {
                        MezzeType.TypeApplication(annotation.name, emptyList())
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

    /**
     * Get the type of an ability method.
     */
    fun getAbilityMethodType(
        abilityName: String,
        methodName: String,
        typeVar: MezzeType.VarType
    ): MezzeType? {
        return abilities[abilityName]?.get(methodName)?.invoke(typeVar)
    }

    /**
     * Check if a name is a built-in ability.
     */
    fun isBuiltInAbility(name: String): Boolean {
        return name in allBuiltInAbilityNames
    }

    /**
     * Check if a variant type is the Bool variant: | True, False |
     */
    fun isBoolVariant(type: MezzeType): Boolean = PrimitiveTypes.isBoolVariant(type)

    /**
     * Get the canonical Bool type.
     */
    fun getBoolType(): MezzeType = boolType

    /**
     * Get all method names for an ability.
     */
    fun getAvailableMethods(abilityName: String): List<String> {
        return abilities[abilityName]?.keys?.toList() ?: emptyList()
    }

    /**
     * Get all built-in ability names.
     */
    fun getAllAbilityNames(): Set<String> {
        return allBuiltInAbilityNames
    }
}
