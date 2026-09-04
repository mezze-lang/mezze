# CLI Changes

This document covers the changes needed to the Mezze CLI to support polyglot interop.

## Overview

The CLI needs to:
1. Parse extended Mezze.toml schema with `[polyglot.*]` sections
2. Check for GraalVM and `gu` availability
3. Auto-install GraalVM language components
4. Install foreign packages (pip, npm, maven, etc.)
5. Set up `.deps/` directory structure

## Mezze.toml Schema Extension

### Updated MezzeToml.kt

```kotlin
// cli/src/main/kotlin/com/mezze/language/cli/package/MezzeToml.kt

@Serializable
data class MezzeToml(
    val `package`: PackageMetadata,
    val lib: LibraryConfig? = null,
    val app: List<AppConfig>? = null,
    val dependencies: Map<String, String> = emptyMap(),
    @SerialName("dev-dependencies")
    val devDependencies: Map<String, String> = emptyMap(),
    val polyglot: Map<String, PolyglotLanguageConfig>? = null
)

@Serializable
data class PolyglotLanguageConfig(
    val interop: String,  // URL to interop library
    val extras: List<String> = emptyList()  // Foreign packages
)

// For interop library manifests
@Serializable
data class PolyglotProviderConfig(
    @SerialName("language_id")
    val languageId: String,
    @SerialName("graalvm_component")
    val graalvmComponent: String,
    @SerialName("install_command")
    val installCommand: String,
    @SerialName("runtime_path")
    val runtimePath: String
)
```

## Updated SyncCommand

```kotlin
// cli/src/main/kotlin/com/mezze/language/cli/commands/SyncCommand.kt

class SyncCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val mezzeToml = File(currentDir, "Mezze.toml")

        if (!mezzeToml.exists()) {
            println("❌ Error: No Mezze.toml found in current directory")
            return
        }

        val isClean = args.contains("--clean")
        val isVerbose = args.contains("--verbose")

        val manifest = parseManifest(mezzeToml)

        // Create .deps directory
        val depsDir = File(currentDir, ".deps")
        if (isClean && depsDir.exists()) {
            println("🧹 Cleaning .deps/...")
            depsDir.deleteRecursively()
        }
        depsDir.mkdirs()

        // 1. Sync Mezze dependencies
        syncMezzeDependencies(manifest, depsDir, isVerbose)

        // 2. Sync polyglot dependencies
        if (manifest.polyglot != null) {
            syncPolyglotDependencies(manifest.polyglot, depsDir, isVerbose)
        }

        // 3. Write lock file
        writeLockFile(currentDir)

        println()
        println("✓ Sync complete")
    }

    private fun syncMezzeDependencies(manifest: MezzeToml, depsDir: File, verbose: Boolean) {
        val mezzeDir = File(depsDir, "mezze")
        mezzeDir.mkdirs()

        val allDeps = manifest.dependencies + manifest.devDependencies

        // Also include interop libraries from polyglot sections
        val interopDeps = manifest.polyglot?.mapValues { it.value.interop } ?: emptyMap()
        val allMezzeDeps = allDeps + interopDeps

        if (allMezzeDeps.isEmpty()) {
            println("✓ No Mezze dependencies")
            return
        }

        println("📦 Syncing ${allMezzeDeps.size} Mezze package(s)...")

        for ((name, url) in allMezzeDeps) {
            syncMezzePackage(name, url, mezzeDir, verbose)
        }
    }

    private fun syncPolyglotDependencies(
        polyglot: Map<String, PolyglotLanguageConfig>,
        depsDir: File,
        verbose: Boolean
    ) {
        for ((language, config) in polyglot) {
            println()
            println("🌐 Polyglot: ${language.capitalize()}")

            // Check if gu is available
            if (!isGuAvailable()) {
                println("  ❌ GraalVM Updater (gu) not found")
                println()
                println("  To use polyglot features, install GraalVM and ensure 'gu' is in your PATH:")
                println("    https://www.graalvm.org/downloads/")
                println()
                println("  Then run 'mezze sync' again.")
                continue
            }

            // Get interop library and read its provider config
            val interopDir = File(depsDir, "mezze/${extractPackageName(config.interop)}")
            val providerConfig = readProviderConfig(interopDir)

            if (providerConfig == null) {
                println("  ❌ Could not read polyglot.provider config from interop library")
                continue
            }

            // Check if GraalVM component is installed
            if (!isComponentInstalled(providerConfig.graalvmComponent)) {
                println("  Installing ${providerConfig.graalvmComponent}...")
                installComponent(providerConfig.graalvmComponent)
            } else {
                println("  ✓ ${providerConfig.graalvmComponent} already installed")
            }

            // Install extras (foreign packages)
            if (config.extras.isNotEmpty()) {
                val langDir = File(depsDir, language)
                langDir.mkdirs()

                println("  Installing extras...")
                installExtras(providerConfig, config.extras, langDir, verbose)
            }

            // Collect extras from wrapper packages
            val wrapperExtras = collectWrapperExtras(language, depsDir)
            if (wrapperExtras.isNotEmpty()) {
                val langDir = File(depsDir, language)
                println("  Installing wrapper dependencies...")
                installExtras(providerConfig, wrapperExtras, langDir, verbose)
            }
        }
    }

    private fun isGuAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("gu", "--version")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isComponentInstalled(component: String): Boolean {
        return try {
            val process = ProcessBuilder("gu", "list")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains(component)
        } catch (e: Exception) {
            false
        }
    }

    private fun installComponent(component: String) {
        try {
            val process = ProcessBuilder("gu", "install", component)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                println("  ✓ Installed $component")
            } else {
                println("  ❌ Failed to install $component (exit code: $exitCode)")
            }
        } catch (e: Exception) {
            println("  ❌ Failed to install $component: ${e.message}")
        }
    }

    private fun installExtras(
        providerConfig: PolyglotProviderConfig,
        extras: List<String>,
        destDir: File,
        verbose: Boolean
    ) {
        // Build install command
        val command = providerConfig.installCommand
            .replace("{dest}", destDir.absolutePath)
            .replace("{packages}", extras.joinToString(" "))

        if (verbose) {
            println("    Running: $command")
        }

        try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(destDir.parentFile)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                for (pkg in extras) {
                    println("    ✓ Installed $pkg")
                }
            } else {
                println("    ❌ Failed to install extras")
                if (verbose) {
                    println(output)
                }
            }
        } catch (e: Exception) {
            println("    ❌ Failed to run install command: ${e.message}")
        }
    }

    private fun readProviderConfig(interopDir: File): PolyglotProviderConfig? {
        val mezzeToml = File(interopDir, "Mezze.toml")
        if (!mezzeToml.exists()) return null

        return try {
            val content = mezzeToml.readText()
            // Parse [polyglot.provider] section
            // This is simplified - actual implementation would use TOML parser
            parseProviderConfig(content)
        } catch (e: Exception) {
            null
        }
    }

    private fun collectWrapperExtras(language: String, depsDir: File): List<String> {
        val mezzeDir = File(depsDir, "mezze")
        val extras = mutableListOf<String>()

        mezzeDir.listFiles()?.forEach { packageDir ->
            val mezzeToml = File(packageDir, "Mezze.toml")
            if (mezzeToml.exists()) {
                val foreignExtras = parseForeignExtras(mezzeToml, language)
                extras.addAll(foreignExtras)
            }
        }

        return extras
    }

    private fun parseForeignExtras(mezzeToml: File, language: String): List<String> {
        // Parse [foreign.{language}].packages from wrapper library
        // Simplified - actual implementation would use TOML parser
        return emptyList()
    }
}
```

## Directory Structure After Sync

```
project/
├── Mezze.toml
├── Mezze.lock
├── .deps/
│   ├── mezze/
│   │   ├── json-1.2.0/
│   │   ├── mezze-python-1.0.0/
│   │   └── mezze-numpy-1.0.0/
│   ├── python/
│   │   ├── numpy/
│   │   ├── pandas/
│   │   └── requests/
│   ├── node/
│   │   └── node_modules/
│   │       ├── lodash/
│   │       └── dayjs/
│   └── java/
│       └── gson-2.10.jar
└── src/
```

## Mezze.lock Extension

```kotlin
// cli/src/main/kotlin/com/mezze/language/cli/package/MezzeLock.kt

@Serializable
data class MezzeLock(
    val mezze: Map<String, LockedPackage> = emptyMap(),
    val python: Map<String, String>? = null,
    val javascript: Map<String, String>? = null,
    val java: Map<String, String>? = null,
    val r: Map<String, String>? = null,
    val ruby: Map<String, String>? = null
)

@Serializable
data class LockedPackage(
    val version: String,
    val checksum: String,
    val url: String? = null
)
```

Example Mezze.lock:

```toml
# Auto-generated by mezze sync - do not edit

[mezze]
json = { version = "1.2.0", checksum = "sha256:abc123..." }
mezze-python = { version = "1.0.0", checksum = "sha256:def456..." }
mezze-numpy = { version = "1.0.0", checksum = "sha256:ghi789..." }

[python]
numpy = "1.24.0"
pandas = "2.0.0"
requests = "2.28.2"

[javascript]
lodash = "4.17.21"
dayjs = "1.11.0"
```

## Error Messages

### gu not found

```
$ mezze sync

📦 Syncing 2 Mezze package(s)...
  ✓ Downloaded json-1.2.0
  ✓ Downloaded mezze-python-1.0.0

🌐 Polyglot: Python
  ❌ GraalVM Updater (gu) not found

  To use polyglot features, install GraalVM and ensure 'gu' is in your PATH:
    https://www.graalvm.org/downloads/

  Then run 'mezze sync' again.
```

### Component installation

```
$ mezze sync

📦 Syncing 2 Mezze package(s)...
  ✓ Downloaded json-1.2.0
  ✓ Downloaded mezze-python-1.0.0

🌐 Polyglot: Python
  Installing python...
  ✓ Installed python

  Installing extras...
    ✓ Installed numpy>=1.24.0
    ✓ Installed pandas==2.0.0

✓ Sync complete
```

### Foreign package installation failure

```
$ mezze sync

🌐 Polyglot: Python
  ✓ python already installed

  Installing extras...
    ✓ Installed numpy>=1.24.0
    ❌ Failed to install nonexistent-package
       pip: No matching distribution found for nonexistent-package

  Some packages failed to install. Run with --verbose for details.
```

## CLI Help

```
$ mezze sync --help

Usage: mezze sync [OPTIONS]

Download and install all dependencies

Options:
  --clean     Delete and re-download everything
  --verbose   Show detailed output
  --help      Show this help message

This command:
  1. Downloads Mezze packages to .deps/mezze/
  2. For each [polyglot.*] section:
     - Checks for GraalVM and gu
     - Installs GraalVM language component if missing
     - Installs foreign packages (extras)
  3. Updates Mezze.lock with all versions
```

## Build Integration

When running `mezze build` or `mezze run`, the runtime paths are set automatically:

```kotlin
// cli/src/main/kotlin/com/mezze/language/cli/commands/BuildCommand.kt

class BuildCommand {

    fun execute(args: List<String>) {
        // ... existing build logic ...

        // Set runtime paths for polyglot
        val depsDir = File(currentDir, ".deps")

        // Python
        if (File(depsDir, "python").exists()) {
            System.setProperty("python.PythonPath", File(depsDir, "python").absolutePath)
        }

        // JavaScript
        if (File(depsDir, "node").exists()) {
            System.setProperty("js.commonjs-require-cwd", File(depsDir, "node").absolutePath)
        }

        // R
        if (File(depsDir, "r").exists()) {
            System.setProperty("R_LIBS_USER", File(depsDir, "r").absolutePath)
        }

        // Ruby
        if (File(depsDir, "ruby").exists()) {
            System.setProperty("GEM_HOME", File(depsDir, "ruby").absolutePath)
            System.setProperty("GEM_PATH", File(depsDir, "ruby").absolutePath)
        }

        // Java - add JARs to classpath
        if (File(depsDir, "java").exists()) {
            val jars = File(depsDir, "java").walkTopDown()
                .filter { it.extension == "jar" }
                .map { it.absolutePath }
                .toList()
            // Add to classpath...
        }
    }
}
```

## .gitignore Template

Updated `mezze init` to include:

```gitignore
# Dependencies
.deps/

# Tooling cache
.mz/

# Lock file is committed
!Mezze.lock
```
