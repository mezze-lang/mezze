package com.mezze.interpreter.runtime;

import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MezzeExitExceptionTest {

    @Test
    void carriesExitStatus() {
        var ex = new MezzeExitException(42);
        assertEquals(42, ex.getExitStatus());
        assertEquals(0, new MezzeExitException(0).getExitStatus());
        assertEquals(130, new MezzeExitException(130).getExitStatus());
    }

    @Test
    void interopReportsAsExitException() {
        var ex = new MezzeExitException(7);
        var lib = InteropLibrary.getUncached();
        assertTrue(lib.isException(ex), "should report isException = true");
        try {
            assertEquals(ExceptionType.EXIT, lib.getExceptionType(ex),
                "should report ExceptionType.EXIT so polyglot treats it as a clean termination");
            assertEquals(7, lib.getExceptionExitStatus(ex),
                "should expose exit status via interop");
        } catch (Exception e) {
            throw new AssertionError("interop probes should not throw", e);
        }
    }

    @Test
    void throwExceptionRethrowsSameInstance() {
        // The InteropLibrary throwException contract is "rethrow as-is";
        // Truffle relies on this to unwind across the language boundary.
        var ex = new MezzeExitException(99);
        var thrown = assertThrows(MezzeExitException.class, ex::throwException);
        assertSame(ex, thrown);
    }
}
