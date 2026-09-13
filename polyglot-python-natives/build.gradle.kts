plugins {
    java
}

// Standalone gradle module producing `polyglot-python-natives.jar` — the slim
// SPI jar the `mezze-python` polyglot provider package ships to register
// GraalPy natives with a running :interpreter via `NativeDispatch.overrideHandler`.
//
// Pilot for polyglot-as-a-package (Phase 2). Loaded via `META-INF/services/
// com.mezze.interpreter.spi.NativePlugin`; the wire child JVM discovers it
// through the classpath entries `mez sync` collects from every synced
// package's `[jars]` table (see ResolvedJarsClasspath).
//
// Slim by design — no shading, no fat jar. The jar contains just:
//   - com.mezze.polyglot.python.PolyglotPythonPlugin
//   - com.mezze.interpreter.nodes.builtin.polyglot.PythonNatives
//   - META-INF/services/com.mezze.interpreter.spi.NativePlugin
//
// It picks up :interpreter classes (NativeDispatch, MezzeForeign, MezzeContext,
// PolyglotNatives) from the surrounding runtime classpath at execution time.

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    // Compile against :interpreter for NativeDispatch / NativePlugin / MezzeForeign
    // / PolyglotNatives — but the produced jar does NOT bundle any of those.
    // They come from the surrounding classpath (mezze-runtime.jar) at runtime.
    compileOnly(project(":interpreter"))
    // Truffle interop lives in :interpreter's transitive graph; keep compileOnly
    // so the produced jar stays minimal.
    compileOnly("org.graalvm.truffle:truffle-api:25.0.3")
    compileOnly("org.graalvm.polyglot:polyglot:25.0.3")
    compileOnly("org.graalvm.sdk:graal-sdk:25.0.3")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "--add-modules=jdk.incubator.vector"
    ))
}

tasks.jar {
    archiveBaseName.set("polyglot-python-natives")
    archiveVersion.set("0.1.0")
    manifest {
        attributes(
            "Implementation-Title" to "mezze polyglot-python-natives",
            "Implementation-Version" to "0.1.0",
        )
    }
}
