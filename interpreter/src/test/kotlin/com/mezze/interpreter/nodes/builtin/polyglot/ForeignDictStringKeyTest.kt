package com.mezze.interpreter.nodes.builtin.polyglot

import com.oracle.truffle.api.interop.InteropLibrary
import com.oracle.truffle.api.strings.TruffleString
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A guest reading `dict['key']` must find the entry.
 *
 * `ForeignDict` is keyed by whatever the encoder put in — for a record, the
 * field names, which are Java Strings. A guest passes ITS OWN string object,
 * and `LinkedHashMap.containsKey` compares by Java `equals`, so a Python `str`
 * never matched and every subscript raised UnknownKey. Iteration worked (keys
 * come back out as guest strings), which is why a `.to_py {}`'d record looked
 * right — `list(m)` gave `['alpha', 'beta']` — while `m['alpha']` failed.
 *
 * Measured against real GraalPy before and after: `lambda m: str(m['alpha'])`
 * went from a failed call to `1`.
 *
 * `TruffleString` stands in for the guest string here: like a Python `str` it
 * answers the interop string messages while NOT being a Java String, which is
 * the whole of the mismatch — and it needs no guest language installed.
 */
class ForeignDictStringKeyTest {

    private fun dictOf(vararg pairs: Pair<String, Any>): Any =
        ForeignContainers.ForeignDict(LinkedHashMap<Any, Any>().apply { pairs.forEach { put(it.first, it.second) } })

    private fun guestString(s: String): TruffleString =
        TruffleString.fromJavaStringUncached(s, TruffleString.Encoding.UTF_8)

    @Test
    fun `a guest string key reads the entry a Java String key stored`() {
        val interop = InteropLibrary.getUncached()
        val dict = dictOf("alpha" to 1L, "beta" to 2L)
        val key = guestString("alpha")

        // The premise: this key is a string to interop but is NOT a Java String,
        // so a raw containsKey would miss it.
        assertTrue(interop.isString(key), "TruffleString must answer isString")
        assertFalse(("alpha" as Any) == (key as Any), "a TruffleString must not equal a Java String")

        assertTrue(interop.isHashEntryReadable(dict, key), "the entry must be readable")
        assertEquals(1L, interop.readHashValue(dict, key))
    }

    @Test
    fun `a Java String key still works`() {
        val interop = InteropLibrary.getUncached()
        val dict = dictOf("alpha" to 1L)
        assertEquals(1L, interop.readHashValue(dict, "alpha"))
    }

    @Test
    fun `an absent key is still absent`() {
        val interop = InteropLibrary.getUncached()
        val dict = dictOf("alpha" to 1L)
        assertFalse(interop.isHashEntryReadable(dict, guestString("nope")))
    }

    /**
     * Only STRINGS are normalized. A non-string key keeps identity semantics —
     * there is no cross-language equality to appeal to for an arbitrary object.
     */
    @Test
    fun `a non-string key is untouched`() {
        val interop = InteropLibrary.getUncached()
        val dict = dictOf("alpha" to 1L)
        val numeric = ForeignContainers.ForeignDict(LinkedHashMap<Any, Any>().apply { put(7L, "seven") })
        assertEquals("seven", interop.readHashValue(numeric, 7L))
        assertFalse(interop.isHashEntryReadable(dict, 7L))
    }
}
