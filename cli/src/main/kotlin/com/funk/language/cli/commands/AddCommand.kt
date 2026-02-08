package com.funk.language.cli.commands

import com.akuleshov7.ktoml.Toml
import com.funk.language.cli.`package`.*
import java.io.File

/**
 * Handle `funk add` command to add dependencies to Funk.toml
 *
 * Usage:
 *   funk add <url>                # Add to [dependencies]
 *   funk add --dev <url>          # Add to [dev-dependencies]
 *   funk remove <package_name>    # Remove dependency
 */
class AddCommand {

    fun executeAdd(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val funkToml = File(currentDir, "Funk.toml")

        if (!funkToml.exists()) {
            println("❌ Error: No Funk.toml found in current directory")
            println()
            println("This doesn't look like a Funk package.")
            println("Location: ${currentDir.absolutePath}")
            println()
            println("💡 Run 'funk init' to create a new package first.")
            return
        }

        // Parse flags
        val isDevDependency = args.contains("--dev")
        val urlArg = args.firstOrNull { !it.startsWith("--") }

        if (urlArg == null) {
            println("❌ Error: Missing dependency URL")
            println()
            println("Usage:")
            println("  funk add <url>           # Add regular dependency")
            println("  funk add --dev <url>     # Add dev dependency")
            println()
            println("Example:")
            println("  funk add \"https://github.com/funk-lang/json/releases/download/v1.2.3/json.tar.gz#sha256:abc123...\"")
            return
        }

        // Validate and parse URL
        val (url, checksum) = try {
            parseDependencyUrl(urlArg)
        } catch (e: IllegalArgumentException) {
            println(e.message)
            return
        }

        val packageName = try {
            extractPackageNameFromUrl(urlArg)
        } catch (e: IllegalArgumentException) {
            println(e.message)
            return
        }

        // Read existing Funk.toml
        val tomlContent = funkToml.readText()
        val manifest = try {
            Toml.decodeFromString(FunkToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("❌ Error: Failed to parse Funk.toml")
            println()
            println("The manifest file has invalid TOML syntax.")
            println("Error: ${e.message}")
            println()
            println("💡 Check the syntax of your Funk.toml file.")
            return
        }

        // Check if package already exists
        val existingInDeps = manifest.dependencies.containsKey(packageName)
        val existingInDevDeps = manifest.devDependencies.containsKey(packageName)

        if (isDevDependency && existingInDevDeps) {
            println("⚠️  Package '$packageName' is already in [dev-dependencies]")
            println()
            println("Current URL: ${manifest.devDependencies[packageName]}")
            println("New URL:     $urlArg")
            println()
            print("Replace? [y/N]: ")
            val response = readLine()?.trim()?.lowercase()
            if (response != "y" && response != "yes") {
                println("Cancelled.")
                return
            }
        } else if (!isDevDependency && existingInDeps) {
            println("⚠️  Package '$packageName' is already in [dependencies]")
            println()
            println("Current URL: ${manifest.dependencies[packageName]}")
            println("New URL:     $urlArg")
            println()
            print("Replace? [y/N]: ")
            val response = readLine()?.trim()?.lowercase()
            if (response != "y" && response != "yes") {
                println("Cancelled.")
                return
            }
        }

        // Update Funk.toml by appending or modifying
        updateFunkToml(funkToml, packageName, urlArg, isDevDependency)

        if (isDevDependency) {
            println("✅ Added $packageName to [dev-dependencies]")
        } else {
            println("✅ Added $packageName to [dependencies]")
        }
        println()
        println("💡 Run 'funk sync' to download the dependency.")
    }

    fun executeRemove(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val funkToml = File(currentDir, "Funk.toml")

        if (!funkToml.exists()) {
            println("❌ Error: No Funk.toml found in current directory")
            println()
            println("💡 Run 'funk init' to create a new package first.")
            return
        }

        val packageName = args.firstOrNull()

        if (packageName == null) {
            println("❌ Error: Missing package name")
            println()
            println("Usage:")
            println("  funk remove <package_name>")
            println()
            println("Example:")
            println("  funk remove json")
            return
        }

        // Read existing Funk.toml
        val tomlContent = funkToml.readText()
        val manifest = try {
            Toml.decodeFromString(FunkToml.serializer(), tomlContent)
        } catch (e: Exception) {
            println("❌ Error: Failed to parse Funk.toml")
            println()
            println(e.message)
            return
        }

        // Check if package exists
        val inDeps = manifest.dependencies.containsKey(packageName)
        val inDevDeps = manifest.devDependencies.containsKey(packageName)

        if (!inDeps && !inDevDeps) {
            println("❌ Error: Package '$packageName' not found in dependencies")
            println()
            println("Available packages:")
            if (manifest.dependencies.isNotEmpty()) {
                println("  [dependencies]:")
                manifest.dependencies.keys.forEach { println("    - $it") }
            }
            if (manifest.devDependencies.isNotEmpty()) {
                println("  [dev-dependencies]:")
                manifest.devDependencies.keys.forEach { println("    - $it") }
            }
            return
        }

        // Remove from Funk.toml
        removeDependencyFromToml(funkToml, packageName)

        if (inDeps) {
            println("✅ Removed $packageName from [dependencies]")
        } else {
            println("✅ Removed $packageName from [dev-dependencies]")
        }
        println()
        println("💡 Run 'funk sync' to update the lock file.")
    }

    private fun updateFunkToml(funkToml: File, packageName: String, url: String, isDevDep: Boolean) {
        val lines = funkToml.readLines().toMutableList()

        val sectionName = if (isDevDep) "[dev-dependencies]" else "[dependencies]"
        val sectionIndex = lines.indexOfFirst { it.trim() == sectionName }

        if (sectionIndex == -1) {
            // Section doesn't exist, add it at the end
            if (lines.lastOrNull()?.isNotBlank() == true) {
                lines.add("")
            }
            lines.add(sectionName)
            lines.add("$packageName = \"$url\"")
        } else {
            // Section exists, find where to add/update
            var insertIndex = sectionIndex + 1

            // Skip to next non-comment line or next section
            while (insertIndex < lines.size) {
                val line = lines[insertIndex].trim()

                // Check if this is the package we want to update
                if (line.startsWith("$packageName =")) {
                    lines[insertIndex] = "$packageName = \"$url\""
                    funkToml.writeText(lines.joinToString("\n"))
                    return
                }

                // Stop at next section
                if (line.startsWith("[") && line.endsWith("]")) {
                    break
                }

                insertIndex++
            }

            // Package not found, insert at the end of the section
            lines.add(insertIndex, "$packageName = \"$url\"")
        }

        funkToml.writeText(lines.joinToString("\n"))
    }

    private fun removeDependencyFromToml(funkToml: File, packageName: String) {
        val lines = funkToml.readLines().toMutableList()

        // Remove all lines that start with the package name
        val filtered = lines.filterNot { line ->
            line.trim().startsWith("$packageName =")
        }

        funkToml.writeText(filtered.joinToString("\n"))
    }
}
