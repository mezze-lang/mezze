package com.mezze.language.typechecker.types

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * DSL for registering native function type signatures.
 *
 * Usage:
 *   natives(context) {
 *       fn("__native_seq_size") { a ->
 *           param("xs" to seq(a))
 *           returns(int)
 *       }
 *       fn("__native_seq_get") { a ->
 *           param("xs" to seq(a), "i" to int)
 *           returns(option(a))
 *       }
 *   }
 */
class NativeTypeBuilder(private val context: MutableMap<String, MezzeType>) {

    fun const(name: String, type: MezzeType) {
        context[name] = type
    }

    fun fn(name: String, build: FnScope.() -> Unit) {
        val scope = FnScope()
        scope.build()
        context[name] = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(scope.params),
            returnType = scope.returnType ?: MezzeType.RecordType(emptyMap()),
            effects = scope.effects
        )
    }

    class FnScope {
        var params: Map<String, MezzeType> = emptyMap()
        var returnType: MezzeType? = null
        var effects: MezzeType.EffectRow? = null

        fun param(vararg fields: Pair<String, MezzeType>) {
            params = fields.toMap()
        }

        fun returns(type: MezzeType) {
            returnType = type
        }

        fun effects(vararg effectNames: String) {
            effects = MezzeType.EffectRow.simple(effectNames.toSet())
        }
    }
}

fun natives(context: MutableMap<String, MezzeType>, build: NativeTypeBuilder.() -> Unit) {
    NativeTypeBuilder(context).build()
}

// Common type constructors
val int = MezzeType.IntType
val float = MezzeType.FloatType
val str = MezzeType.StringType
val bool = MezzeType.BoolType
val nat = MezzeType.NatType
val char = MezzeType.CharType
val frac = MezzeType.FracType
val bytes = MezzeType.BytesType
val byteBuffer = MezzeType.ByteBufferType
val ordering = MezzeType.OrderingType
val unit = MezzeType.RecordType(emptyMap())

fun tvar(name: String) = MezzeType.VarType(TypeVar(name))
fun seq(elem: MezzeType) = MezzeType.SeqType(elem)
fun list(elem: MezzeType) = MezzeType.ListType(elem)
fun set(elem: MezzeType) = MezzeType.SetType(elem)
fun sortedSet(elem: MezzeType) = MezzeType.SortedSetType(elem)
fun map(k: MezzeType, v: MezzeType) = MezzeType.MapType(k, v)
fun sortedMap(k: MezzeType, v: MezzeType) = MezzeType.SortedMapType(k, v)
fun option(inner: MezzeType) = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(inner))
fun result(ok: MezzeType, err: MezzeType) = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(ok, err))
fun rec(vararg fields: Pair<String, MezzeType>) = MezzeType.RecordType(fields.toMap())
