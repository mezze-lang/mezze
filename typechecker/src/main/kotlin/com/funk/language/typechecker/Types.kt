package com.funk.language.typechecker

/**
 * Core type system for Funk language with row polymorphism and effects
 */

// Type variables for generics and row polymorphism
data class TypeVar(val name: String, val level: Int = 0, val id: Int = nextId()) {
    companion object {
        private var counter = 0
        private fun nextId() = counter++
        
        fun fresh(prefix: String = "t", level: Int = 0): TypeVar {
            val name = "$prefix${counter}"
            counter++
            return TypeVar(name, level, counter - 1)
        }
    }
}

data class RowVar(val name: String, val id: Int = nextId()) {
    companion object {
        private var counter = 0
        private fun nextId() = counter++
        
        fun fresh(prefix: String = "r") = RowVar("$prefix${nextId()}")
    }
}

// Core type system
sealed interface FunkType {
    // Primitive types
    object IntType : FunkType
    object FloatType : FunkType 
    object StringType : FunkType
    object BooleanType : FunkType
    
    // Type variables
    data class VarType(val variable: TypeVar) : FunkType
    
    // Record types with row polymorphism
    data class RecordType(
        val fields: Map<String, FunkType>,
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null
    }
    
    // Variant types with row polymorphism  
    data class VariantType(
        val constructors: Map<String, FunkType?>, // null = no data, FunkType = constructor data
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null
    }
    
    // Function types with effects
    data class FunctionType(
        val paramType: FunkType,
        val returnType: FunkType, 
        val effects: Set<Effect> = emptySet()
    ) : FunkType
    
    // Higher-kinded types
    data class TypeConstructor(
        val name: String,
        val args: List<FunkType>
    ) : FunkType
    
    // Type aliases
    data class TypeAlias(
        val name: String,
        val type: FunkType
    ) : FunkType
}

// Effect system
data class Effect(val name: String, val hash: String? = null)

// Row constraints for polymorphism
sealed interface RowConstraint {
    data class HasField(val field: String, val type: FunkType) : RowConstraint
    data class LacksField(val field: String) : RowConstraint
    data class HasConstructor(val constructor: String, val type: FunkType?) : RowConstraint
    data class LacksConstructor(val constructor: String) : RowConstraint
}

// Type scheme for polymorphism
data class TypeScheme(
    val typeVars: Set<TypeVar>,
    val rowVars: Set<RowVar>,
    val constraints: Set<RowConstraint>,
    val type: FunkType
) {
    companion object {
        fun monomorphic(type: FunkType) = TypeScheme(emptySet(), emptySet(), emptySet(), type)
    }
}

// Content hash for addressability
data class ContentHash(val hash: String) {
    companion object {
        fun compute(type: FunkType): ContentHash {
            // Simplified hash computation - in real implementation would use proper cryptographic hash
            return ContentHash("hash_${type.hashCode()}")
        }
    }
}


// Type environment
data class TypeEnvironment(
    val bindings: Map<String, TypeScheme> = emptyMap(),
    val typeAliases: Map<String, FunkType> = emptyMap(),
    val effects: Map<String, Effect> = emptyMap()
) {
    fun extend(name: String, scheme: TypeScheme) = 
        copy(bindings = bindings + (name to scheme))
    
    fun extendType(name: String, type: FunkType) = 
        extend(name, TypeScheme.monomorphic(type))
    
    fun lookup(name: String): TypeScheme? = bindings[name]
    
    fun lookupType(name: String): FunkType? = typeAliases[name]
    
    fun lookupEffect(name: String): Effect? = effects[name]
}

// Type checking context
data class TypeContext(
    val env: TypeEnvironment = TypeEnvironment(),
    val substitution: Substitution = Substitution.empty(),
    val constraints: MutableSet<Constraint> = mutableSetOf(),
    val currentFunction: String? = null // Added for richer error context
)

// Substitution for type variables
data class Substitution(val mapping: Map<TypeVar, FunkType> = emptyMap()) {
    companion object {
        fun empty() = Substitution()
        fun single(typeVar: TypeVar, type: FunkType) = Substitution(mapOf(typeVar to type))
    }
    
    fun extend(typeVar: TypeVar, type: FunkType): Substitution {
        val newMapping = mapping.toMutableMap()
        newMapping[typeVar] = apply(type)
        return Substitution(newMapping)
    }
    
    fun apply(type: FunkType): FunkType = when (type) {
        is FunkType.VarType -> mapping[type.variable] ?: type
        is FunkType.RecordType -> FunkType.RecordType(
            type.fields.mapValues { apply(it.value) },
            type.rowVar
        )
        is FunkType.VariantType -> FunkType.VariantType(
            type.constructors.mapValues { it.value?.let { apply(it) } },
            type.rowVar
        )
        is FunkType.FunctionType -> FunkType.FunctionType(
            apply(type.paramType),
            apply(type.returnType),
            type.effects
        )
        is FunkType.TypeConstructor -> FunkType.TypeConstructor(
            type.name,
            type.args.map { apply(it) }
        )
        is FunkType.TypeAlias -> FunkType.TypeAlias(type.name, apply(type.type))
        else -> type
    }
    
    fun compose(other: Substitution): Substitution {
        val newMapping = mapping.mapValues { other.apply(it.value) } + other.mapping
        return Substitution(newMapping)
    }
}

// Constraints for type inference
sealed interface Constraint {
    data class Equality(val type1: FunkType, val type2: FunkType) : Constraint
    data class RowUnification(val row1: RowVar, val row2: RowVar, val fields: Map<String, FunkType>) : Constraint
    data class HasField(val recordType: FunkType, val field: String, val fieldType: FunkType) : Constraint
    data class HasConstructor(val variantType: FunkType, val constructor: String, val constructorType: FunkType?) : Constraint
    data class PartialApplication(val functionType: FunkType, val argumentType: FunkType, val resultType: FunkType) : Constraint
}

// Helper functions for creating InferenceResult instances
object InferenceResultHelper {
    fun simple(type: FunkType) = InferenceResult(type, emptySet())
    fun withConstraints(type: FunkType, vararg constraints: Constraint) = 
        InferenceResult(type, constraints.toSet())
}

fun InferenceResult.addConstraint(constraint: Constraint) = 
    InferenceResult(type, constraints + constraint)

fun InferenceResult.addConstraints(newConstraints: Set<Constraint>) = 
    InferenceResult(type, constraints + newConstraints)

// Type checking errors
sealed class TypeError(message: String) : Exception(message) {
    class UnificationError(val type1: FunkType, val type2: FunkType) : 
        TypeError("Cannot unify types: $type1 and $type2")
    
    class UnboundVariable(val name: String) : 
        TypeError("Unbound variable: $name")
    
    class FieldNotFound(val recordType: FunkType, val field: String) : 
        TypeError("Field '$field' not found in record type: $recordType")
    
    class ConstructorNotFound(val variantType: FunkType, val constructor: String) : 
        TypeError("Constructor '$constructor' not found in variant type: $variantType")
    
    class EffectNotFound(val effect: String) : 
        TypeError("Effect '$effect' not found")
    
    class OccursCheck(val typeVar: TypeVar, val type: FunkType) : 
        TypeError("Occurs check failed: $typeVar occurs in $type")
    
    class RowConstraintViolation(val constraint: RowConstraint, val type: FunkType) : 
        TypeError("Row constraint violation: $constraint not satisfied by $type")
    
    class PartialApplicationError(val expected: FunkType, val provided: FunkType) : 
        TypeError("Partial application error: expected $expected, provided $provided")
}
