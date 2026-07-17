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

        private val allBuiltInAbilityNames: Set<String> by lazy {
        abilities.keys + "NumNeg"
    }

        private fun buildAbilitiesFromMzFiles(): Map<String, Map<String, (MezzeType.VarType) -> MezzeType>> {
        val parser = MezzeParser()
        val abilityFileNames = listOf(
            "abilities/numeric.mz",
            "abilities/compare.mz",
            "abilities/logic.mz",
            "abilities/inspect.mz",
            "abilities/convert.mz",
            "abilities/default.mz",
        )
        val abilityFiles = abilityFileNames.map { name ->
            "stdlib/std/$name" to "std/$name"
        }

        val result = mutableMapOf<String, Map<String, (MezzeType.VarType) -> MezzeType>>()

        for ((embeddedPath, diskPath) in abilityFiles) {
            try {
                val source = ModuleResolver.readStdlibFile(embeddedPath)
                    ?: ModuleResolver.readStdlibFile(diskPath)
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
}
