# Runtime Implementation

This document covers the Truffle interpreter changes needed to support polyglot interop.

## Overview

The runtime needs to:
1. Wrap foreign values in `MezzeForeign` type
2. Implement `__native_*` functions for each language
3. Manage GraalVM contexts with proper isolation
4. Handle exceptions and wrap them in `InteropError`
5. Enforce timeouts and memory limits

## MezzeForeign Type

Wraps values from other languages:

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/values/MezzeForeign.kt

@ExportLibrary(InteropLibrary::class)
class MezzeForeign(
    val value: Any,
    val sourceLanguage: String
) : TruffleObject {

    @ExportMessage
    fun hasMembers(): Boolean {
        return InteropLibrary.getUncached().hasMembers(value)
    }

    @ExportMessage
    fun getMembers(includeInternal: Boolean): Any {
        return InteropLibrary.getUncached().getMembers(value, includeInternal)
    }

    @ExportMessage
    fun readMember(member: String): Any {
        return InteropLibrary.getUncached().readMember(value, member)
    }

    @ExportMessage
    fun isMemberInvocable(member: String): Boolean {
        return InteropLibrary.getUncached().isMemberInvocable(value, member)
    }

    @ExportMessage
    fun invokeMember(member: String, vararg args: Any): Any {
        return InteropLibrary.getUncached().invokeMember(value, member, *args)
    }

    @ExportMessage
    fun isExecutable(): Boolean {
        return InteropLibrary.getUncached().isExecutable(value)
    }

    @ExportMessage
    fun execute(vararg args: Any): Any {
        return InteropLibrary.getUncached().execute(value, *args)
    }

    @ExportMessage
    fun hasArrayElements(): Boolean {
        return InteropLibrary.getUncached().hasArrayElements(value)
    }

    @ExportMessage
    fun getArraySize(): Long {
        return InteropLibrary.getUncached().getArraySize(value)
    }

    @ExportMessage
    fun readArrayElement(index: Long): Any {
        return InteropLibrary.getUncached().readArrayElement(value, index)
    }

    @ExportMessage
    fun isNull(): Boolean {
        return InteropLibrary.getUncached().isNull(value)
    }
}
```

## Context Type

Represents a language context:

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/values/MezzeContext.kt

class MezzePolyglotContext(
    val context: Context,
    val language: String,
    val timeout: Long? = null,
    val memoryLimit: Long? = null
) {
    private var closed = false

    fun eval(code: String): Any {
        checkNotClosed()
        return withLimits {
            context.eval(language, code)
        }
    }

    fun getBindings(): Value {
        checkNotClosed()
        return context.getBindings(language)
    }

    fun close() {
        if (!closed) {
            context.close()
            closed = true
        }
    }

    private fun checkNotClosed() {
        if (closed) {
            throw IllegalStateException("Context has been closed")
        }
    }

    private fun <T> withLimits(block: () -> T): T {
        // Apply timeout if set
        if (timeout != null) {
            context.setOption("engine.TimeLimitMS", timeout.toString())
        }

        // Apply memory limit if set
        if (memoryLimit != null) {
            context.setOption("sandbox.MaxHeapMemory", "${memoryLimit}MB")
        }

        return try {
            block()
        } catch (e: PolyglotException) {
            if (e.isCancelled) {
                throw TimeoutException("Operation timed out after ${timeout}ms")
            }
            throw e
        }
    }
}
```

## Context Creation

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/polyglot/ContextFactory.kt

object ContextFactory {

    fun createPythonContext(): MezzePolyglotContext {
        val context = Context.newBuilder("python")
            .allowAllAccess(true)
            .allowIO(true)
            .option("python.PythonPath", ".deps/python/")
            .build()
        return MezzePolyglotContext(context, "python")
    }

    fun createJavaScriptContext(): MezzePolyglotContext {
        val context = Context.newBuilder("js")
            .allowAllAccess(true)
            .allowIO(true)
            .option("js.commonjs-require", "true")
            .option("js.commonjs-require-cwd", ".deps/node/")
            .build()
        return MezzePolyglotContext(context, "js")
    }

    fun createJavaContext(): MezzePolyglotContext {
        val context = Context.newBuilder()
            .allowAllAccess(true)
            .build()
        return MezzePolyglotContext(context, "java")
    }

    fun createRContext(): MezzePolyglotContext {
        val context = Context.newBuilder("R")
            .allowAllAccess(true)
            .allowIO(true)
            .environment("R_LIBS_USER", ".deps/r/")
            .build()
        return MezzePolyglotContext(context, "R")
    }

    fun createRubyContext(): MezzePolyglotContext {
        val context = Context.newBuilder("ruby")
            .allowAllAccess(true)
            .allowIO(true)
            .environment("GEM_HOME", ".deps/ruby/")
            .environment("GEM_PATH", ".deps/ruby/")
            .build()
        return MezzePolyglotContext(context, "ruby")
    }
}
```

## Native Functions

### std/interop/foreign.mz natives

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/interop/ForeignNatives.kt

object ForeignNatives {

    // __native_foreign_from
    fun from(value: Any): MezzeForeign {
        return MezzeForeign(convertToPolyglot(value), "mezze")
    }

    // __native_foreign_to_int
    fun toInt(foreign: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.fitsInLong(foreign.value)) {
                MezzeResult.ok(interop.asLong(foreign.value))
            } else {
                MezzeResult.err(conversionError("Foreign", "Int", "Value does not fit in Int"))
            }
        } catch (e: Exception) {
            MezzeResult.err(conversionError("Foreign", "Int", e.message ?: "Unknown error"))
        }
    }

    // __native_foreign_to_float
    fun toFloat(foreign: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.fitsInDouble(foreign.value)) {
                MezzeResult.ok(interop.asDouble(foreign.value))
            } else {
                MezzeResult.err(conversionError("Foreign", "Float", "Value does not fit in Float"))
            }
        } catch (e: Exception) {
            MezzeResult.err(conversionError("Foreign", "Float", e.message ?: "Unknown error"))
        }
    }

    // __native_foreign_to_string
    fun toString(foreign: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isString(foreign.value)) {
                MezzeResult.ok(interop.asString(foreign.value))
            } else {
                MezzeResult.err(conversionError("Foreign", "String", "Value is not a string"))
            }
        } catch (e: Exception) {
            MezzeResult.err(conversionError("Foreign", "String", e.message ?: "Unknown error"))
        }
    }

    // __native_foreign_to_bool
    fun toBool(foreign: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isBoolean(foreign.value)) {
                MezzeResult.ok(interop.asBoolean(foreign.value))
            } else {
                MezzeResult.err(conversionError("Foreign", "Bool", "Value is not a boolean"))
            }
        } catch (e: Exception) {
            MezzeResult.err(conversionError("Foreign", "Bool", e.message ?: "Unknown error"))
        }
    }

    // __native_foreign_to_array
    fun toArray(foreign: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.hasArrayElements(foreign.value)) {
                val size = interop.getArraySize(foreign.value)
                val elements = (0 until size).map { i ->
                    MezzeForeign(interop.readArrayElement(foreign.value, i), foreign.sourceLanguage)
                }
                MezzeResult.ok(elements)
            } else {
                MezzeResult.err(conversionError("Foreign", "Array", "Value is not array-like"))
            }
        } catch (e: Exception) {
            MezzeResult.err(conversionError("Foreign", "Array", e.message ?: "Unknown error"))
        }
    }

    // __native_foreign_get
    fun get(obj: MezzeForeign, member: String): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.hasMembers(obj.value) && interop.isMemberReadable(obj.value, member)) {
                val result = interop.readMember(obj.value, member)
                MezzeResult.ok(MezzeForeign(result, obj.sourceLanguage))
            } else {
                MezzeResult.err(memberNotFound(obj, member))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(obj.sourceLanguage, e))
        }
    }

    // __native_foreign_set
    fun set(obj: MezzeForeign, member: String, value: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.hasMembers(obj.value) && interop.isMemberWritable(obj.value, member)) {
                interop.writeMember(obj.value, member, value.value)
                MezzeResult.ok(Unit)
            } else {
                MezzeResult.err(memberNotFound(obj, member))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(obj.sourceLanguage, e))
        }
    }

    // __native_foreign_call
    fun call(obj: MezzeForeign, method: String, args: List<MezzeForeign>): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isMemberInvocable(obj.value, method)) {
                val result = interop.invokeMember(obj.value, method, *args.map { it.value }.toTypedArray())
                MezzeResult.ok(MezzeForeign(result, obj.sourceLanguage))
            } else {
                MezzeResult.err(invocationError(obj, method, "Method is not invocable"))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(obj.sourceLanguage, e))
        }
    }

    // __native_foreign_invoke
    fun invoke(func: MezzeForeign, args: List<MezzeForeign>): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isExecutable(func.value)) {
                val result = interop.execute(func.value, *args.map { it.value }.toTypedArray())
                MezzeResult.ok(MezzeForeign(result, func.sourceLanguage))
            } else {
                MezzeResult.err(invocationError(func, "execute", "Value is not executable"))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(func.sourceLanguage, e))
        }
    }

    // __native_foreign_length
    fun length(arr: MezzeForeign): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.hasArrayElements(arr.value)) {
                MezzeResult.ok(interop.getArraySize(arr.value))
            } else {
                MezzeResult.err(conversionError("Foreign", "Array", "Value is not array-like"))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(arr.sourceLanguage, e))
        }
    }

    // __native_foreign_at
    fun at(arr: MezzeForeign, index: Long): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.hasArrayElements(arr.value)) {
                val size = interop.getArraySize(arr.value)
                if (index >= 0 && index < size) {
                    val result = interop.readArrayElement(arr.value, index)
                    MezzeResult.ok(MezzeForeign(result, arr.sourceLanguage))
                } else {
                    MezzeResult.err(runtimeError(arr.sourceLanguage, "Index $index out of bounds (size: $size)"))
                }
            } else {
                MezzeResult.err(conversionError("Foreign", "Array", "Value is not array-like"))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError(arr.sourceLanguage, e))
        }
    }

    // __native_foreign_is_null
    fun isNull(value: MezzeForeign): Boolean {
        return InteropLibrary.getUncached().isNull(value.value)
    }

    // __native_foreign_is_callable
    fun isCallable(value: MezzeForeign): Boolean {
        return InteropLibrary.getUncached().isExecutable(value.value)
    }

    // __native_foreign_is_array
    fun isArray(value: MezzeForeign): Boolean {
        return InteropLibrary.getUncached().hasArrayElements(value.value)
    }

    // __native_foreign_is_object
    fun isObject(value: MezzeForeign): Boolean {
        return InteropLibrary.getUncached().hasMembers(value.value)
    }

    // __native_foreign_type_name
    fun typeName(value: MezzeForeign): String {
        val interop = InteropLibrary.getUncached()
        return if (interop.hasMetaObject(value.value)) {
            val meta = interop.getMetaObject(value.value)
            interop.asString(interop.getMetaSimpleName(meta))
        } else {
            "Unknown"
        }
    }

    // Helper functions
    private fun convertToPolyglot(value: Any): Any {
        return when (value) {
            is Long, is Double, is String, is Boolean -> value
            is List<*> -> value.map { convertToPolyglot(it!!) }
            is Map<*, *> -> ProxyObject.fromMap(value.mapValues { convertToPolyglot(it.value!!) })
            is MezzeRecord -> ProxyObject.fromMap(value.fields.mapValues { convertToPolyglot(it.value) })
            is MezzeFunction -> ProxyExecutable { args ->
                // Convert args back to Mezze values, call function, convert result
                val mezzeArgs = args.map { convertFromPolyglot(it) }
                val result = value.call(mezzeArgs)
                convertToPolyglot(result)
            }
            else -> value
        }
    }

    private fun convertFromPolyglot(value: Any): Any {
        val interop = InteropLibrary.getUncached()
        return when {
            interop.isNull(value) -> MezzeNull
            interop.isBoolean(value) -> interop.asBoolean(value)
            interop.fitsInLong(value) -> interop.asLong(value)
            interop.fitsInDouble(value) -> interop.asDouble(value)
            interop.isString(value) -> interop.asString(value)
            else -> MezzeForeign(value, "polyglot")
        }
    }

    private fun conversionError(from: String, to: String, message: String): MezzeVariant {
        return MezzeVariant("ConversionError", mapOf(
            "from_type" to from,
            "to_type" to to,
            "message" to message
        ))
    }

    private fun memberNotFound(obj: MezzeForeign, member: String): MezzeVariant {
        return MezzeVariant("MemberNotFound", mapOf(
            "object" to obj,
            "member" to member
        ))
    }

    private fun invocationError(obj: MezzeForeign, method: String, message: String): MezzeVariant {
        return MezzeVariant("InvocationError", mapOf(
            "object" to obj,
            "method" to method,
            "message" to message
        ))
    }

    private fun runtimeError(language: String, e: Exception): MezzeVariant {
        val stack = if (e is PolyglotException) {
            e.polyglotStackTrace.joinToString("\n") { it.toString() }
        } else {
            e.stackTraceToString()
        }
        return MezzeVariant("RuntimeError", mapOf(
            "language" to language,
            "message" to (e.message ?: "Unknown error"),
            "stack" to stack
        ))
    }

    private fun runtimeError(language: String, message: String): MezzeVariant {
        return MezzeVariant("RuntimeError", mapOf(
            "language" to language,
            "message" to message,
            "stack" to ""
        ))
    }
}
```

### Python Native Functions

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/interop/PythonNatives.kt

object PythonNatives {

    // __native_python_create_context
    fun createContext(): MezzePolyglotContext {
        return ContextFactory.createPythonContext()
    }

    // __native_python_close_context
    fun closeContext(context: MezzePolyglotContext) {
        context.close()
    }

    // __native_python_eval
    fun eval(context: MezzePolyglotContext, code: String): MezzeResult {
        return try {
            val result = context.eval(code)
            MezzeResult.ok(MezzeForeign(result, "python"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("python", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("python", e))
        }
    }

    // __native_python_eval_with
    fun evalWith(context: MezzePolyglotContext, code: String, bindings: MezzeRecord): MezzeResult {
        return try {
            val pyBindings = context.getBindings()
            for ((name, value) in bindings.fields) {
                pyBindings.putMember(name, convertToPolyglot(value))
            }
            val result = context.eval(code)
            MezzeResult.ok(MezzeForeign(result, "python"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("python", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("python", e))
        }
    }

    // __native_python_bind
    fun bind(context: MezzePolyglotContext, name: String, value: Any): MezzeResult {
        return try {
            val pyBindings = context.getBindings()
            pyBindings.putMember(name, convertToPolyglot(value))
            MezzeResult.ok(Unit)
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("python", e))
        }
    }

    // __native_python_import
    fun importModule(context: MezzePolyglotContext, name: String): MezzeResult {
        return try {
            val result = context.eval("import $name; $name")
            MezzeResult.ok(MezzeForeign(result, "python"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("python", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("python", e))
        }
    }

    private fun handlePolyglotException(language: String, e: PolyglotException): MezzeVariant {
        return when {
            e.isCancelled -> MezzeVariant("Timeout", mapOf(
                "operation" to "eval",
                "limit_ms" to 0L // TODO: get actual limit
            ))
            e.isResourceExhausted -> MezzeVariant("MemoryExceeded", mapOf(
                "operation" to "eval",
                "limit_mb" to 0L // TODO: get actual limit
            ))
            else -> MezzeVariant("RuntimeError", mapOf(
                "language" to language,
                "message" to (e.message ?: "Unknown error"),
                "stack" to e.polyglotStackTrace.joinToString("\n") { it.toString() }
            ))
        }
    }
}
```

### JavaScript Native Functions

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/interop/JavaScriptNatives.kt

object JavaScriptNatives {

    // __native_js_create_context
    fun createContext(): MezzePolyglotContext {
        return ContextFactory.createJavaScriptContext()
    }

    // __native_js_close_context
    fun closeContext(context: MezzePolyglotContext) {
        context.close()
    }

    // __native_js_eval
    fun eval(context: MezzePolyglotContext, code: String): MezzeResult {
        return try {
            val result = context.eval(code)
            MezzeResult.ok(MezzeForeign(result, "js"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("js", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("js", e))
        }
    }

    // __native_js_eval_with
    fun evalWith(context: MezzePolyglotContext, code: String, bindings: MezzeRecord): MezzeResult {
        return try {
            val jsBindings = context.getBindings()
            for ((name, value) in bindings.fields) {
                jsBindings.putMember(name, convertToPolyglot(value))
            }
            val result = context.eval(code)
            MezzeResult.ok(MezzeForeign(result, "js"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("js", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("js", e))
        }
    }

    // __native_js_bind
    fun bind(context: MezzePolyglotContext, name: String, value: Any): MezzeResult {
        return try {
            val jsBindings = context.getBindings()
            jsBindings.putMember(name, convertToPolyglot(value))
            MezzeResult.ok(Unit)
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("js", e))
        }
    }

    // __native_js_require
    fun require(context: MezzePolyglotContext, module: String): MezzeResult {
        return try {
            val result = context.eval("require('$module')")
            MezzeResult.ok(MezzeForeign(result, "js"))
        } catch (e: PolyglotException) {
            MezzeResult.err(handlePolyglotException("js", e))
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("js", e))
        }
    }
}
```

### Java Native Functions

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/interop/JavaNatives.kt

object JavaNatives {

    // __native_java_create_context
    fun createContext(): MezzePolyglotContext {
        return ContextFactory.createJavaContext()
    }

    // __native_java_close_context
    fun closeContext(context: MezzePolyglotContext) {
        context.close()
    }

    // __native_java_import_class
    fun importClass(context: MezzePolyglotContext, name: String): MezzeResult {
        return try {
            val hostAccess = context.context.getBindings("java")
            val clazz = hostAccess.getMember(name)
            if (clazz != null) {
                MezzeResult.ok(MezzeForeign(clazz, "java"))
            } else {
                MezzeResult.err(MezzeVariant("RuntimeError", mapOf(
                    "language" to "java",
                    "message" to "Class not found: $name",
                    "stack" to ""
                )))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("java", e))
        }
    }

    // __native_java_new_instance
    fun newInstance(context: MezzePolyglotContext, clazz: MezzeForeign, args: List<MezzeForeign>): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isInstantiable(clazz.value)) {
                val instance = interop.instantiate(clazz.value, *args.map { it.value }.toTypedArray())
                MezzeResult.ok(MezzeForeign(instance, "java"))
            } else {
                MezzeResult.err(MezzeVariant("RuntimeError", mapOf(
                    "language" to "java",
                    "message" to "Class is not instantiable",
                    "stack" to ""
                )))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("java", e))
        }
    }

    // __native_java_call_static
    fun callStatic(context: MezzePolyglotContext, clazz: MezzeForeign, method: String, args: List<MezzeForeign>): MezzeResult {
        return try {
            val interop = InteropLibrary.getUncached()
            if (interop.isMemberInvocable(clazz.value, method)) {
                val result = interop.invokeMember(clazz.value, method, *args.map { it.value }.toTypedArray())
                MezzeResult.ok(MezzeForeign(result, "java"))
            } else {
                MezzeResult.err(MezzeVariant("InvocationError", mapOf(
                    "object" to clazz,
                    "method" to method,
                    "message" to "Static method not found or not invocable"
                )))
            }
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("java", e))
        }
    }

    // __native_java_bind
    fun bind(context: MezzePolyglotContext, name: String, value: Any): MezzeResult {
        return try {
            val javaBindings = context.getBindings()
            javaBindings.putMember(name, convertToPolyglot(value))
            MezzeResult.ok(Unit)
        } catch (e: Exception) {
            MezzeResult.err(runtimeError("java", e))
        }
    }
}
```

## Exception Handling

All polyglot exceptions are caught and converted to `InteropError` variants:

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/interop/ExceptionHandler.kt

object PolyglotExceptionHandler {

    fun handle(language: String, e: PolyglotException): MezzeVariant {
        return when {
            e.isHostException -> {
                // Exception from Java/Kotlin host code
                val hostException = e.asHostException()
                MezzeVariant("RuntimeError", mapOf(
                    "language" to language,
                    "message" to (hostException.message ?: "Host exception"),
                    "stack" to hostException.stackTraceToString()
                ))
            }
            e.isGuestException -> {
                // Exception from guest language (Python, JS, etc.)
                MezzeVariant("RuntimeError", mapOf(
                    "language" to language,
                    "message" to (e.message ?: "Guest exception"),
                    "stack" to e.polyglotStackTrace.joinToString("\n") { frame ->
                        "${frame.rootName} (${frame.sourceLocation})"
                    }
                ))
            }
            e.isCancelled -> {
                MezzeVariant("Timeout", mapOf(
                    "operation" to "eval",
                    "limit_ms" to 0L
                ))
            }
            e.isResourceExhausted -> {
                MezzeVariant("MemoryExceeded", mapOf(
                    "operation" to "eval",
                    "limit_mb" to 0L
                ))
            }
            e.isInternalError -> {
                MezzeVariant("RuntimeError", mapOf(
                    "language" to language,
                    "message" to "Internal polyglot error: ${e.message}",
                    "stack" to e.stackTraceToString()
                ))
            }
            else -> {
                MezzeVariant("RuntimeError", mapOf(
                    "language" to language,
                    "message" to (e.message ?: "Unknown polyglot error"),
                    "stack" to e.stackTraceToString()
                ))
            }
        }
    }
}
```

## Timeout and Memory Limits

Applied via handler parameters:

```kotlin
// When creating context from handler with limits
fun createContextWithLimits(
    language: String,
    timeout: Long?,
    memoryLimit: Long?
): MezzePolyglotContext {
    val builder = Context.newBuilder(language)
        .allowAllAccess(true)

    if (timeout != null) {
        builder.option("engine.TimeLimitMS", timeout.toString())
    }

    if (memoryLimit != null) {
        builder.option("sandbox.MaxHeapMemory", "${memoryLimit}m")
    }

    return MezzePolyglotContext(builder.build(), language, timeout, memoryLimit)
}
```

## Native Function Registration

Register all native functions in the interpreter:

```kotlin
// interpreter/src/main/kotlin/com/mezze/language/interpreter/stdlib/NativeRegistry.kt

object NativeRegistry {

    private val natives = mutableMapOf<String, (List<Any>) -> Any>()

    init {
        // Foreign operations
        register("__native_foreign_from") { args -> ForeignNatives.from(args[0]) }
        register("__native_foreign_to_int") { args -> ForeignNatives.toInt(args[0] as MezzeForeign) }
        register("__native_foreign_to_float") { args -> ForeignNatives.toFloat(args[0] as MezzeForeign) }
        register("__native_foreign_to_string") { args -> ForeignNatives.toString(args[0] as MezzeForeign) }
        register("__native_foreign_to_bool") { args -> ForeignNatives.toBool(args[0] as MezzeForeign) }
        register("__native_foreign_to_array") { args -> ForeignNatives.toArray(args[0] as MezzeForeign) }
        register("__native_foreign_get") { args -> ForeignNatives.get(args[0] as MezzeForeign, args[1] as String) }
        register("__native_foreign_set") { args -> ForeignNatives.set(args[0] as MezzeForeign, args[1] as String, args[2] as MezzeForeign) }
        register("__native_foreign_call") { args -> ForeignNatives.call(args[0] as MezzeForeign, args[1] as String, args[2] as List<MezzeForeign>) }
        register("__native_foreign_invoke") { args -> ForeignNatives.invoke(args[0] as MezzeForeign, args[1] as List<MezzeForeign>) }
        register("__native_foreign_length") { args -> ForeignNatives.length(args[0] as MezzeForeign) }
        register("__native_foreign_at") { args -> ForeignNatives.at(args[0] as MezzeForeign, args[1] as Long) }
        register("__native_foreign_is_null") { args -> ForeignNatives.isNull(args[0] as MezzeForeign) }
        register("__native_foreign_is_callable") { args -> ForeignNatives.isCallable(args[0] as MezzeForeign) }
        register("__native_foreign_is_array") { args -> ForeignNatives.isArray(args[0] as MezzeForeign) }
        register("__native_foreign_is_object") { args -> ForeignNatives.isObject(args[0] as MezzeForeign) }
        register("__native_foreign_type_name") { args -> ForeignNatives.typeName(args[0] as MezzeForeign) }

        // Python
        register("__native_python_create_context") { _ -> PythonNatives.createContext() }
        register("__native_python_close_context") { args -> PythonNatives.closeContext(args[0] as MezzePolyglotContext) }
        register("__native_python_eval") { args -> PythonNatives.eval(args[0] as MezzePolyglotContext, args[1] as String) }
        register("__native_python_eval_with") { args -> PythonNatives.evalWith(args[0] as MezzePolyglotContext, args[1] as String, args[2] as MezzeRecord) }
        register("__native_python_bind") { args -> PythonNatives.bind(args[0] as MezzePolyglotContext, args[1] as String, args[2]) }
        register("__native_python_import") { args -> PythonNatives.importModule(args[0] as MezzePolyglotContext, args[1] as String) }

        // JavaScript
        register("__native_js_create_context") { _ -> JavaScriptNatives.createContext() }
        register("__native_js_close_context") { args -> JavaScriptNatives.closeContext(args[0] as MezzePolyglotContext) }
        register("__native_js_eval") { args -> JavaScriptNatives.eval(args[0] as MezzePolyglotContext, args[1] as String) }
        register("__native_js_eval_with") { args -> JavaScriptNatives.evalWith(args[0] as MezzePolyglotContext, args[1] as String, args[2] as MezzeRecord) }
        register("__native_js_bind") { args -> JavaScriptNatives.bind(args[0] as MezzePolyglotContext, args[1] as String, args[2]) }
        register("__native_js_require") { args -> JavaScriptNatives.require(args[0] as MezzePolyglotContext, args[1] as String) }

        // Java
        register("__native_java_create_context") { _ -> JavaNatives.createContext() }
        register("__native_java_close_context") { args -> JavaNatives.closeContext(args[0] as MezzePolyglotContext) }
        register("__native_java_import_class") { args -> JavaNatives.importClass(args[0] as MezzePolyglotContext, args[1] as String) }
        register("__native_java_new_instance") { args -> JavaNatives.newInstance(args[0] as MezzePolyglotContext, args[1] as MezzeForeign, args[2] as List<MezzeForeign>) }
        register("__native_java_call_static") { args -> JavaNatives.callStatic(args[0] as MezzePolyglotContext, args[1] as MezzeForeign, args[2] as String, args[3] as List<MezzeForeign>) }
        register("__native_java_bind") { args -> JavaNatives.bind(args[0] as MezzePolyglotContext, args[1] as String, args[2]) }

        // R, Ruby similar...
    }

    fun register(name: String, impl: (List<Any>) -> Any) {
        natives[name] = impl
    }

    fun call(name: String, args: List<Any>): Any {
        val impl = natives[name] ?: throw IllegalArgumentException("Unknown native function: $name")
        return impl(args)
    }
}
```

## Value Conversion Summary

| Mezze Type | Polyglot Type | Notes |
|-----------|---------------|-------|
| Int | Long | Direct mapping |
| Float | Double | Direct mapping |
| String | String | Direct mapping |
| Bool | Boolean | Direct mapping |
| [T] | Array/List | Converted element by element |
| { ... } (Record) | ProxyObject | Fields become members |
| { } -> T (Function) | ProxyExecutable | Callable from foreign code |
| Foreign | TruffleObject | Wrapped foreign value |

## Thread Safety

- Each `MezzePolyglotContext` should be used from a single thread
- Context creation and closing are thread-safe
- Native function calls are synchronized per-context
