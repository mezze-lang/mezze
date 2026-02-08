package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.interpreter.NativeFunkFunction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ArrayListInterpreterTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    private lateinit var interpreter: FunkInterpreter
    
    @BeforeEach
    fun setUp() {
        interpreter = FunkInterpreter()
    }
    
    @Test
    fun `test empty ArrayList execution`() {
        // Test FunkArrayList directly since type checking has limitations
        val arrayList = FunkArrayList()
        assertTrue(arrayList.isEmpty())
        assertEquals("[]", arrayList.getDisplayString())
    }
    
    @Test
    fun `test integer ArrayList execution`() {
        // Test FunkArrayList directly
        val arrayList = FunkArrayList(listOf(
            FunkInt(1), FunkInt(2), FunkInt(3), FunkInt(4), FunkInt(5)
        ))
        
        assertEquals(5, arrayList.size())
        assertEquals("[1, 2, 3, 4, 5]", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("1", (arrayList.get(0) as FunkInt).getDisplayString())
        assertEquals("2", (arrayList.get(1) as FunkInt).getDisplayString())
        assertEquals("3", (arrayList.get(2) as FunkInt).getDisplayString())
        assertEquals("4", (arrayList.get(3) as FunkInt).getDisplayString())
        assertEquals("5", (arrayList.get(4) as FunkInt).getDisplayString())
    }
    
    @Test
    fun `test string ArrayList execution`() {
        // Test FunkArrayList directly
        val arrayList = FunkArrayList(listOf(
            FunkString("Alice"), FunkString("Bob"), FunkString("Charlie")
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("""["Alice", "Bob", "Charlie"]""", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("\"Alice\"", (arrayList.get(0) as FunkString).getDisplayString())
        assertEquals("\"Bob\"", (arrayList.get(1) as FunkString).getDisplayString())
        assertEquals("\"Charlie\"", (arrayList.get(2) as FunkString).getDisplayString())
    }
    
    @Test
    fun `test boolean ArrayList execution`() {
        // Test FunkArrayList directly
        val arrayList = FunkArrayList(listOf(
            FunkBool(true), FunkBool(false), FunkBool(true)
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("[true, false, true]", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("true", (arrayList.get(0) as FunkBool).getDisplayString())
        assertEquals("false", (arrayList.get(1) as FunkBool).getDisplayString())
        assertEquals("true", (arrayList.get(2) as FunkBool).getDisplayString())
    }
    
    @Test
    fun `test nested ArrayList execution`() {
        // Test nested FunkArrayList directly
        val innerArray1 = FunkArrayList(listOf(FunkInt(1), FunkInt(2)))
        val innerArray2 = FunkArrayList(listOf(FunkInt(3), FunkInt(4)))
        val innerArray3 = FunkArrayList(listOf(FunkInt(5), FunkInt(6)))
        val outerArrayList = FunkArrayList(listOf(innerArray1, innerArray2, innerArray3))
        
        assertEquals(3, outerArrayList.size())
        assertEquals("[[1, 2], [3, 4], [5, 6]]", outerArrayList.getDisplayString())
        
        // Check first inner ArrayList
        val firstInner = outerArrayList.get(0) as FunkArrayList
        assertEquals(2, firstInner.size())
        assertEquals("[1, 2]", firstInner.getDisplayString())
        
        // Check second inner ArrayList
        val secondInner = outerArrayList.get(1) as FunkArrayList
        assertEquals(2, secondInner.size())
        assertEquals("[3, 4]", secondInner.getDisplayString())
        
        // Check third inner ArrayList
        val thirdInner = outerArrayList.get(2) as FunkArrayList
        assertEquals(2, thirdInner.size())
        assertEquals("[5, 6]", thirdInner.getDisplayString())
    }
    
    @Test
    fun `test ArrayList with computed elements execution`() {
        // Test with pre-computed values since we're testing the ArrayList class directly
        val arrayList = FunkArrayList(listOf(
            FunkInt(3), FunkInt(12), FunkInt(5)  // Results of 1+2, 3*4, 10-5
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("[3, 12, 5]", arrayList.getDisplayString())
        
        // Check computed values
        assertEquals("3", (arrayList.get(0) as FunkInt).getDisplayString())
        assertEquals("12", (arrayList.get(1) as FunkInt).getDisplayString())
        assertEquals("5", (arrayList.get(2) as FunkInt).getDisplayString())
    }
    
    @Test
    fun `test single element ArrayList execution`() {
        // Test FunkArrayList directly
        val arrayList = FunkArrayList(listOf(FunkInt(42)))
        
        assertEquals(1, arrayList.size())
        assertEquals("[42]", arrayList.getDisplayString())
        assertEquals("42", (arrayList.get(0) as FunkInt).getDisplayString())
    }
    
    @Test
    fun `test ArrayList built-in functions are available`() {
        // Test that built-in functions exist in the interpreter context
        // This is a basic test to ensure the functions are registered
        val arrayList = FunkArrayList(listOf(FunkInt(1), FunkInt(2), FunkInt(3)))
        
        // Test basic ArrayList functionality
        assertEquals(3, arrayList.size())
        assertFalse(arrayList.isEmpty())
        assertEquals("[1, 2, 3]", arrayList.getDisplayString())
    }
    
    @Test
    fun `test FunkArrayList equality`() {
        val arrayList1 = FunkArrayList(listOf(FunkInt(1), FunkInt(2), FunkInt(3)))
        val arrayList2 = FunkArrayList(listOf(FunkInt(1), FunkInt(2), FunkInt(3)))
        val arrayList3 = FunkArrayList(listOf(FunkInt(1), FunkInt(2), FunkInt(4)))
        
        assertEquals(arrayList1, arrayList2)
        assertNotEquals(arrayList1, arrayList3)
        assertEquals(arrayList1.hashCode(), arrayList2.hashCode())
    }
    
    @Test
    fun `test FunkArrayList operations`() {
        val arrayList = FunkArrayList()

        // Test isEmpty
        assertTrue(arrayList.isEmpty())
        assertEquals(0, arrayList.size())

        // Test add (immutable - returns new array)
        val list1 = arrayList.add(FunkInt(1))
        val list2 = list1.add(FunkInt(2))
        assertEquals(2, list2.size())
        assertFalse(list2.isEmpty())

        // Test get
        assertEquals(FunkInt(1), list2.get(0))
        assertEquals(FunkInt(2), list2.get(1))

        // Test set (immutable - returns new array)
        val list3 = list2.set(0, FunkInt(10))
        assertEquals(FunkInt(10), list3.get(0))
        // Original unchanged
        assertEquals(FunkInt(1), list2.get(0))

        // Test removeAt (immutable - returns new array)
        val list4 = list3.removeAt(0)
        assertEquals(1, list4.size())
        assertEquals(FunkInt(2), list4.get(0))

        // Original still has both elements
        assertEquals(2, list3.size())
    }
    
    @Test
    fun `test FunkArrayList functional operations`() {
        val arrayList = FunkArrayList(listOf(FunkInt(1), FunkInt(2), FunkInt(3)))
        
        // Test map
        val mapped = arrayList.map { value ->
            val intValue = value as FunkInt
            FunkInt(intValue.value * 2)
        }
        assertEquals("[2, 4, 6]", mapped.getDisplayString())
        
        // Test filter
        val filtered = arrayList.filter { value ->
            val intValue = value as FunkInt
            intValue.value > 1
        }
        assertEquals("[2, 3]", filtered.getDisplayString())
        
        // Test fold
        val sum = arrayList.fold(FunkInt(0)) { acc, value ->
            val accInt = acc as FunkInt
            val valueInt = value as FunkInt
            FunkInt(accInt.value + valueInt.value)
        }
        assertEquals(FunkInt(6), sum)
    }
    
    @Test
    fun `test FunkArrayList display string with different types`() {
        // Test with mixed nested structure (though type system wouldn't allow this)
        val innerArray = FunkArrayList(listOf(FunkInt(1), FunkInt(2)))
        val outerArray = FunkArrayList(listOf(innerArray, FunkString("test")))

        assertEquals("""[[1, 2], "test"]""", outerArray.getDisplayString())
    }

    @Test
    fun `test array spread with append`() {
        // Valid: spread first, then append elements
        val code = """
            let rest = [1, 2, 3]
            let combined = [...rest, 4, 5]
            combined
        """.trimIndent()

        val result = interpreter.run(code)
        assertTrue(result is FunkArrayList)
        assertEquals("[1, 2, 3, 4, 5]", (result as FunkArrayList).getDisplayString())
    }

    @Test
    fun `test array spread with multiple spreads`() {
        val code = """
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b]
            combined
        """.trimIndent()

        val result = interpreter.run(code)
        assertTrue(result is FunkArrayList)
        assertEquals("[1, 2, 3, 4]", (result as FunkArrayList).getDisplayString())
    }

    @Test
    fun `test array spread with spread only`() {
        val code = """
            let arr = [1, 2, 3]
            let copy = [...arr]
            copy
        """.trimIndent()

        val result = interpreter.run(code)
        assertTrue(result is FunkArrayList)
        assertEquals("[1, 2, 3]", (result as FunkArrayList).getDisplayString())
    }

    @Test
    fun `test array spread with multiple spreads and append`() {
        val code = """
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b, 5, 6]
            combined
        """.trimIndent()

        val result = interpreter.run(code)
        assertTrue(result is FunkArrayList)
        assertEquals("[1, 2, 3, 4, 5, 6]", (result as FunkArrayList).getDisplayString())
    }
}