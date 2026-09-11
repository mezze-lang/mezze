plugins {
    java
    kotlin("jvm")
    // shadowJar: produces the slim "mezze-runtime.jar" that user apps
    // bundle. Contains :interpreter + its runtime deps only — no parser,
    // typechecker, CLI, LSP, formatter, etc. See the runtimeShadowJar
    // task below.
    id("com.gradleup.shadow") version "9.4.1"
}
// GraalPy Gradle plugin (`org.graalvm.python`) intentionally NOT applied.
// The plugin's numpy install path (`graalPyInstallPackages`) reliably
// FAILS on GraalPy 25.0.3 at the Meson AVX2 loop generation step for
// both numpy 1.26.4 and 2.0.2. The `NumpyInteropTest` gracefully skips
// (via JUnit Assumptions) when numpy is absent; users can populate the
// venv manually per `examples/polyglot_numpy/README` if needed.

// Pure Java module for Truffle DSL nodes
// The converter will be in a separate Kotlin module or in the interpreter module
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Enable Vector API (incubator module) — FlpSimd uses it for SIMD-accelerated
// FusedLongPipeline/FusedLongRangePipeline hot paths.
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--add-modules=jdk.incubator.vector"
    ))
}

dependencies {
    // Core project dependencies.
    //
    // :interpreter is the runtime. The :parser and :typechecker deps
    // exist for two reasons today:
    //   1. MezzeContext (a Truffle context) holds lazy per-context
    //      parser/typechecker fields. The host can re-parse/recheck
    //      sources at runtime — but `mez build --native` user binaries
    //      never do (they execute pre-compiled FunctionIR only).
    //   2. PipelineRunner references PrecompiledStdlib (which lives in
    //      :typechecker even though it's pure runtime data —
    //      pre-compiled stdlib FunctionIRs + dep graph).
    //
    // Both could be lifted: (1) by moving the lazy parser/typechecker
    // out of MezzeContext (host concern, not language concern), (2) by
    // moving PrecompiledStdlib into :store or :stdlib. Tracked under
    // #187. Until then the deps stick.
    implementation(project(":ast"))
    implementation(project(":store"))
    implementation(project(":stdlib"))
    implementation(project(":datastructures"))
    // RuntimeServer (the JVM-side wire endpoint) speaks the v1 protocol
    // defined in :wire-protocol.
    implementation(project(":wire-protocol"))

    // Truffle API - for language implementation
    implementation("org.graalvm.truffle:truffle-api:25.0.3")

    // Truffle optimizing runtime (HotSpotTruffleRuntime). Without this,
    // Truffle falls back to DefaultTruffleRuntime — i.e. pure interpreter,
    // no PE, no JIT. On GraalVM JDK the compiler module
    // `org.graalvm.truffle.compiler` is already a jmod in the JDK, so this
    // single artifact is sufficient — no --upgrade-module-path needed.
    implementation("org.graalvm.truffle:truffle-runtime:25.0.3")

    // Truffle DSL annotation processor - generates specialization code
    annotationProcessor("org.graalvm.truffle:truffle-dsl-processor:25.0.3")

    // GraalVM SDK - for polyglot interop
    implementation("org.graalvm.sdk:graal-sdk:25.0.3")
    implementation("org.graalvm.polyglot:polyglot:25.0.3")

    // Truffle instrumentation tools — Chrome Inspector (CDP), DAP (VS Code
    // debug protocol), CPU sampler / tracer / memtracer, coverage, Insight.
    // Without these on the classpath, Truffle reports "Could not find option"
    // for cpusampler / coverage / inspect / etc.
    implementation("org.graalvm.tools:profiler-tool:25.0.3")
    implementation("org.graalvm.tools:chromeinspector-tool:25.0.3")
    implementation("org.graalvm.tools:dap-tool:25.0.3")
    implementation("org.graalvm.tools:coverage-tool:25.0.3")
    implementation("org.graalvm.tools:insight-tool:25.0.3")

    // HTTP client — Helidon Níma WebClient. Loom-native (blocking API on
    // virtual threads), built directly on JDK NIO + SSLEngine, no Netty.
    // Replaces Vert.x: ~12 MB jar trim and proper Loom integration (the
    // old Vert.x path did `future.get(timeout)` which pinned the calling
    // fiber).
    implementation("io.helidon.webclient:helidon-webclient:4.4.1")

    // DSL-JSON - high performance JSON library (byte-level, no reflection, GraalVM native-image compatible)
    implementation("com.dslplatform:dsl-json:2.0.2")

    // GraalPy — polyglot Python. Depend on the concrete jars, NOT the
    // aggregating `org.graalvm.polyglot:python` POM: shadowJar cannot
    // expand a POM artifact and fails with "Cannot expand ZIP ...pom".
    // `runtimeOnly` because :interpreter never references GraalPy classes
    // by symbol — everything goes through the generic polyglot API
    // (Env.parsePublic, Context.eval on "python"). Having them on the
    // runtime classpath is what lets the shadowJar bundle GraalPy into
    // mezze-runtime.jar so `mez exec` can enable the "python" language
    // when the project's Mezze.toml declares `[polyglot.python]`.
    runtimeOnly("org.graalvm.python:python-language:25.0.3")
    runtimeOnly("org.graalvm.python:python-resources:25.0.3")
    runtimeOnly("org.graalvm.python:python-launcher:25.0.3")

    // GraalJS — polyglot JavaScript. Same rationale as GraalPy: depend on
    // the concrete jars (js-language + js-scriptengine), not the aggregating
    // POM, so shadowJar can bundle them. `runtimeOnly` because :interpreter
    // never references GraalJS classes directly — Context.eval("js", …)
    // routes through the generic polyglot API. Loading `js` is opted-in via
    // `[polyglot.js]` in the project's Mezze.toml; a plain program that
    // doesn't declare the section pays zero for these being on the classpath.
    runtimeOnly("org.graalvm.js:js-language:25.0.3")
    runtimeOnly("org.graalvm.js:js-scriptengine:25.0.3")

    // GraalWasm — polyglot WebAssembly. Same rationale as GraalPy / GraalJS:
    // depend on the concrete jar (wasm-language), not the aggregating POM,
    // so shadowJar can bundle it. `runtimeOnly` because :interpreter never
    // references GraalWasm classes directly — Context.eval on a
    // `Source.newBuilder("wasm", ByteSequence.create(bytes), name)` routes
    // through the generic polyglot API. Loading `wasm` is opted-in via
    // `[polyglot.wasm]` in the project's Mezze.toml; a plain program that
    // doesn't declare the section pays zero for this being on the classpath.
    runtimeOnly("org.graalvm.wasm:wasm-language:25.0.3")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(project(":parser"))
    testImplementation(project(":typechecker"))

    // python-embedding — GraalPyResources + venv helpers used by the
    // NumpyInteropTest harness. Tests only; not needed at runtime.
    testImplementation("org.graalvm.python:python-embedding:25.0.3")
}

// Shared config applied to both the default `test` task and the
// opt-in `bench` task below. Keeps env-passthrough, JVM args, heap, and
// logging in one place — the only difference between the two is which
// test classes they include.
fun org.gradle.api.tasks.testing.Test.applyCommonTestConfig() {
    useJUnitPlatform()

    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }

    // Single fork (#379): the interpreter suite contains wall-clock timing
    // assertions (e.g. VirtualThreadEffectIntegrationTest "parallel faster than
    // sequential", which asserts < 200ms) that assume exclusive CPU access.
    // Parallel test forks oversubscribe the CPU and starve those tasks of cores,
    // so the timing assertions flake. Parallelism was measured (4 forks: 3m33s vs
    // 6m26s single-fork) but forced this test red — timing tests are fundamentally
    // incompatible with fork contention, so the suite stays single-fork.
    maxParallelForks = 1

    systemProperty("mezze.project.root", rootProject.projectDir.absolutePath)
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")
    // Typechecker phase-boundary invariants ON — the interpreter suite type-checks
    // real programs (incl. the whole stdlib), so this is the broadest coverage of
    // the invariants. See the TC re-layering plan (Stage 0).
    systemProperty("mezze.invariants", "true")

    // Pass through MEZZE_DEBUG env var into the test JVM so the deferred
    // catches in TypeChecker print exception traces.
    System.getenv("MEZZE_DEBUG")?.let { environment("MEZZE_DEBUG", it) }
    System.getenv("MEZZE_PATHD_TRACE")?.let { environment("MEZZE_PATHD_TRACE", it) }
    System.getenv("MEZZE_BDSL_STATS")?.let { environment("MEZZE_BDSL_STATS", it) }
    System.getenv("MEZZE_BDSL_STRICT")?.let { environment("MEZZE_BDSL_STRICT", it) }
    System.getenv("MEZZE_HANDLE_LOOM")?.let { environment("MEZZE_HANDLE_LOOM", it) }
    System.getenv("MEZZE_BDSL_ENTRY")?.let { environment("MEZZE_BDSL_ENTRY", it) }
    System.getenv("MEZZE_INLINE_WRAPPERS")?.let { environment("MEZZE_INLINE_WRAPPERS", it) }
    System.getenv("MEZZE_LAZY_EFFECTS")?.let { environment("MEZZE_LAZY_EFFECTS", it) }
    System.getenv("MEZZE_LEGACY_SUSPENDED")?.let { environment("MEZZE_LEGACY_SUSPENDED", it) }
    System.getenv("MEZZE_MONO_STATS")?.let { environment("MEZZE_MONO_STATS", it) }
    System.getenv("MEZZE_MONO_TRACE")?.let { environment("MEZZE_MONO_TRACE", it) }
    System.getenv("MEZZE_DISPATCH_CENSUS")?.let { environment("MEZZE_DISPATCH_CENSUS", it) }
    System.getenv("MEZZE_DISABLE_SCALAR")?.let { environment("MEZZE_DISABLE_SCALAR", it) }
    System.getenv("MEZZE_DEBUG_TYPED")?.let { environment("MEZZE_DEBUG_TYPED", it) }
    System.getenv("MEZZE_DEBUG_INLINE")?.let { environment("MEZZE_DEBUG_INLINE", it) }
    System.getenv("MEZZE_DISABLE_INLINE")?.let { environment("MEZZE_DISABLE_INLINE", it) }
    System.getenv("MEZZE_DISABLE_DSWIRE")?.let { environment("MEZZE_DISABLE_DSWIRE", it) }
    // Diagnostic traces (task #384 investigation).
    System.getenv("MEZZE_TRAMPOLINE_TRACE")?.let { environment("MEZZE_TRAMPOLINE_TRACE", it) }
    System.getenv("MEZZE_INDIRECT_TRACE")?.let { environment("MEZZE_INDIRECT_TRACE", it) }
    System.getenv("MEZZE_CALL_TRACE")?.let { environment("MEZZE_CALL_TRACE", it) }
    // Allow opt-in to Truffle JIT for perf measurement (bench task).
    System.getenv("MEZZE_JIT")?.let { environment("MEZZE_JIT", it) }

    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/sun.misc=ALL-UNNAMED",
        "--add-modules=jdk.incubator.vector"
    )

    maxHeapSize = "2g"
}

// Names that mark a JUnit class as a benchmark / profiler / stress test
// rather than a correctness test. Filename convention — any class whose
// simple name matches one of these is routed to `:interpreter:bench`,
// not the default `test` task.
val benchClassPatterns = listOf(
    "**/*Benchmark*",
    "**/*Bench*",
    "**/*PerfTest*",
    "**/*ProfileTest*",
    "**/StdlibHofSoSurveyTest*",
    "**/MicroBenchmarkTest*",
)

tasks.test {
    applyCommonTestConfig()
    // Test-isolation: the correctness suite shares one Engine
    // (MezzeTestHelper.sharedEngine) across every test, so Language-level
    // CallTarget caches + the BDSL compiler's compiled-caller cache persist and a
    // caller cached in one test can hold a baked stub to a callee whose IR only
    // lived in a now-closed Context → order-dependent "no MezzeFunction registered
    // for hash" flakes. This flips MezzeLanguage into per-parse cache clearing.
    // Set ONLY here, never on `bench`: benches share the jitEngine ACROSS Contexts
    // specifically to warm CallTargets, and per-parse clearing would force every
    // bench iteration cold and wreck the measurements.
    systemProperty("mezze.test.isolation", "true")
    // Default `test` excludes benches so `./gradlew :interpreter:test`
    // stays a fast feedback loop. Benches live in :interpreter:bench.
    benchClassPatterns.forEach { exclude(it) }
    // WireProtocolIntegrationTest spawns a JVM against the runtime shadow
    // jar; without this dep, the test runs before the jar exists and fails
    // its locateRuntimeJar precondition.
    dependsOn("runtimeShadowJar")
}

// ─── Runtime shadowJar ──────────────────────────────────────────────────
//
// Produces `mezze-runtime.jar` — the slim runtime that user-app JVM
// jars bundle (and that `mez build --native` feeds to native-image).
//
// Contains: :interpreter + its runtime transitive deps (truffle, polyglot,
// helidon-webclient, kotlin-stdlib, kotlinx-serialization-cbor). Crucially NOT:
// :parser, :typechecker, :formatter, :lsp, :store-sqlite, :cli, lsp4j,
// logback, ktor, sqlite-jdbc.
//
// User-app build path (BuildCommand.createStandaloneJar): clones this
// jar, appends META-INF/mezze/embedded-app.cbor + .meta, sets Main-Class
// to com.mezze.interpreter.standalone.NativeMain. Same artifact works on
// JVM (`java -jar app.jar`) and as the staging input to native-image.
val runtimeShadowJar = tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("runtimeShadowJar") {
    group = "build"
    description = "Slim runtime fat-jar that user apps bundle (no CLI/LSP/parser/typechecker)."

    // The CLI's compileStdlib task produces stdlib.sqlite +
    // META-INF/mezze/stdlib-snapshot.cbor under cli/build/generated/resources/.
    // Pull those into the runtime jar so PrecompiledStdlib's
    // tryLoadFromSnapshot finds the CBOR (no sources fallback) and the
    // SQLite path still works on JVM-jar boots that prefer it.
    dependsOn(":cli:compileStdlib")

    archiveBaseName.set("mezze-runtime")
    archiveClassifier.set("")
    archiveVersion.set("")

    // Truffle API is a multi-release JAR — the JDK 21+ overlay of
    // JDKAccessor implements virtual threads (Loom). Without this
    // manifest entry, the pre-21 base class is loaded at runtime and
    // immediately throws "VirtualThread is not supported on this JDK"
    // the moment Async.spawn fires. Matches cli/build.gradle.kts's
    // mezze-lang.jar (which already sets this).
    manifest {
        attributes("Multi-Release" to "true")
    }

    from(sourceSets.main.get().output)
    from(project(":cli").layout.buildDirectory.dir("generated/resources")) {
        // Skip the per-library zip — that's CLI-only (for `mez build --native`).
        exclude("META-INF/mezze/native-classpath.zip")
        // Drop the 30 MB stdlib.sqlite blob — runtime loads stdlib from
        // META-INF/mezze/stdlib-snapshot.cbor (PrecompiledStdlib's primary
        // path). The .sqlite was only needed by the legacy code that
        // round-tripped through JDBC; with #220's wire path the runtime
        // never opens SQLite.
        exclude("stdlib.sqlite")
    }
    // Stdlib .mz sources for go-to-definition + the prelude bootstrap
    // fallback (when the CBOR snapshot is somehow missing).
    from(rootProject.projectDir.resolve("stdlib")) {
        include("**/*.mz")
        into("stdlib")
    }
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // Drop tools that exist only for dev (mez dev, mez run --profile etc).
    // These live in :interpreter's runtime classpath because we want them
    // available when the CLI shells out to a JVM with this same classpath,
    // but a deployed user binary doesn't need them.
    dependencies {
        exclude(dependency("org.graalvm.tools:profiler-tool"))
        exclude(dependency("org.graalvm.tools:chromeinspector-tool"))
        exclude(dependency("org.graalvm.tools:dap-tool"))
        exclude(dependency("org.graalvm.tools:coverage-tool"))
        exclude(dependency("org.graalvm.tools:insight-tool"))
        // sqlite-jdbc is pulled in transitively via :typechecker, but the
        // runtime never opens SQLite — both the wire-protocol path (#220)
        // and the embedded-app path read FunctionIR from CBOR. Drops 26 MB.
        exclude(dependency("org.xerial:sqlite-jdbc"))
    }

    // Truffle discovers languages/instruments via ServiceLoader —
    // META-INF/services/com.oracle.truffle.api.provider.TruffleLanguageProvider
    // lists every language impl. Every contributing jar (mezze,
    // python-language, regex, nfi, ...) ships its own copy of that
    // file; the runtime jar has to contain every line, or the language
    // never registers and Context.newBuilder(...,"python") fails at
    // runtime with "Could not find option with name python.PythonPath".
    //
    // Two knobs are needed together. mergeServiceFiles() installs the
    // transformer that concatenates copies from configuration deps.
    // duplicatesStrategy = INCLUDE stops Gradle's default Copy
    // machinery from silently dropping duplicates before the
    // transformer sees them — the annotation-processor output of
    // :interpreter and python-language.jar both write the same path,
    // and INCLUDE is what lets both reach the merge.
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    // Exclude module-info.class so the merged fat-jar doesn't claim to be
    // a module (would conflict on the classpath).
    exclude("module-info.class")
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")

    // No manual com/mezze/language/{parser,typechecker}/** excludes here:
    // :interpreter no longer depends on :parser or :typechecker (#222), so
    // those classes aren't on the runtime classpath at all. If a regression
    // accidentally re-introduces the dep, the shadowJar will start
    // including those packages again — the fix is to remove the dep, not to
    // pile excludes back here.
}

tasks.register<Test>("bench") {
    group = "verification"
    description = "Performance benchmarks and profiling harnesses (excluded from :test)."
    applyCommonTestConfig()
    // Wire the custom Test task to the `test` source set explicitly.
    // A registered custom Test task doesn't inherit testClassesDirs/
    // classpath the way the default `test` task does, which is why bench
    // was reporting NO-SOURCE. #70.
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    // Bench tests need longer per-test timeout — they're allowed to run
    // for tens of seconds. Override the strict 120s default applied above.
    systemProperty("junit.jupiter.execution.timeout.default", "600s")
    // Bench needs more heap than the 2g test default — the 1M-spawn
    // probe alone needs ~5GB for virtual-thread stacks.
    maxHeapSize = "10g"
    benchClassPatterns.forEach { include(it) }
    // Always re-run; bench output is informational, never up-to-date.
    outputs.upToDateWhen { false }
}
