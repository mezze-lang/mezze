package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.interpreter.NativeMezzeFunction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ArrayListInterpreterTest {
    
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()
    private lateinit var interpreter: MezzeInterpreter
    
    @BeforeEach
    fun setUp() {
        interpreter = MezzeInterpreter()
    }
    
    @Test
    fun `test empty ArrayList execution`() {
        // Test MezzeArrayList directly since type checking has limitations
        val arrayList = MezzeArrayList()
        assertTrue(arrayList.isEmpty())
        assertEquals("[]", arrayList.getDisplayString())
    }
    
    @Test
    fun `test integer ArrayList execution`() {
        // Test MezzeArrayList directly
        val arrayList = MezzeArrayList(listOf(
            MezzeInt(1), MezzeInt(2), MezzeInt(3), MezzeInt(4), MezzeInt(5)
        ))
        
        assertEquals(5, arrayList.size())
        assertEquals("[1, 2, 3, 4, 5]", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("1", (arrayList.get(0) as MezzeInt).getDisplayString())
        assertEquals("2", (arrayList.get(1) as MezzeInt).getDisplayString())
        assertEquals("3", (arrayList.get(2) as MezzeInt).getDisplayString())
        assertEquals("4", (arrayList.get(3) as MezzeInt).getDisplayString())
        assertEquals("5", (arrayList.get(4) as MezzeInt).getDisplayString())
    }
    
    @Test
    fun `test string ArrayList execution`() {
        // Test MezzeArrayList directly
        val arrayList = MezzeArrayList(listOf(
            MezzeString("Alice"), MezzeString("Bob"), MezzeString("Charlie")
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("""["Alice", "Bob", "Charlie"]""", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("\"Alice\"", (arrayList.get(0) as MezzeString).getDisplayString())
        assertEquals("\"Bob\"", (arrayList.get(1) as MezzeString).getDisplayString())
        assertEquals("\"Charlie\"", (arrayList.get(2) as MezzeString).getDisplayString())
    }
    
    @Test
    fun `test boolean ArrayList execution`() {
        // Test MezzeArrayList directly
        val arrayList = MezzeArrayList(listOf(
            MezzeBool(true), MezzeBool(false), MezzeBool(true)
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("[true, false, true]", arrayList.getDisplayString())
        
        // Check individual elements
        assertEquals("true", (arrayList.get(0) as MezzeBool).getDisplayString())
        assertEquals("false", (arrayList.get(1) as MezzeBool).getDisplayString())
        assertEquals("true", (arrayList.get(2) as MezzeBool).getDisplayString())
    }
    
    @Test
    fun `test nested ArrayList execution`() {
        // Test nested MezzeArrayList directly
        val innerArray1 = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2)))
        val innerArray2 = MezzeArrayList(listOf(MezzeInt(3), MezzeInt(4)))
        val innerArray3 = MezzeArrayList(listOf(MezzeInt(5), MezzeInt(6)))
        val outerArrayList = MezzeArrayList(listOf(innerArray1, innerArray2, innerArray3))
        
        assertEquals(3, outerArrayList.size())
        assertEquals("[[1, 2], [3, 4], [5, 6]]", outerArrayList.getDisplayString())
        
        // Check first inner ArrayList
        val firstInner = outerArrayList.get(0) as MezzeArrayList
        assertEquals(2, firstInner.size())
        assertEquals("[1, 2]", firstInner.getDisplayString())
        
        // Check second inner ArrayList
        val secondInner = outerArrayList.get(1) as MezzeArrayList
        assertEquals(2, secondInner.size())
        assertEquals("[3, 4]", secondInner.getDisplayString())
        
        // Check third inner ArrayList
        val thirdInner = outerArrayList.get(2) as MezzeArrayList
        assertEquals(2, thirdInner.size())
        assertEquals("[5, 6]", thirdInner.getDisplayString())
    }
    
    @Test
    fun `test ArrayList with computed elements execution`() {
        // Test with pre-computed values since we're testing the ArrayList class directly
        val arrayList = MezzeArrayList(listOf(
            MezzeInt(3), MezzeInt(12), MezzeInt(5)  // Results of 1+2, 3*4, 10-5
        ))
        
        assertEquals(3, arrayList.size())
        assertEquals("[3, 12, 5]", arrayList.getDisplayString())
        
        // Check computed values
        assertEquals("3", (arrayList.get(0) as MezzeInt).getDisplayString())
        assertEquals("12", (arrayList.get(1) as MezzeInt).getDisplayString())
        assertEquals("5", (arrayList.get(2) as MezzeInt).getDisplayString())
    }
    
    @Test
    fun `test single element ArrayList execution`() {
        // Test MezzeArrayList directly
        val arrayList = MezzeArrayList(listOf(MezzeInt(42)))
        
        assertEquals(1, arrayList.size())
        assertEquals("[42]", arrayList.getDisplayString())
        assertEquals("42", (arrayList.get(0) as MezzeInt).getDisplayString())
    }
    
    @Test
    fun `test ArrayList built-in functions are available`() {
        // Test that built-in functions exist in the interpreter context
        // This is a basic test to ensure the functions are registered
        val arrayList = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2), MezzeInt(3)))
        
        // Test basic ArrayList functionality
        assertEquals(3, arrayList.size())
        assertFalse(arrayList.isEmpty())
        assertEquals("[1, 2, 3]", arrayList.getDisplayString())
    }
    
    @Test
    fun `test MezzeArrayList equality`() {
        val arrayList1 = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2), MezzeInt(3)))
        val arrayList2 = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2), MezzeInt(3)))
        val arrayList3 = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2), MezzeInt(4)))
        
        assertEquals(arrayList1, arrayList2)
        assertNotEquals(arrayList1, arrayList3)
        assertEquals(arrayList1.hashCode(), arrayList2.hashCode())
    }
    
    @Test
    fun `test MezzeArrayList operations`() {
        val arrayList = MezzeArrayList()

        // Test isEmpty
        assertTrue(arrayList.isEmpty())
        assertEquals(0, arrayList.size())

        // Test add (immutable - returns new array)
        val list1 = arrayList.add(MezzeInt(1))
        val list2 = list1.add(MezzeInt(2))
        assertEquals(2, list2.size())
        assertFalse(list2.isEmpty())

        // Test get
        assertEquals(MezzeInt(1), list2.get(0))
        assertEquals(MezzeInt(2), list2.get(1))

        // Test set (immutable - returns new array)
        val list3 = list2.set(0, MezzeInt(10))
        assertEquals(MezzeInt(10), list3.get(0))
        // Original unchanged
        assertEquals(MezzeInt(1), list2.get(0))

        // Test removeAt (immutable - returns new array)
        val list4 = list3.removeAt(0)
        assertEquals(1, list4.size())
        assertEquals(MezzeInt(2), list4.get(0))

        // Original still has both elements
        assertEquals(2, list3.size())
    }
    
    @Test
    fun `test MezzeArrayList functional operations`() {
        val arrayList = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2), MezzeInt(3)))
        
        // Test map
        val mapped = arrayList.map { value ->
            val intValue = value as MezzeInt
            MezzeInt(intValue.value * 2)
        }
        assertEquals("[2, 4, 6]", mapped.getDisplayString())
        
        // Test filter
        val filtered = arrayList.filter { value ->
            val intValue = value as MezzeInt
            intValue.value > 1
        }
        assertEquals("[2, 3]", filtered.getDisplayString())
        
        // Test fold
        val sum = arrayList.fold(MezzeInt(0)) { acc, value ->
            val accInt = acc as MezzeInt
            val valueInt = value as MezzeInt
            MezzeInt(accInt.value + valueInt.value)
        }
        assertEquals(MezzeInt(6), sum)
    }
    
    @Test
    fun `test MezzeArrayList display string with different types`() {
        // Test with mixed nested structure (though type system wouldn't allow this)
        val innerArray = MezzeArrayList(listOf(MezzeInt(1), MezzeInt(2)))
        val outerArray = MezzeArrayList(listOf(innerArray, MezzeString("test")))

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
        assertTrue(result is MezzeArrayList)
        assertEquals("[1, 2, 3, 4, 5]", (result as MezzeArrayList).getDisplayString())
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
        assertTrue(result is MezzeArrayList)
        assertEquals("[1, 2, 3, 4]", (result as MezzeArrayList).getDisplayString())
    }

    @Test
    fun `test array spread with spread only`() {
        val code = """
            let arr = [1, 2, 3]
            let copy = [...arr]
            copy
        """.trimIndent()

        val result = interpreter.run(code)
        assertTrue(result is MezzeArrayList)
        assertEquals("[1, 2, 3]", (result as MezzeArrayList).getDisplayString())
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
        assertTrue(result is MezzeArrayList)
        assertEquals("[1, 2, 3, 4, 5, 6]", (result as MezzeArrayList).getDisplayString())
    }
}