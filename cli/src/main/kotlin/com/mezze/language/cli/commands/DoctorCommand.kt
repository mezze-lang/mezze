package com.mezze.language.cli.commands

import com.mezze.language.cli.BuildInfo
import com.mezze.language.cli.GraalvmResolver

/**
 * `mez doctor` — report what mez found for its runtime and what, if anything,
 * to set up. It DIAGNOSES and ADVISES; it never installs anything (that's the
 * user's package manager's job, or the opt-in `mez setup graalvm`). When the
 * runtime is degraded it prints the install command for each common package
 * manager plus the one env contract (MEZZE_GRAALVM_HOME) that wires a
 * bring-your-own GraalVM in.
 */
class DoctorCommand {

    fun execute() {
        val r = GraalvmResolver.resolve()
        val g = Glyphs

        println("mez ${BuildInfo.VERSION}  (wants GraalVM line ${BuildInfo.GRAAL_LINE})")
        println()

        // Runtime line — one glyph that reads at a glance.
        when (r.kind) {
            GraalvmResolver.Kind.GRAALVM_MATCHING ->
                println("${g.OK} GraalVM ${r.version}  (line ${r.graalLine}) — JIT enabled")
            GraalvmResolver.Kind.GRAALVM_OTHER_LINE ->
                println("${g.WARN} GraalVM ${r.version}  (line ${r.graalLine}, mez wants ${BuildInfo.GRAAL_LINE})")
            GraalvmResolver.Kind.PLAIN_JDK ->
                println("${g.WARN} JDK ${r.version}  (not GraalVM) — interpreter only, no JIT")
            GraalvmResolver.Kind.TOO_OLD ->
                println("${g.ERROR} JDK ${r.version}  is too old — mez needs 21+")
            GraalvmResolver.Kind.NONE ->
                println("${g.ERROR} no JDK found")
        }
        r.home?.let { println("   home:   $it") }
        if (r.kind != GraalvmResolver.Kind.NONE) println("   source: ${r.source}")

        // Capability summary.
        println()
        println("   JIT (fast execution):     ${yesNo(r.jit)}")
        println("   native-image (--native):  ${yesNo(r.native)}")

        // Advice, only when there's something to do.
        if (r.kind != GraalvmResolver.Kind.GRAALVM_MATCHING) {
            println()
            println("${g.TIP} For JIT + native builds, install GraalVM ${BuildInfo.GRAAL_LINE} your way:")
            println("     nix       add pkgs.graalvm-ce to your shell, then")
            println("               export MEZZE_GRAALVM_HOME=\"\${pkgs.graalvm-ce}\"")
            println("     brew      brew install --cask graalvm-jdk")
            println("     sdkman    sdk install java ${BuildInfo.GRAAL_LINE.substringBefore('.')}-graalce")
            println("     manual    github.com/graalvm/graalvm-ce-builds/releases")
            println()
            println("   Then point mez at it (any one):")
            println("     export MEZZE_GRAALVM_HOME=<graalvm-dir>   # explicit, wins over all")
            println("     export GRAALVM_HOME=<graalvm-dir>")
            println("     put <graalvm-dir>/bin on your PATH")
            println()
            println("   mez runs today on any JDK 21+ (interpreter); GraalVM only adds")
            println("   JIT speed and native builds — it is never required to get started.")
        }
    }

    private fun yesNo(b: Boolean): String = if (b) "${Glyphs.CHECK} yes" else "${Glyphs.CROSS} no"
}
