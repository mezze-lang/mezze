package com.mezze.language.typechecker.abilities

import com.mezze.language.ast.*

/**
 * Built-in ability registry for TypeChecker.
 *
 * Ability method signatures are loaded from stdlib .mz files (not hardcoded).
 * Only Inspect is compiler-provided (universally available for all types).
 *
 * Operators are desugared to method-call syntax (receiver is implicit `it`):
 * - x + y  →  x.add {r = y}   (NumAdd ability)
 * - x == y →  x.eq {r = y}    (Eq ability)
 * - x < y  →  x.lt {r = y}    (Ord ability)
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
                    ?: continue
                val program = parser.parse(source)
                for (decl in program.items.filterIsInstance<AbilityDeclaration>()) {
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
                System.err.println("[BuiltInAbilities] Failed to parse $embeddedPath: ${e.message}")
            }
        }

        // Ensure Inspect is always present (compiler-provided)
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
     * Convert a TypeAnnotation to a MezzeType, replacing type variables with the shared typeVar.
     */
    private fun convertAnnotationToType(annotation: TypeAnnotation, typeVar: MezzeType.VarType): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                PrimitiveTypes.fromName(annotation.name)
                    ?: if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) {
                        typeVar
                    } else {
                        MezzeType.TypeApplication(annotation.name, emptyList())
                    }
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertAnnotationToType(entry.type, typeVar)
                    },
                    rowVar = null
                )
            }
            is FunctionTypeAnnotation -> {
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
    fun isBoolVariant(type: MezzeType): Boolean {
        return type is MezzeType.VariantType &&
               type.constructors.size == 2 &&
               type.constructors.containsKey("True") &&
               type.constructors.containsKey("False") &&
               type.constructors["True"] == null &&
               type.constructors["False"] == null
    }

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
