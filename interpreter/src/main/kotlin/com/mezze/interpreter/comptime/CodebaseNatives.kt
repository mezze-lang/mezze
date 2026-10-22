package com.mezze.interpreter.comptime

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.nodes.builtin.NativeDispatch
import com.mezze.interpreter.runtime.MezzeContext
import com.mezze.interpreter.values.MezzeGuestException
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.language.comptime.CodebaseHost
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.SyntaxHost
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.interop.InteropLibrary
import java.util.function.BiConsumer

/**
 * The natives behind `std::codebase`: each asks the [CodebaseHost] this
 * compile-time evaluation was handed, and turns its answer into a Mezze value.
 *
 * The host lives in the evaluation's own polyglot bindings, put there by
 * [TruffleComptimeEvaluator] — one per context, never global. Where there is
 * none (the runtime; `mez dev` until the interpreter runs inside `mez`) the
 * question fails, loudly: there is no codebase to answer from, and answering
 * with nothing would read as "the codebase has none of these".
 */
object CodebaseNatives {

    /** The polyglot-bindings key the evaluator stores the host under. */
    const val BINDING = "mezze.comptime.codebase"

    /** Where [TruffleComptimeEvaluator.invoke] puts the argument record of the call it makes. */
    const val ARGUMENT_BINDING = "mezze.comptime.argument"

    /** Where [TruffleComptimeEvaluator.invoke] puts the [SyntaxHost] a macro parses through. */
    const val SYNTAX_BINDING = "mezze.comptime.syntax"

    /** Reads one field of the invoked call's argument; only the synthetic entry calls it. */
    const val ARGUMENT_NATIVE = "__native_comptime_argument"

    fun register(put: BiConsumer<String, NativeDispatch.NativeHandler>) {
        put.accept(ARGUMENT_NATIVE) { args, _ ->
            val argument = bound(ARGUMENT_BINDING, ARGUMENT_NATIVE) as? HostValue.Record
                ?: throw IllegalStateException("the value bound as $ARGUMENT_BINDING is not a record")
            val field = str(args, "field")
            answer(argument.fields.firstOrNull { it.first == field }?.second
                ?: throw IllegalStateException("the invoked call's argument has no field `$field`"))
        }
        put.accept("__native_syntax_parse") { args, _ ->
            val host = bound(SYNTAX_BINDING, "__native_syntax_parse") as? SyntaxHost
                ?: throw IllegalStateException("the value bound as $SYNTAX_BINDING is not a SyntaxHost")
            answer(host.parse(
                str(args, "text"), str(args, "file"),
                (args.getField("line") as Long).toInt(), (args.getField("column") as Long).toInt(),
                args.getField("declarations") as Boolean,
            ))
        }

        fun native(name: String, ask: (CodebaseHost, MezzeRecord) -> HostValue) =
            put.accept(name) { args, _ -> answer(ask(host(name), args)) }

        native("__native_codebase_resolve") { h, a -> h.resolve(str(a, "module"), str(a, "name")) }
        native("__native_codebase_info") { h, a -> h.info(str(a, "hash")) }
        native("__native_codebase_decls") { h, a -> h.decls(str(a, "module")) }
        native("__native_codebase_modules") { h, a -> h.modules(str(a, "prefix")) }
        native("__native_codebase_module_doc") { h, a -> h.moduleDoc(str(a, "module")) }
        native("__native_codebase_signature") { h, a -> h.signature(str(a, "hash")) }
        native("__native_codebase_definition") { h, a -> h.definition(str(a, "hash")) }
        native("__native_codebase_source") { h, a -> h.source(str(a, "hash")) }
        native("__native_codebase_impls_of") { h, a -> h.implsOf(str(a, "hash")) }
        native("__native_codebase_impls_for") { h, a -> h.implsFor(str(a, "hash")) }
        native("__native_codebase_effects_of") { h, a -> h.effectsOf(str(a, "hash")) }
        native("__native_codebase_performing") { h, a -> h.performing(str(a, "hash")) }
        native("__native_codebase_requiring") { h, a -> h.requiring(str(a, "hash")) }
        native("__native_codebase_performers_of") { h, a -> h.performersOf(str(a, "hash")) }
        native("__native_codebase_callers") { h, a -> h.callers(str(a, "hash")) }
        native("__native_codebase_callees") { h, a -> h.callees(str(a, "hash")) }
        native("__native_codebase_references") { h, a -> h.references(str(a, "hash")) }
        native("__native_codebase_usages") { h, a -> h.usages(str(a, "hash")) }
        native("__native_codebase_abilities_of") { h, a -> h.abilitiesOf(str(a, "hash")) }
        native("__native_codebase_syntax") { h, a -> h.syntax(str(a, "hash")) }
        native("__native_codebase_module_syntax") { h, a -> h.moduleSyntax(str(a, "module")) }
        native("__native_codebase_occurrences") { h, a ->
            val symbol = (plain(a.getField("symbol")) as HostValue.Opt).value as HostValue.Str?
            h.occurrences(str(a, "kind"), symbol?.value)
        }
        native("__native_codebase_search") { h, a -> h.search(plain(a.getField("q"))) }
        native("__native_codebase_ref") { h, a ->
            val fn = a.getField("of") as? com.mezze.interpreter.values.MezzeFunction
            val hash = fn?.let { MezzeContext.get(null).hashOfFunction(it.callTarget) }
            // A value that is not a top-level function — a lambda, a number — is
            // no declaration: the answer is None, not an error.
            if (hash == null) HostValue.Opt(null) else h.ref(hash.hex)
        }
    }

    /**
     * A Mezze value as plain data: the inverse of [answer], for a question's
     * argument (a `Query`). Only data shapes; anything else is a defect in the
     * caller, since `std::codebase` passes nothing else.
     */
    @TruffleBoundary
    fun plain(value: Any?): HostValue = when (value) {
        is String -> HostValue.Str(value)
        is Long -> HostValue.IntV(value)
        is Boolean -> HostValue.Bool(value)
        is Double -> HostValue.FloatV(value)
        is MezzeRecord -> HostValue.Record(
            (0 until value.fieldCount).map { i -> value.getFieldName(i) to plain(value.getField(i)) }
        )
        is MezzeSeq<*> -> HostValue.Seq((0 until value.size()).map { plain(value.get(it)) })
        is MezzeVariant -> when {
            // By tag, as the runtime itself decides it — never by spelling.
            value.isNone -> HostValue.Opt(null)
            value.isSome -> HostValue.Opt(plain(value.someValue))
            value.payloadLength == 0 -> HostValue.Variant(value.constructorName, null)
            else -> HostValue.Variant(value.constructorName, plain(value.payloadValue) as? HostValue.Record
                ?: throw IllegalStateException("std::codebase native: a variant payload that is not a record"))
        }
        else -> throw IllegalStateException("std::codebase native: cannot pass ${value?.javaClass?.simpleName} to the compiler")
    }

    @TruffleBoundary
    private fun host(native: String): CodebaseHost =
        bound(BINDING, native, "no codebase to answer it") as? CodebaseHost
            ?: throw IllegalStateException("the value bound as $BINDING is not a CodebaseHost")

    /**
     * What this evaluation bound under [key], or a guest error naming [native]
     * when nothing is: the native was called outside the compile-time evaluation
     * that provides it.
     */
    @TruffleBoundary
    private fun bound(key: String, native: String, missing: String = "nothing to answer it"): Any {
        val env = MezzeContext.get(null).env
        // Read through the bindings object rather than `importSymbol`: with no
        // bindings ever created, `importSymbol` dies inside Truffle with a
        // NullPointerException instead of answering "not bound".
        val bound = if (!env.isPolyglotBindingsAccessAllowed) null else {
            val bindings = env.polyglotBindings
            val interop = InteropLibrary.getUncached()
            if (interop.isMemberReadable(bindings, key)) interop.readMember(bindings, key) else null
        }
        if (bound == null) {
            throw MezzeGuestException(
                "`$native` was called with $missing. It is answered only while " +
                    "compiling — a `comptime` block that `mez build` folds, or a macro being " +
                    "expanded; this ran somewhere else.",
            )
        }
        return env.asHostObject(bound)
    }

    private fun str(args: MezzeRecord, field: String): String =
        args.getField(field) as? String
            ?: throw IllegalStateException("std::codebase native: field `$field` is not a Str")

    /** A [HostValue] as the Mezze value it describes. */
    @TruffleBoundary
    fun answer(value: HostValue): Any = when (value) {
        is HostValue.Str -> value.value
        is HostValue.IntV -> value.value
        is HostValue.Bool -> value.value
        is HostValue.FloatV -> value.value
        is HostValue.Seq -> MezzeSeq.fromArray(Array(value.items.size) { answer(value.items[it]) })
        is HostValue.Record -> record(value)
        is HostValue.Variant -> {
            val registry = MezzeVariant.TagRegistry.getGlobal()
            val tag = registry.getOrCreateTag(value.tag)
            val payload = value.payload
            if (payload == null) MezzeVariant.noPayload(registry, tag)
            else MezzeVariant.singlePayload(registry, tag, record(payload))
        }
        is HostValue.Opt -> value.value?.let { MezzeVariant.some(answer(it)) } ?: MezzeVariant.none()
    }

    private fun record(value: HostValue.Record): MezzeRecord =
        MezzeRecord.fromFields(
            Array(value.fields.size) { value.fields[it].first },
            Array(value.fields.size) { answer(value.fields[it].second) },
        )
}
