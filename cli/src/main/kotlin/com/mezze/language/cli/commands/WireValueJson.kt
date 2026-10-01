package com.mezze.language.cli.commands

import com.mezze.wire.EvalError
import com.mezze.wire.WireValue

/**
 * One JSON encoding of a [WireValue] tree, for every command that emits one.
 *
 * Extracted at the SECOND consumer rather than the third, because the cost of a
 * second copy here is not duplicated code — it is two commands disagreeing about
 * what `--json` means. `mez sample` first stringified its values
 * (`"args": "{ quantity = 0 }"`), which would have obliged a consumer to parse
 * Mezze's rendering by hand while `mez eval` handed it the tree. That is a worse
 * outcome than the duplication, and the kind of drift API-1 exists to stop.
 *
 * The TREE, not its rendering: a caller asking for JSON wants the structure. [text]
 * is included alongside so a consumer that only wants to display something does not
 * have to fold the tree itself.
 */
object WireValueJson {

    /** The tree, as a JSON object. */
    fun tree(value: WireValue): String = when (value) {
        is WireValue.Scalar ->
            "{\"node\":\"scalar\",\"kind\":${quote(value.kind)},\"text\":${quote(value.text)}}"

        is WireValue.Newtype ->
            "{\"node\":\"newtype\",\"name\":${quote(value.name)},\"inner\":${tree(value.inner)}}"

        is WireValue.Record -> buildString {
            append("{\"node\":\"record\",\"fields\":[")
            value.fields.forEachIndexed { i, f ->
                if (i > 0) append(",")
                append("{\"name\":").append(quote(f.name))
                append(",\"value\":").append(tree(f.value)).append("}")
            }
            append("]}")
        }

        is WireValue.Variant -> buildString {
            append("{\"node\":\"variant\",\"constructor\":").append(quote(value.constructor))
            value.payload?.let { append(",\"payload\":").append(tree(it)) }
            append("}")
        }

        is WireValue.Sequence -> buildString {
            append("{\"node\":\"seq\",\"kind\":").append(quote(value.kind))
            append(",\"omitted\":").append(value.omitted)
            append(",\"items\":[")
            value.items.forEachIndexed { i, item ->
                if (i > 0) append(",")
                append(tree(item))
            }
            append("]}")
        }

        is WireValue.Mapping -> buildString {
            append("{\"node\":\"map\",\"kind\":").append(quote(value.kind))
            append(",\"omitted\":").append(value.omitted)
            append(",\"entries\":[")
            value.entries.forEachIndexed { i, e ->
                if (i > 0) append(",")
                append("{\"key\":").append(tree(e.key))
                append(",\"value\":").append(tree(e.value)).append("}")
            }
            append("]}")
        }

        is WireValue.Fn ->
            "{\"node\":\"fn\"" + (value.name?.let { ",\"name\":${quote(it)}" } ?: "") + "}"

        // `path` is what an EXPAND names, so a JSON consumer can ask for this branch
        // specifically rather than re-running at a larger depth.
        is WireValue.Elided ->
            "{\"node\":\"elided\",\"path\":${quote(value.path)},\"hint\":${quote(value.hint)}}"
    }

    /** A failed run, as a JSON object. Shared so `ok` means the same thing everywhere. */
    fun error(error: EvalError): String = buildString {
        append("{\"ok\":false,\"kind\":").append(quote(error.kind))
        append(",\"message\":").append(quote(error.detail.message))
        append(",\"stack\":[")
        error.detail.stack.forEachIndexed { i, f ->
            if (i > 0) append(",")
            append("{\"function\":").append(quote(f.function))
            f.file?.let { append(",\"file\":").append(quote(it)) }
            f.line?.let { append(",\"line\":").append(it) }
            append("}")
        }
        append("]}")
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }
}
