package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.util.TypeAnnotationConverter

class PerformValidator(
    private val typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
    private val implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
) {

    fun validatePerformExpressionsInExpression(
        expr: com.mezze.language.parsedast.ParsedExpr,
        implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        when (expr) {
            is com.mezze.language.parsedast.ParsedPerform -> {
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

            is com.mezze.language.parsedast.ParsedDo -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is com.mezze.language.parsedast.ParsedStatement.Let -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.value, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                        is com.mezze.language.parsedast.ParsedStatement.Expr -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                        is com.mezze.language.parsedast.ParsedStatement.Bind -> {
                            errors.addAll(validatePerformExpressionsInExpression(stmt.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
                errors.addAll(validatePerformExpressionsInExpression(expr.result, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedLambda -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.body, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedIf -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.condition, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.thenBranch, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.elseBranch, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedFunctionCall -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.function, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.arguments, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedRecordLit -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is com.mezze.language.parsedast.ParsedRecordEntry.Field -> {
                            entry.value?.let { value ->
                                errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                            }
                        }
                        is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> {
                            errors.addAll(validatePerformExpressionsInExpression(entry.expr, implDeclarations, effectDeclarations, functionDefinitions))
                        }
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedMatch -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.scrutinee, implDeclarations, effectDeclarations, functionDefinitions))
                for (case in expr.cases) {
                    case.guard?.let { guard ->
                        errors.addAll(validatePerformExpressionsInExpression(guard, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                    errors.addAll(validatePerformExpressionsInExpression(case.body, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedVariantConstruct -> {
                expr.value?.let { value ->
                    errors.addAll(validatePerformExpressionsInExpression(value, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedMemberAccess -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.receiver, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedSeqLit -> {
                for (element in expr.elements) {
                    val elementExpr = when (element) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> element.expr
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> element.expr
                    }
                    errors.addAll(validatePerformExpressionsInExpression(elementExpr, implDeclarations, effectDeclarations, functionDefinitions))
                }
            }

            is com.mezze.language.parsedast.ParsedStringLit -> {
                for (part in expr.parts) {
                    if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) {
                        errors.addAll(validatePerformExpressionsInExpression(part.expr, implDeclarations, effectDeclarations, functionDefinitions))
                    }
                }
            }

            is com.mezze.language.parsedast.ParsedPipe -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.left, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.right, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedParens -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.expr, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedBinaryOp -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.left, implDeclarations, effectDeclarations, functionDefinitions))
                errors.addAll(validatePerformExpressionsInExpression(expr.right, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedUnaryOp -> {
                errors.addAll(validatePerformExpressionsInExpression(expr.operand, implDeclarations, effectDeclarations, functionDefinitions))
            }

            is com.mezze.language.parsedast.ParsedIdentifier,
            is com.mezze.language.parsedast.ParsedIntLit,
            is com.mezze.language.parsedast.ParsedNatLit,
            is com.mezze.language.parsedast.ParsedFloatLit,
            is com.mezze.language.parsedast.ParsedFracLit,
            is com.mezze.language.parsedast.ParsedCharLit,
            is com.mezze.language.parsedast.ParsedPipePlaceholder,
            is com.mezze.language.parsedast.ParsedEffectCall -> {
            }
        }

        return errors
    }

    private fun validatePerformExpression(
        expr: com.mezze.language.parsedast.ParsedPerform,
        implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
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
        expr: com.mezze.language.parsedast.ParsedExpr,
        effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
        functionDefinitions: Map<String, com.mezze.language.parsedast.ParsedLambda>
    ): Map<String, Map<String, MezzeType>> {
        val result = mutableMapOf<String, MutableMap<String, MezzeType>>()
        val visitedFunctions = mutableSetOf<String>()

        fun traverse(e: com.mezze.language.parsedast.ParsedExpr) {
            when (e) {
                is com.mezze.language.parsedast.ParsedEffectCall -> {
                    val effectName = e.operation.qualifier?.toString() ?: ""
                    val operationName = e.operation.localName

                    val effectDecl = effectDeclarations[effectName]
                    if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                        extractAssociatedTypeValuesV2(effectDecl, operationName, e.arguments)?.let { typeMap ->
                            val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                            for ((assocTypeName, extractedType) in typeMap) {
                                unifyThrownType(effectMap, assocTypeName, extractedType)
                            }
                        }
                    }
                }

                is com.mezze.language.parsedast.ParsedFunctionCall -> {
                    if (e.function is com.mezze.language.parsedast.ParsedMemberAccess) {
                        val memberAccess = e.function as com.mezze.language.parsedast.ParsedMemberAccess
                        val operationName = memberAccess.member

                        val effectName = when (val receiver = memberAccess.receiver) {
                            is com.mezze.language.parsedast.ParsedIdentifier -> receiver.localName
                            is com.mezze.language.parsedast.ParsedVariantConstruct -> receiver.constructor
                            else -> null
                        }

                        if (effectName != null) {
                            val effectDecl = effectDeclarations[effectName]
                            if (effectDecl != null && effectDecl.associatedTypes.isNotEmpty()) {
                                extractAssociatedTypeValuesV2(effectDecl, operationName, e.arguments)?.let { typeMap ->
                                    val effectMap = result.getOrPut(effectName) { mutableMapOf() }
                                    for ((assocTypeName, extractedType) in typeMap) {
                                        unifyThrownType(effectMap, assocTypeName, extractedType)
                                    }
                                }
                            }
                        }
                    }

                    if (e.function is com.mezze.language.parsedast.ParsedIdentifier) {
                        val funcName = (e.function as com.mezze.language.parsedast.ParsedIdentifier).localName
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

                is com.mezze.language.parsedast.ParsedDo -> {
                    for (stmt in e.statements) {
                        when (stmt) {
                            is com.mezze.language.parsedast.ParsedStatement.Let -> traverse(stmt.value)
                            is com.mezze.language.parsedast.ParsedStatement.Expr -> traverse(stmt.expr)
                            is com.mezze.language.parsedast.ParsedStatement.Bind -> traverse(stmt.expr)
                        }
                    }
                    traverse(e.result)
                }

                is com.mezze.language.parsedast.ParsedIf -> {
                    traverse(e.condition)
                    traverse(e.thenBranch)
                    traverse(e.elseBranch)
                }

                is com.mezze.language.parsedast.ParsedMatch -> {
                    traverse(e.scrutinee)
                    for (case in e.cases) {
                        traverse(case.body)
                    }
                }

                is com.mezze.language.parsedast.ParsedLambda -> traverse(e.body)

                is com.mezze.language.parsedast.ParsedRecordLit -> {
                    for (entry in e.entries) {
                        when (entry) {
                            is com.mezze.language.parsedast.ParsedRecordEntry.Field -> entry.value?.let { traverse(it) }
                            is com.mezze.language.parsedast.ParsedRecordEntry.Spread -> traverse(entry.expr)
                        }
                    }
                }

                is com.mezze.language.parsedast.ParsedVariantConstruct -> e.value?.let { traverse(it) }
                is com.mezze.language.parsedast.ParsedMemberAccess -> traverse(e.receiver)
                is com.mezze.language.parsedast.ParsedSeqLit -> e.elements.forEach { element ->
                    when (element) {
                        is com.mezze.language.parsedast.ParsedSeqElement.Regular -> traverse(element.expr)
                        is com.mezze.language.parsedast.ParsedSeqElement.Spread -> traverse(element.expr)
                    }
                }
                is com.mezze.language.parsedast.ParsedStringLit -> {
                    for (part in e.parts) {
                        if (part is com.mezze.language.parsedast.ParsedStringPart.Interpolation) traverse(part.expr)
                    }
                }
                is com.mezze.language.parsedast.ParsedPerform -> {
                    // Do NOT traverse into nested perform expressions —
                    // the nested perform has its own handler that catches errors from its inner expression.
                }

                is com.mezze.language.parsedast.ParsedPipe -> {
                    traverse(e.left)
                    traverse(e.right)
                }

                is com.mezze.language.parsedast.ParsedParens -> traverse(e.expr)

                is com.mezze.language.parsedast.ParsedBinaryOp -> { traverse(e.left); traverse(e.right) }
                is com.mezze.language.parsedast.ParsedUnaryOp -> traverse(e.operand)

                is com.mezze.language.parsedast.ParsedIdentifier,
                is com.mezze.language.parsedast.ParsedIntLit,
                is com.mezze.language.parsedast.ParsedNatLit,
                is com.mezze.language.parsedast.ParsedFloatLit,
                is com.mezze.language.parsedast.ParsedFracLit,
                is com.mezze.language.parsedast.ParsedCharLit,
                is com.mezze.language.parsedast.ParsedPipePlaceholder,
                is com.mezze.language.parsedast.ParsedEffectCall -> { }
            }
        }

        traverse(expr)
        return result
    }

    private fun findAssociatedTypeFieldsV2(
        typeAnnotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        associatedTypeNames: Set<String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (typeAnnotation is com.mezze.language.parsedast.ParsedRecordType) {
            for (entry in typeAnnotation.entries) {
                if (entry.type is com.mezze.language.parsedast.ParsedNamedType) {
                    val typeName = (entry.type as com.mezze.language.parsedast.ParsedNamedType).name
                    if (typeName in associatedTypeNames) result[entry.name] = typeName
                }
            }
        }
        return result
    }

    private fun extractAssociatedTypeValuesV2(
        effectDecl: com.mezze.language.parsedast.ParsedEffectDecl,
        operationName: String,
        args: com.mezze.language.parsedast.ParsedExpr
    ): Map<String, MezzeType>? {
        val operation = effectDecl.operations.find { it.name == operationName } ?: return null
        val paramTypeAnnotation = operation.paramType
        val associatedTypeNames = effectDecl.associatedTypes.toSet()
        val fieldToAssocType = findAssociatedTypeFieldsV2(paramTypeAnnotation, associatedTypeNames)

        if (fieldToAssocType.isEmpty()) return null

        if (args !is com.mezze.language.parsedast.ParsedRecordLit) return null

        val result = mutableMapOf<String, MezzeType>()
        for ((fieldName, assocTypeName) in fieldToAssocType) {
            val fieldEntry = args.entries.filterIsInstance<com.mezze.language.parsedast.ParsedRecordEntry.Field>()
                .find { it.name == fieldName }

            val fieldValue = fieldEntry?.value ?: continue

            val fieldType = constructTypeFromParsedExpression(fieldValue) ?: continue

            result[assocTypeName] = fieldType
        }

        return if (result.isNotEmpty()) result else null
    }

    private fun constructTypeFromParsedExpression(expr: com.mezze.language.parsedast.ParsedExpr): MezzeType? {
        return when (expr) {
            is com.mezze.language.parsedast.ParsedRecordLit -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is com.mezze.language.parsedast.ParsedRecordEntry.Field && entry.value != null) {
                        val fieldType = constructTypeFromParsedExpression(entry.value!!)
                        if (fieldType != null) {
                            fields[entry.name] = fieldType
                        }
                    }
                }
                MezzeType.RecordType(fields, null)
            }
            is com.mezze.language.parsedast.ParsedVariantConstruct -> {
                if (expr.constructor == "True" || expr.constructor == "False") {
                    MezzeType.BoolType
                } else {
                    val payloadType = expr.value?.let { constructTypeFromParsedExpression(it) }
                    MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is com.mezze.language.parsedast.ParsedStringLit -> MezzeType.StringType
            is com.mezze.language.parsedast.ParsedIntLit -> MezzeType.IntType
            is com.mezze.language.parsedast.ParsedFloatLit -> MezzeType.FloatType
            is com.mezze.language.parsedast.ParsedNatLit -> MezzeType.NatType
            is com.mezze.language.parsedast.ParsedCharLit -> MezzeType.CharType
            is com.mezze.language.parsedast.ParsedFracLit -> MezzeType.FracType
            is com.mezze.language.parsedast.ParsedIdentifier -> null
            else -> null
        }
    }

    private fun expandParsedTypeAnnotation(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                val typeDecl = typeDeclarations[annotation.name]
                if (typeDecl != null) when (val def = typeDecl.definition) {
                    is com.mezze.language.parsedast.ParsedVariantDef -> MezzeType.VariantType(
                        constructors = def.constructors.associate { it.name to it.dataType?.let { dt -> TypeAnnotationConverter.convert(dt) } },
                        rowVar = if (def.isOpen) RowVar.fresh() else null)
                    is com.mezze.language.parsedast.ParsedRecordDef -> MezzeType.RecordType(fields = def.fields.associate { it.name to TypeAnnotationConverter.convert(it.type) })
                    is com.mezze.language.parsedast.ParsedNewtypeDef -> TypeAnnotationConverter.convert(def.wrappedType)
                } else MezzeType.VarType(TypeVar.freshWithName(annotation.name))
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                TypeAnnotationConverter.convert(annotation)
            }
            else -> TypeAnnotationConverter.convert(annotation)
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

        if (handlerType is MezzeType.BoolType && exprType is MezzeType.VariantType) {
            return PrimitiveTypes.isBoolVariant(exprType)
        }
        if (exprType is MezzeType.BoolType && handlerType is MezzeType.VariantType) {
            return PrimitiveTypes.isBoolVariant(handlerType)
        }

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
