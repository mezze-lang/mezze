package com.mezze.interpreter.v2;

import com.mezze.language.parser.MezzeParser;
import com.mezze.language.typechecker.ModuleResolver;
import com.mezze.language.typechecker.TypeCheckError;
import com.mezze.language.typechecker.TypeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for string interpolation.
 * Uses V2 unboxed values directly.
 */
class StringInterpolationIntegrationTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
    }

    @Test
    void simpleStringInterpolation() {
        Object result = MezzeTestHelper.run("""
            let name = "World"
            "Hello $(name)!"
            """);
        assertEquals("Hello World!", result);
    }

    @Test
    void multipleInterpolations() {
        Object result = MezzeTestHelper.run("""
            let first = "Alice"
            let last = "Smith"
            "$(first) $(last)"
            """);
        assertEquals("Alice Smith", result);
    }

    @Test
    void interpolationOnlyNoSurroundingText() {
        Object result = MezzeTestHelper.run("""
            let value = "test"
            "$(value)"
            """);
        assertEquals("test", result);
    }

    @Test
    void interpolationWithFunctionCallReturningString() {
        Object result = MezzeTestHelper.run("""
            let intToString = {n: Int} -> if n == 0 then "0" else if n == 1 then "1" else if n == 42 then "42" else "other"
            "The answer is $(intToString{n = 42})"
            """);
        assertEquals("The answer is 42", result);
    }

    @Test
    void plainStringWithoutInterpolation() {
        Object result = MezzeTestHelper.run("""
            "Hello World"
            """);
        assertEquals("Hello World", result);
    }

    @Test
    void escapedInterpolationIsLiteral() {
        Object result = MezzeTestHelper.run("""
            "Use \\$(var) for interpolation"
            """);
        assertEquals("Use $(var) for interpolation", result);
    }

    @Test
    void multilineStringWithInterpolation() {
        Object result = MezzeTestHelper.run("""
            let a = "first"
            let b = "second"
            "Line 1: $(a)
            Line 2: $(b)"
            """);
        assertTrue(result.toString().contains("first"));
        assertTrue(result.toString().contains("second"));
    }

    @Test
    void interpolationWithRecordFieldAccess() {
        Object result = MezzeTestHelper.run("""
            let person = {name = "Bob", title = "Engineer"}
            "$(person.name) is an $(person.title)"
            """);
        assertEquals("Bob is an Engineer", result);
    }

    @Test
    void nestedInterpolatedStrings() {
        Object result = MezzeTestHelper.run("""
            let inner = "nested"
            let outer = "Value: $(inner)"
            "Result: $(outer)"
            """);
        assertEquals("Result: Value: nested", result);
    }

    @Test
    void emptyStringBetweenInterpolations() {
        Object result = MezzeTestHelper.run("""
            let a = "X"
            let b = "Y"
            "$(a)$(b)"
            """);
        assertEquals("XY", result);
    }

    @Test
    void nestedStringWithInterpolationInsideInterpolation() {
        Object result = MezzeTestHelper.run("""
            let name = "World"
            "outer: $("inner $(name)")"
            """);
        assertEquals("outer: inner World", result);
    }

    @Test
    void emptyString() {
        Object result = MezzeTestHelper.run("""
            ""
            """);
        assertEquals("", result);
    }

    @Test
    void consecutiveInterpolations() {
        Object result = MezzeTestHelper.run("""
            let a = "A"
            let b = "B"
            let c = "C"
            "$(a)$(b)$(c)"
            """);
        assertEquals("ABC", result);
    }

    @Test
    void interpolationWithIfExpression() {
        Object result = MezzeTestHelper.run("""
            "$(if True then "yes" else "no")"
            """);
        assertEquals("yes", result);
    }

    @Test
    void interpolationWithConditionalExpression() {
        Object result = MezzeTestHelper.run("""
            let flag = True
            let label = if flag then "enabled" else "disabled"
            "Status: $(label)"
            """);
        assertEquals("Status: enabled", result);
    }

    @Test
    void escapeSequencesMixedWithInterpolation() {
        Object result = MezzeTestHelper.run("""
            let middle = "MID"
            "start\\n$(middle)\\tend"
            """);
        assertEquals("start\nMID\tend", result);
    }

    @Test
    void interpolationAtStringBoundariesStart() {
        Object result = MezzeTestHelper.run("""
            let prefix = "START"
            "$(prefix) middle"
            """);
        assertEquals("START middle", result);
    }

    @Test
    void interpolationAtStringBoundariesEnd() {
        Object result = MezzeTestHelper.run("""
            let suffix = "END"
            "middle $(suffix)"
            """);
        assertEquals("middle END", result);
    }

    @Test
    void deeplyNestedInterpolation() {
        Object result = MezzeTestHelper.run("""
            let a = "A"
            let level1 = "1$(a)1"
            let level2 = "2$(level1)2"
            "3$(level2)3"
            """);
        assertEquals("321A123", result);
    }

    @Test
    void multipleStringInterpolationsProduceCorrectResult() {
        Object result = MezzeTestHelper.run("""
            let a = "Hello"
            let b = "World"
            "$(a) $(b)"
            """);
        assertEquals("Hello World", result);
    }

    @Test
    void lambdaReturningStringInInterpolation() {
        Object result = MezzeTestHelper.run("""
            let greet = {name: String} -> "Hi $(name)"
            "Message: $(greet{name = "Alice"})"
            """);
        assertEquals("Message: Hi Alice", result);
    }

    // Type error tests

    @Test
    void nonStringInterpolationFailsAtTypecheck() {
        MezzeParser parser = new MezzeParser();
        TypeChecker typeChecker = new TypeChecker();

        String source = """
            let num = 42
            let msg = "Number: $(num)"
            """;

        var program = parser.parse(source, true, null);
        assertThrows(TypeCheckError.class, () -> typeChecker.typeCheck(program));
    }

    @Test
    void booleanInterpolationFailsAtTypecheck() {
        MezzeParser parser = new MezzeParser();
        TypeChecker typeChecker = new TypeChecker();

        String source = """
            let flag = True
            let msg = "Flag: $(flag)"
            """;

        var program = parser.parse(source, true, null);
        assertThrows(TypeCheckError.class, () -> typeChecker.typeCheck(program));
    }

    @Test
    void recordInterpolationFailsAtTypecheck() {
        MezzeParser parser = new MezzeParser();
        TypeChecker typeChecker = new TypeChecker();

        String source = """
            let person = {name = "Bob"}
            let msg = "Person: $(person)"
            """;

        var program = parser.parse(source, true, null);
        assertThrows(TypeCheckError.class, () -> typeChecker.typeCheck(program));
    }
}
