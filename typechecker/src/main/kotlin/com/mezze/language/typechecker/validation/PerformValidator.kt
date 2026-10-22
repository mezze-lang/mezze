package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typedast.Hash

class PerformValidator(
    private val typeDeclarations: Map<String, ParsedTypeDecl>,
    private val implDeclarations: Map<String, ParsedPerformerDecl>,
) {

    fun validatePerformExpressionsInExpression(
        expr: ParsedExpr,
        implDeclarations: Map<String, ParsedPerformerDecl>,
        effectDeclarations: Map<String, ParsedEffectDecl>,
        functionDefinitions: Map<String, ParsedLambda>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is ParsedPerform -> {
                val performErrors = validatePerformExpression(
                    expr,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(performErrors)

                val innerErrors = validatePerformExpressionsInExpression(
                    expr.body,
                    implDeclarations,
                    effectDeclarations,
                    functionDefinitions
                )
                errors.addAll(innerErrors)
            }

            is ParsedDo -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is ParsedStatement.Let -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.value, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                        is ParsedStatement.Expr -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
                errors.addAll(validatePerformExpressionsInExpression(expr.result, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedLambda -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.body, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedIf -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.condition, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.thenBranch, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.elseBranch, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedFunctionCall -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.function, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.arguments, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedRecordLit -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is ParsedRecordEntry.Field -> {
                            entry.value?.let { value ->
                                errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                            }
                        }
                        is ParsedRecordEntry.Spread -> {
                            errors.addAll(validatePerformExpressionsInExpression(entry.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
            }

            is ParsedMatch -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.scrutinee, implDeclarations, effectDeclarations, functionDefinitions))
                for (case in expr.cases) {
                    case.guard?.let { guard ->
                        errors.addAll(validatePerformExpressionsInExpression(guard, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                    errors.addAll(validatePerformExpressionsInExpression(case.body, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is ParsedVariantConstruct -> {
                expr.value?.let { value ->
                    errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is ParsedNewtypeConstruct -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.value, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedMemberAccess -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.receiver, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedSeqLit -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is ParsedSeqElement.Regular -> element.expr
                        is ParsedSeqElement.Spread -> element.expr
                    }
                    errors.addAll(validatePerformExpressionsInExpression(elementExpr, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is ParsedStringLit -> {
                for (part in expr.parts) {
                    if (part is ParsedStringPart.Interpolation) {
                        errors.addAll(validatePerformExpressionsInExpression(part.expr, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                }
            }

            is ParsedPipe -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.left, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.right, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedParens -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.expr, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedQuote -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.value, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedMacroCall -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.compiled(), implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedBinaryOp -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.left, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.right, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is ParsedIdentifier,
            is ParsedIntLit,
            is ParsedNatLit,
            is ParsedFloatLit,
            is ParsedFracLit,
            is ParsedCharLit,
            is ParsedPipePlaceholder,
            is ParsedEffectCall -> {
            }
        }

        return errors
    }

    private fun validatePerformExpression(
        expr: ParsedPerform,
        implDeclarations: Map<String, ParsedPerformerDecl>,
        effectDeclarations: Map<String, ParsedEffectDecl>,
        functionDefinitions: Map<String, ParsedLambda>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val thrownTypes = collectThrownErrorTypes(expr.body, effectDeclarations, functionDefinitions)

        for (handler in expr.handlers) {
            val handlerName = handler.performer.localName
            val implDecl = implDeclarations[handlerName] ?: continue

            val effectName = implDecl.effect.localName

            if (implDecl.associatedTypeBindings.isEmpty()) continue

            val thrownForEffect = thrownTypes[effectName] ?: continue

            for ((typeName, handlerTypeAnnotation) in implDecl.associatedTypeBindings) {
                val thrownType = thrownForEffect[typeName] ?: continue

                val handlerType = expandParsedTypeAnnotation(handlerTypeAnnotation)

                val compatible = areAssociatedTypesCompatible(handlerType, thrownType)

                if (!compatible) {
                    errors.add(
                        ValidationError.HandlerTypeMismatch(
                            handlerName = handlerName,
                            effectName = effectName,
                            expectedType = handlerType,
                            actualType = thrownType,
                            loc = expr.loc
                        )
                    )
                }
            }
        }

        return errors
    }

    private fun collectThrownErrorTypes(
        expr: ParsedExpr,
        effectDeclarations: Map<String, ParsedEffectDecl>,
        functionDefinitions: Map<String, ParsedLambda>
    ): Map<String, Map<String, MezzeType>> {
        val result = mutableMapOf<String, MutableMap<String, MezzeType>>()
        val visitedFunctions = mutableSetOf<String>()

        fun traverse(e: ParsedExpr) {
            when (e) {
                is ParsedEffectCall -> {
                    val effectName = e.operation.qualifier?.toString() ?: ""
                    val operationName = e.operation.localName

                    val effectDecl = effectDeclarations[effectName]
                    if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                        extractAssociatedTypeValues(effectDecl, operationName, e.arguments)?.let { typeMap ->
                            val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                            for ((assocTypeName, extractedType) in typeMap) {
                                unifyThrownType(effectMap, assocTypeName, extractedType)
                            }
                        }
                    }
                }

                is ParsedFunctionCall -> {
                    if (e.function is ParsedMemberAccess) {
                        val memberAccess = e.function as ParsedMemberAccess
                        val operationName = memberAccess.member

                        val effectName = when (val receiver = memberAccess.receiver) {
                            is ParsedIdentifier -> receiver.localName
                            is ParsedVariantConstruct -> receiver.constructor
                            else -> null
                        }

                        if (effectName != null) {
                            val effectDecl = effectDeclarations[effectName]
                            if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                                extractAssociatedTypeValues(effectDecl, operationName, e.arguments)?.let { typeMap ->
                                    val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                                    for ((assocTypeName, extractedType) in typeMap) {
                                        unifyThrownType(effectMap, assocTypeName, extractedType)
                                    }
                                }
                            }
                        }
                    }

                    if (e.function is ParsedIdentifier) {
                        val funcName = (e.function as ParsedIdentifier).localName
                        if (!visitedFunctions.contains(funcName)) {
                            val funcDef = functionDefinitions[funcName]
                            if (funcDef != null) {
                                visitedFunctions.add(funcName)
                                traverse(funcDef.body)
                            }
                        }
                    }

                    traverse(e.function)
                    traverse(e.arguments)
                }

                is ParsedDo -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is ParsedStatement.Let -> traverse(stmt.value)
                            is ParsedStatement.Expr -> traverse(stmt.expr)
                        }
                    }
                    traverse(e.result)
                }

                is ParsedIf -> {
                    traverse(e.condition)
                    traverse(e.thenBranch)
                    traverse(e.elseBranch)
                }

                is ParsedMatch -> {
                    traverse(e.scrutinee)
                    for (case in e.cases) {
                        traverse(case.body)
                    }
                }

                is ParsedLambda -> traverse(e.body)

                is ParsedRecordLit -> {
                    for (entry in e.entries) {
                        when (entry) {
                            is ParsedRecordEntry.Field -> entry.value?.let { traverse(it) }
                            is ParsedRecordEntry.Spread -> traverse(entry.expr)
                        }
                    }
                }

                is ParsedVariantConstruct -> e.value?.let { traverse(it) }
                is ParsedNewtypeConstruct -> traverse(e.value)
                is ParsedMemberAccess -> traverse(e.receiver)
                is ParsedSeqLit -> e.elements.forEach { element ->
                    when (element) {
                        is ParsedSeqElement.Regular -> traverse(element.expr)
                        is ParsedSeqElement.Spread -> traverse(element.expr)
                    }
                }
                is ParsedStringLit -> {
                    for (part in e.parts) {
                        if (part is ParsedStringPart.Interpolation) traverse(part.expr)
                    }
                }
                is ParsedPerform -> {
                    // Do NOT traverse into nested perform expressions —
                    // the nested perform has its own handler that catches errors from its inner expression.
                }

                is ParsedPipe -> {
                    traverse(e.left)
                    traverse(e.right)
                }

                is ParsedParens -> traverse(e.expr)
                is ParsedQuote -> traverse(e.value)
                is ParsedMacroCall -> traverse(e.compiled())

                is ParsedBinaryOp -> { traverse(e.left); traverse(e.right) }

                is ParsedIdentifier,
                is ParsedIntLit,
                is ParsedNatLit,
                is ParsedFloatLit,
                is ParsedFracLit,
                is ParsedCharLit,
                is ParsedPipePlaceholder,
                is ParsedEffectCall -> { }
            }
        }

        traverse(expr)
        return result
    }

    private fun findAssociatedTypeFields(
        typeAnnotation: ParsedTypeAnnotation,
        associatedTypeNames: Set<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (typeAnnotation is ParsedRecordType) {
            for (entry in typeAnnotation.entries) {
                if (entry.type is ParsedNamedType) {
                    val typeName = (entry.type as ParsedNamedType).name
                    if (typeName in associatedTypeNames) result[entry.name] = typeName
                }
            }
        }
        return result
    }

    private fun extractAssociatedTypeValues(
        effectDecl: ParsedEffectDecl,
        operationName: String,
        args: ParsedExpr
    ): Map<String, MezzeType>? {
        val operation = effectDecl.operations.find { it.name == operationName } ?: return null
        val paramTypeAnnotation = operation.paramType
        val associatedTypeNames = effectDecl.associatedTypes.toSet()
        val fieldToAssocType = findAssociatedTypeFields(paramTypeAnnotation, associatedTypeNames)

        if (fieldToAssocType.isEmpty()) return null

        if (args !is ParsedRecordLit) return null

        val result = mutableMapOf<String, MezzeType>()
        for ((fieldName, assocTypeName) in fieldToAssocType) {
            val fieldEntry = args.entries.filterIsInstance<ParsedRecordEntry.Field>()
                .find { it.name == fieldName }

            val fieldValue = fieldEntry?.value ?: continue

            val fieldType = constructTypeFromParsedExpression(fieldValue) ?: continue

            result[assocTypeName] = fieldType
        }

        return if (result.isNotEmpty()) result else null
    }

    private fun constructTypeFromParsedExpression(expr: ParsedExpr): MezzeType? {
        return when (expr) {
            is ParsedRecordLit -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is ParsedRecordEntry.Field && entry.value != null) {
                        val fieldType = constructTypeFromParsedExpression(entry.value!!)
                        if (fieldType != null) {
                            fields[entry.name] = fieldType
                        }
                    }
                }
                MezzeType.RecordType(fields, null)
            }
            is ParsedVariantConstruct -> {
                if (expr.constructor == BuiltinTypes.TRUE || expr.constructor == BuiltinTypes.FALSE) {
                    PrimitiveTypes.BOOL_TYPE
                } else {
                    val payloadType = expr.value?.let { constructTypeFromParsedExpression(it) }
                    MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is ParsedStringLit -> MezzeType.StringType
            is ParsedIntLit -> MezzeType.IntType
            is ParsedFloatLit -> MezzeType.FloatType
            is ParsedNatLit -> {
                val decl = typeDeclarations["Nat"]
                val newtypeDef = decl?.definition as? ParsedNewtypeDef
                val hash = decl?.contentHash
                MezzeType.NewtypeType(
                    name = "Nat",
                    constructor = newtypeDef?.constructor ?: "Nat",
                    wrappedType = MezzeType.IntType,
                    identity = if (hash != null) TypeIdentity.Declared(hash, "Nat") else TypeIdentity.Anonymous,
                )
            }
            is ParsedCharLit -> MezzeType.CharType
            is ParsedFracLit -> MezzeType.FracType
            is ParsedIdentifier -> null
            else -> null
        }
    }

    /**
     * Convert an annotation, expanding a NAME that refers to a local type
     * declaration into that declaration's structure.
     *
     * Distinct from plain conversion, which labels `Color` as an applied type and
     * leaves it labelled: comparing a thrown type against a handler's declared one
     * needs the shape. The shared converter's `resolveTypeAlias` only inlines
     * transparent `type X = …` aliases by design, so the record/variant/newtype
     * expansion here stays — but everything BELOW the expansion goes through the
     * one converter.
     *
     * Each declaration is converted in ONE variable scope. Per-field conversion
     * gave every occurrence of a name its own variable, so
     * `type Pair 'a = { l: 'a, r: 'a }` expanded to `{ l: 'b, r: 'c }` and stopped
     * requiring the two halves to agree — the same defect [TypeAnnotationConverter.convertSignature]
     * exists to prevent, which is why it is used here rather than re-derived.
     */
    private fun expandParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        if (annotation !is ParsedNamedType) return TypeAnnotationConverter.convert(annotation)

        PrimitiveTypes.fromName(annotation.name)?.let { return it }
        val def = typeDeclarations[annotation.name]?.definition
            ?: return MezzeType.VarType(TypeVar.freshWithName(annotation.name))

        return when (def) {
            is ParsedVariantDef -> MezzeType.VariantType(
                constructors = TypeAnnotationConverter.convertSignature(
                    def.constructors.mapNotNull { ctor -> ctor.dataType?.let { ctor.name to it } },
                    TypeAnnotationConverter.ConversionConfig(honorOpenRowVars = true),
                ).toMap().let { converted ->
                    // Payload-less constructors carry null, and must still appear.
                    def.constructors.associate { it.name to converted[it.name] }
                },
                rowVar = if (def.isOpen) RowVar.fresh() else null,
            )
            is ParsedRecordDef -> MezzeType.RecordType(
                fields = TypeAnnotationConverter.convertSignature(
                    def.fields.map { it.name to it.type },
                    TypeAnnotationConverter.ConversionConfig(honorOpenRowVars = true),
                ).toMap(),
            )
            is ParsedNewtypeDef -> TypeAnnotationConverter.convert(def.wrappedType)
            is ParsedTypeAliasDef -> TypeAnnotationConverter.convert(def.aliasedType)
        }
    }

    private fun unifyThrownType(
        effectMap: MutableMap<String, MezzeType>,
        typeName: String,
        newType: MezzeType
    ) {
        val existing = effectMap[typeName]
        if (existing == null) {
            effectMap[typeName] = newType
        } else if (existing is MezzeType.VariantType && newType is MezzeType.VariantType) {
            val mergedConstructors = existing.constructors + newType.constructors
            effectMap[typeName] = MezzeType.VariantType(mergedConstructors, null)
        }
    }

    private fun areAssociatedTypesCompatible(handlerType: MezzeType, exprType: MezzeType): Boolean {
        if (handlerType == exprType) return true

        if (handlerType is MezzeType.VarType) return true

        // The two Bool bridges that used to sit here — one per direction, pairing a
        // primitive `BoolType` with a `| True, False |` variant — are gone with the
        // dual representation. `handlerType == exprType` above answers Bool now.

        if (handlerType is MezzeType.VariantType && exprType is MezzeType.VariantType) {
            if (PrimitiveTypes.isBoolVariant(handlerType) && PrimitiveTypes.isBoolVariant(exprType)) {
                return true
            }

            val handlerVariantNames = handlerType.constructors.keys
            val exprVariantNames = exprType.constructors.keys

            return exprVariantNames.all { it in handlerVariantNames }
        }

        if ((handlerType is MezzeType.VariantType) != (exprType is MezzeType.VariantType)) {
            return false
        }

        return handlerType == exprType
    }
}
