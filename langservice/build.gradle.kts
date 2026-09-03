// :langservice — transport-neutral language-services / presentation layer.
//
// Owns the "last mile" that turns typechecker output into something a human
// sees: diagnostics (DiagnosticJson + formatters), hover, typed-AST lookup, and
// (later) completion. Consumed by :lsp, :cli, and the WASM playground, each of
// which adds only a thin adapter (lsp4j / terminal text / JS string).
//
// HARD RULE: this module must NOT depend on lsp4j or any transport. Keeping it
// transport-neutral is what lets every front-end share one source of truth and
// makes last-mile divergence impossible by construction.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":ast"))         // MezzeType, parsedast (Loc/ErrorCode/FixHint), TypePrinter
    implementation(project(":parser"))       // ParseException
    implementation(project(":typechecker"))  // TypeCheckResult/Error, TypedAst, ModuleError, unify, TypeAnnotationConverter
    implementation(project(":store"))        // MezzeStore — completion import-path + auto-import decls

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }

    maxParallelForks = 1

    systemProperty("junit.jupiter.execution.timeout.default", "60s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
}
