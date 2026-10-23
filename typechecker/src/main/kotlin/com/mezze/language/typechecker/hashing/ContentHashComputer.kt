package com.mezze.language.typechecker.hashing

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHash

object ContentHashComputer {

    // Keyed on the TYPE, not System.identityHashCode(type). The rendering is a
    // function of the type's structure, so equal types belong in one entry;
    // identity hashes collide between distinct live objects and are reused after
    // collection, which hands back a name belonging to an unrelated type.
    private val typeStringCache = object : LinkedHashMap<MezzeType, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MezzeType, String>?) = size > 1000
    }

    /**
     * A short, LOSSY display/key name for a type.
     *
     * A record collapses to `Record_<fieldnames>` and a variant to
     * `Variant_<ctornames>`, so `{ x: Int }` and `{ x: Str }` render identically
     * and every function type is just `Function`. NEVER decide whether two types
     * are the same by comparing these — compare the types.
     */
    fun mezzeTypeToString(type: MezzeType): String {
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> type.variable.name
            is MezzeType.FunctionType -> "Function"
            else -> mezzeTypeToStringCached(type)
        }
    }

        private fun mezzeTypeToStringCached(type: MezzeType): String {
        synchronized(typeStringCache) { typeStringCache[type] }?.let { return it }

        val result = when (type) {
            is MezzeType.TypeApplication -> {
                val args = type.args.joinToString("_") { mezzeTypeToString(it) }
                if (args.isEmpty()) type.constructor else "${type.constructor}_$args"
            }
            is MezzeType.RecordType -> {
                val fieldNames = type.fields.keys.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is MezzeType.VariantType -> {
                val constructorNames = type.constructors.keys.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            else -> type.toString()
        }

        synchronized(typeStringCache) { typeStringCache[type] = result }
        return result
    }

    }
