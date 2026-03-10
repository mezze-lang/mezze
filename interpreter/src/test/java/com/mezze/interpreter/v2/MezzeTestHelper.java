package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.*;
import com.mezze.language.ast.Program;
import com.mezze.language.parser.MezzeParser;
import com.mezze.language.typechecker.ModuleResolver;
import com.mezze.language.typechecker.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test helper for V2 interpreter tests.
 *
 * Mirrors the V1 MezzeTestHelper API for compatibility,
 * but uses the V2 optimized interpreter.
 *
 * Usage:
 *   Object result = MezzeTestHelper.run("1 + 2");
 *   assertEquals(3L, result);
 */
public final class MezzeTestHelper {

    private static final MezzeParser parser = new MezzeParser();
    private static final TypeChecker typeChecker = new TypeChecker();

    // Shared engine for test execution performance
    private static final Engine sharedEngine = Engine.newBuilder()
        .option("engine.WarnInterpreterOnly", "false")
        .build();

    private MezzeTestHelper() {}

    /**
     * Execute a Mezze program from source code.
     * Returns the raw V2 value (Long, Double, Boolean, MezzeRecord, etc.)
     */
    public static Object run(String sourceCode) {
        return run(sourceCode, null);
    }

    /**
     * Execute a Mezze program from source code with custom project root.
     */
    public static Object run(String sourceCode, Path projectRoot) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        String sourcePath = root.resolve("__test__.mz").toString();

        // Clear module cache for clean state
        ModuleResolver.clearCache();
        MezzeLanguage.clearLastResult();

        Context context = Context.newBuilder(MezzeLanguage.ID)
                .engine(sharedEngine)
                .allowAllAccess(true)
                .allowCreateThread(true)
                .build();

        try {
            Source source = Source.newBuilder(MezzeLanguage.ID, sourceCode, sourcePath)
                .buildLiteral();

            context.eval(source);

            return MezzeLanguage.getLastExecutionResult();
        } finally {
            // Use cancelIfExecuting=true for Go/Rust-style semantics:
            // When the program exits, any fire-and-forget tasks are cancelled.
            // This prevents "polyglot threads did not complete" errors.
            context.close(true);
        }
    }

    /**
     * Execute a pre-typed program directly.
     */
    public static Object execute(Program program) {
        return execute(program, null);
    }

    /**
     * Execute a pre-typed program with custom project root.
     */
    public static Object execute(Program program, Path projectRoot) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        MezzeLanguage.clearLastResult();

        Context context = Context.newBuilder(MezzeLanguage.ID)
                .engine(sharedEngine)
                .allowAllAccess(true)
                .allowCreateThread(true)
                .build();

        try {
            // Set the program for direct execution
            MezzeLanguage.setProgramForDirectExecution(program, root);

            // Trigger execution with minimal source
            Source source = Source.newBuilder(MezzeLanguage.ID, "# direct", "__direct__.mz")
                .buildLiteral();

            context.eval(source);

            return MezzeLanguage.getLastExecutionResult();
        } finally {
            // Use cancelIfExecuting=true for Go/Rust-style semantics:
            // When the program exits, any fire-and-forget tasks are cancelled.
            // This prevents "polyglot threads did not complete" errors.
            context.close(true);
        }
    }

    /**
     * Parse source code to AST (without type-checking).
     */
    public static Program parse(String sourceCode) {
        return parser.parse(sourceCode, true, null);
    }

    /**
     * Parse and type-check source code.
     */
    public static Program typeCheck(String sourceCode) {
        return typeCheck(sourceCode, null);
    }

    /**
     * Parse and type-check source code with custom project root.
     */
    public static Program typeCheck(String sourceCode, Path projectRoot) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        ModuleResolver.clearCache();

        Program ast = parser.parse(sourceCode, true, null);
        var result = typeChecker.typeCheck(ast, root);
        return result.getProgram();
    }

    // ========================
    // Value conversion helpers
    // ========================

    /**
     * Convert V2 result to Long (for Int values).
     */
    public static Long asLong(Object result) {
        if (result instanceof Long l) return l;
        if (result instanceof Number n) return n.longValue();
        throw new AssertionError("Expected Long, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to Double (for Float values).
     */
    public static Double asDouble(Object result) {
        if (result instanceof Double d) return d;
        if (result instanceof Number n) return n.doubleValue();
        throw new AssertionError("Expected Double, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to Boolean.
     */
    public static Boolean asBoolean(Object result) {
        if (result instanceof Boolean b) return b;
        throw new AssertionError("Expected Boolean, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to String.
     */
    public static String asString(Object result) {
        if (result instanceof String s) return s;
        throw new AssertionError("Expected String, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to MezzeRecord.
     */
    public static MezzeRecord asRecord(Object result) {
        if (result instanceof MezzeRecord r) return r;
        throw new AssertionError("Expected MezzeRecord, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to MezzeList.
     */
    public static MezzeList asList(Object result) {
        if (result instanceof MezzeList l) return l;
        throw new AssertionError("Expected MezzeList, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Convert V2 result to MezzeVariant.
     */
    public static MezzeVariant asVariant(Object result) {
        if (result instanceof MezzeVariant v) return v;
        throw new AssertionError("Expected MezzeVariant, got: " + (result == null ? "null" : result.getClass().getName()));
    }

    /**
     * Check if result is Unit.
     * Unit can be either MezzeUnit.INSTANCE or an empty MezzeRecord {}.
     */
    public static boolean isUnit(Object result) {
        if (result == MezzeUnit.INSTANCE) return true;
        if (result instanceof MezzeRecord r && r.getFieldCount() == 0) return true;
        return false;
    }

    // ========================
    // Output capture helpers
    // ========================

    /**
     * Result of running with output capture.
     */
    public record RunResult(Object value, String output) {}

    /**
     * Execute a Mezze program and capture stdout.
     * Returns both the result value and the captured output.
     */
    public static RunResult runWithOutput(String sourceCode) {
        return runWithOutput(sourceCode, null);
    }

    /**
     * Execute a Mezze program and capture stdout with custom project root.
     */
    public static RunResult runWithOutput(String sourceCode, Path projectRoot) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        String sourcePath = root.resolve("__test__.mz").toString();

        // Clear module cache for clean state
        ModuleResolver.clearCache();
        MezzeLanguage.clearLastResult();

        // Capture stdout
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos);
        PrintStream originalOut = System.out;

        Context context = Context.newBuilder(MezzeLanguage.ID)
                .engine(sharedEngine)
                .allowAllAccess(true)
                .allowCreateThread(true)
                .out(captureStream)
                .build();

        try {
            System.setOut(captureStream);

            Source source = Source.newBuilder(MezzeLanguage.ID, sourceCode, sourcePath)
                .buildLiteral();

            context.eval(source);

            Object result = MezzeLanguage.getLastExecutionResult();
            String output = baos.toString();

            return new RunResult(result, output);
        } finally {
            System.setOut(originalOut);
            context.close(true);
        }
    }
}
