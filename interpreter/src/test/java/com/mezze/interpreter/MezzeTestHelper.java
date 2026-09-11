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

    // Shared engine for general test execution performance.
    // engine.Compilation=false disables Truffle PE/JIT — even though
    // truffle-runtime is on the classpath (HotSpotTruffleRuntime), tests
    // run interpreted to avoid per-test JIT warmup (~1-3s each).
    // Production (mez build output) leaves this on the default (true).
    private static final Engine sharedEngine = Engine.newBuilder()
        .allowExperimentalOptions(true)
        .option("engine.WarnInterpreterOnly", "false")
        .option("engine.Compilation", "false")
        .build();

    // Separate engine for benchmarks: JIT ON, with PE+compilation, so
    // measurements reflect production perf (mez build / native image).
    // Bench callers must warm up before timing — Truffle needs ~hundreds
    // of executions before a CallTarget compiles. Use `runJit()` from
    // the bench harness; correctness tests should keep using `run()`.
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
        return runWith(sourceCode, projectRoot, sharedEngine);
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
        return runWith(sourceCode, null, jitEngine);
    }

    /**
     * Execute with GraalPy's `python.IsolateNativeModules` set to true.
     * Needed for tests that load a Python C-extension (numpy, etc.) from
     * more than one Context in the same process — without isolation the
     * second load fails with "a second GraalPy context attempted to load
     * a native module ...".
     *
     * Runs on a dedicated fresh Engine (not the shared one) so the
     * isolate's classloader cannot leak into other tests' Mezze
     * bytecode reflection lookups.
     */
    public static Object runWithPythonIsolate(String sourceCode) {
        // Fresh Engine per call — safe because the isolate must not
        // survive into shared-engine tests.
        Engine engine = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .option("engine.Compilation", "false")
            .build();
        try {
            return runWith(sourceCode, null, engine, true);
        } finally {
            try { engine.close(); } catch (Exception ignored) {}
        }
    }

    private static Object runWith(String sourceCode, Path projectRoot, Engine engine) {
        return runWith(sourceCode, projectRoot, engine, false);
    }

    private static Object runWith(String sourceCode, Path projectRoot, Engine engine, boolean pythonIsolate) {
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

        Context.Builder builder = Context.newBuilder(MezzeLanguage.ID, "python", "js", "wasm")
                .engine(engine)
                .allowAllAccess(true)
                .allowCreateThread(true)
                // Python (GraalPy) needs experimental options + a JVM-friendly
                // posix backend when run embedded — the "java" backend avoids
                // requiring a POSIX resource library at runtime.
                .allowExperimentalOptions(true)
                .option("python.PosixModuleBackend", "java")
                .option("python.WarnExperimentalFeatures", "false")
                // JS (GraalJS): pin ECMAScript version so tests are stable
                // across GraalJS bumps. No require/import support wired up
                // here — JsInteropTest exercises `eval` only.
                .option("js.ecmascript-version", "2024");
        if (pythonIsolate) {
            builder.option("python.IsolateNativeModules", "true");
        }

        // If the org.graalvm.python plugin populated the venv, wire its
        // site-packages onto PythonPath so `import numpy` succeeds.  The
        // venv lives at <build>/graalpy-venv/venv/ (Linux/macOS layout).
        String projectDir = System.getProperty("mezze.project.root", ".");
        Path venvSitePackages = Paths.get(projectDir,
            "interpreter", "build", "graalpy-venv", "venv", "lib");
        if (java.nio.file.Files.isDirectory(venvSitePackages)) {
            try (var stream = java.nio.file.Files.list(venvSitePackages)) {
                var pyDir = stream
                    .filter(p -> p.getFileName().toString().startsWith("python"))
                    .findFirst();
                if (pyDir.isPresent()) {
                    Path sitePackages = pyDir.get().resolve("site-packages");
                    if (java.nio.file.Files.isDirectory(sitePackages)) {
                        builder.option("python.PythonPath", sitePackages.toString());
                    }
                }
            } catch (java.io.IOException ignored) {}
        }

        Context context = builder.build();

        try {
            Source source = Source.newBuilder(MezzeLanguage.ID, sourceCode, sourcePath)
                .buildLiteral();

            context.eval(source);
            return MezzeLanguage.getLastExecutionResult();
        } finally {
            try { context.close(true); } catch (Exception ignored) {}
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
