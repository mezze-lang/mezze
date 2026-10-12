package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Unit-pins the inherent-method registry's hash-keying (finding #3). Two impls of
 * the SAME method name on two SAME-named types from different modules must both
 * survive registration and be disambiguable by the target type's CONTENT HASH —
 * previously they collapsed onto one `"Name::method"` key (last-writer-wins →
 * silent mis-dispatch). See project_classparam_runtime_dispatch_tuple.
 *
 * A source-level cross-module test isn't feasible: newtype constructors are
 * module-private and record types dispatch structurally (bypassing this registry),
 * so this pins the registry mechanism the fix actually changed.
 */
class InherentMethodHashRegistryTest {

    private fun info(typeHash: Hash?, ret: MezzeType) = TypingContext.InherentMethodInfo(
        targetTypeName = "Widget",         // deliberately the SAME name for both
        methodName = "describe",
        methodType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(mapOf("it" to MezzeType.VarType(TypeVar.fresh()))),
            returnType = ret
        ),
        implContentHash = if (ret == MezzeType.IntType) Hash("1111") else Hash("2222"),
        targetTypeHash = typeHash,
    )

    @Test
    fun `two same-named types with distinct hashes both register and disambiguate by hash`() {
        val hashA = Hash("aaaa")
        val hashB = Hash("bbbb")
        val ctx = TypingContext.empty
            .extendWithAnonymousMethod(info(hashA, MezzeType.IntType))
            .extendWithAnonymousMethod(info(hashB, MezzeType.StringType))

        // Both survive registration (no last-writer-wins collapse).
        val byName = ctx.findAnonymousMethodsByName("describe")
        assertEquals(2, byName.size, "both same-named impls must survive; got ${byName.map { it.targetTypeHash }}")

        // Each impl's own method is found by the impl's identity.
        val a = ctx.findMethodOfImpl(Hash("1111"), "describe")
        val b = ctx.findMethodOfImpl(Hash("2222"), "describe")
        assertNotNull(a); assertNotNull(b)
        assertEquals(MezzeType.IntType, (a!!.methodType as MezzeType.FunctionType).returnType, "hashA → Int-returning impl")
        assertEquals(MezzeType.StringType, (b!!.methodType as MezzeType.FunctionType).returnType, "hashB → Str-returning impl")
    }

    @Test
    fun `an impl hash with no method finds nothing`() {
        val ctx = TypingContext.empty.extendWithAnonymousMethod(info(Hash("aaaa"), MezzeType.IntType))
        assertNull(ctx.findMethodOfImpl(Hash("cccc"), "describe"))
    }

    @Test
    fun `two impls of one target hash both survive`() {
        // `impl Box Int` and `impl Box Str`: one constructor, so one target hash.
        // Keyed by target, the second overwrote the first.
        val box = Hash("aaaa")
        val ctx = TypingContext.empty
            .extendWithAnonymousMethod(info(box, MezzeType.IntType))
            .extendWithAnonymousMethod(info(box, MezzeType.StringType))
        assertEquals(2, ctx.findAnonymousMethodsByName("describe").size)
        assertEquals(MezzeType.IntType,
            (ctx.findMethodOfImpl(Hash("1111"), "describe")!!.methodType as MezzeType.FunctionType).returnType)
    }

    @Test
    fun `hashless builtin-style impl still registers and is found by name`() {
        // Builtins have no decl hash — the name path must still work.
        val ctx = TypingContext.empty.extendWithAnonymousMethod(info(null, MezzeType.IntType))
        assertEquals(1, ctx.findAnonymousMethodsByName("describe").size)
    }
}
