package com.mezze.interpreter;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.*;
import com.mezze.language.parser.MezzeParser;
import com.mezze.language.typechecker.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test helper for interpreter tests.
 *
 * Usage:
 *   Object result = MezzeTestHelper.run("1 + 2");
 *   assertEquals(3L, result);
 */
public final class MezzeTestHelper {

    private static final MezzeParser parser = new MezzeParser();
    private static final TypeChecker typeChecker = new TypeChecker();

    // Per-test Engine for correctness tests: an Engine holds strong
    // references to every RootNode/CallTarget its Language creates
    // (Truffle's internal retention), so a long-lived shared Engine
    // across ~thousands of tests leaks CallTargets and blows the heap.
    // engine.Compilation=false disables Truffle PE/JIT anyway (so the
    // usual "share to warm the JIT" argument doesn't apply here — tests
    // run interpreted to avoid per-test JIT warmup), which means the
    // fresh-per-test cost is negligible. Production (mez build output)
    // leaves Compilation on the default (true).
    private static Engine newInterpreterEngine() {
        return Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build();
    }

    // Separate engine for benchmarks: JIT ON, with PE+compilation, so
    // measurements reflect production perf (mez build / native image).
    // Bench callers must warm up before timing — Truffle needs ~hundreds
    // of executions before a CallTarget compiles. Use `runJit()` from
    // the bench harness; correctness tests should keep using `run()`.
    // Kept as a shared static specifically because bench iterations WANT
    // CallTargets (and their compiled code) to persist across calls.
    private static final Engine jitEngine = Engine.newBuilder()
        .allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false")
        // engine.Compilation defaults to true; spell it out for clarity.
        .option("engine.Compilation", "true")
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
        // Fresh Engine per test, closed after — see newInterpreterEngine()
        // for why. The `ownEngine=true` flag tells runWith to close it.
        Engine engine = newInterpreterEngine();
        return runWith(sourceCode, projectRoot, engine, true);
    }

    /**
     * Execute a Mezze program with Truffle JIT enabled. Intended for
     * benchmarks where JIT-compiled hot paths reflect production perf.
     * Each call still creates a fresh Context so module caches don't
     * leak across benches, but the engine is shared so CallTargets
     * (and their compiled code) persist across calls — meaning the
     * first call pays compile cost, subsequent calls are warm.
     */
    public static Object runJit(String sourceCode) {
        return runWith(sourceCode, null, jitEngine, false);
    }

    private static Object runWith(String sourceCode, Path projectRoot, Engine engine, boolean ownEngine) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        String sourcePath = root.resolve("__test__.mz").toString();

        // Clear module cache for clean state between tests.
        com.mezze.language.typechecker.ModuleResolver.clearCache();
        com.mezze.language.typechecker.TypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
        MezzeLanguage.clearLastResult();

        // Bare-mez interpreter tests: only the Mezze language is on the
        // classpath. Python/JS/Wasm engines are user-installed provider
        // packages tested separately in `:polyglot-*-natives` modules.
        Context.Builder builder = Context.newBuilder(MezzeLanguage.ID)
                .engine(engine)
                .allowAllAccess(true)
                .allowCreateThread(true)
                .allowExperimentalOptions(true);

        Context context = builder.build();

        try {
            Source source = Source.newBuilder(MezzeLanguage.ID, sourceCode, sourcePath)
                .buildLiteral();

            context.eval(source);
            return MezzeLanguage.getLastExecutionResult();
        } finally {
            try { context.close(true); } catch (Exception ignored) {}
            if (ownEngine) {
                try { engine.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Parse and type-check source code, returning the TypeCheckResult.
     */
    public static com.mezze.language.typechecker.TypeCheckResult typeCheck(String sourceCode) {
        return typeCheck(sourceCode, null);
    }

    /**
     * Parse and type-check source code with custom project root.
     */
    public static com.mezze.language.typechecker.TypeCheckResult typeCheck(String sourceCode, Path projectRoot) {
        Path root = projectRoot != null
            ? projectRoot
            : Paths.get(System.getProperty("mezze.project.root", "."));

        // Keep module caches for performance — only clear execution state
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();

        var parsedModule = parser.parseParsedModule(sourceCode, "<test>");
        return typeChecker.typeCheck(parsedModule, root);
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
     * Check if result is Unit — an empty MezzeRecord {} (unit is a 0-field record).
     */
    public static boolean isUnit(Object result) {
        return result instanceof MezzeRecord r && r.getFieldCount() == 0;
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

        // Clear module cache for clean state between tests.
        com.mezze.language.typechecker.ModuleResolver.clearCache();
        com.mezze.language.typechecker.TypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
        MezzeLanguage.clearLastResult();

        // Capture stdout
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream captureStream = new PrintStream(baos);
        PrintStream originalOut = System.out;

        // Fresh Engine per test — see newInterpreterEngine().
        Engine engine = newInterpreterEngine();
        Context context = Context.newBuilder(MezzeLanguage.ID)
                .engine(engine)
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
            try { context.close(true); } catch (Exception ignored) {}
            try { engine.close(); } catch (Exception ignored) {}
        }
    }
}
