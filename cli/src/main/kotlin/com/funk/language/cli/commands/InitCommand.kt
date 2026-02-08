package com.funk.language.cli.commands

import com.funk.language.cli.`package`.validatePackageName
import com.funk.language.cli.`package`.validateVersion
import java.io.File

/**
 * Handle `funk init` command to create a new Funk package
 *
 * Usage:
 *   funk init              # Interactive mode
 *   funk init --lib        # Create library package
 *   funk init --app        # Create app package
 *   funk init --lib --app  # Create both library and app
 */
class InitCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        // Parse flags
        val hasLibFlag = args.contains("--lib")
        val hasAppFlag = args.contains("--app")

        // Check if Funk.toml already exists
        val funkToml = File(currentDir, "Funk.toml")
        if (funkToml.exists()) {
            println("❌ Error: Funk.toml already exists in this directory")
            println()
            println("This directory is already a Funk package.")
            println("Location: ${funkToml.absolutePath}")
            println()
            println("💡 If you want to create a new package, run 'funk init' in a different directory.")
            return
        }

        // Determine package type
        val createLib: Boolean
        val createApp: Boolean

        when {
            hasLibFlag && hasAppFlag -> {
                createLib = true
                createApp = true
                println("Creating library and app package...")
            }
            hasLibFlag -> {
                createLib = true
                createApp = false
                println("Creating library package...")
            }
            hasAppFlag -> {
                createLib = false
                createApp = true
                println("Creating app package...")
            }
            else -> {
                // Interactive mode - ask user
                println("What type of package do you want to create?")
                println()
                println("  1) Library (reusable code for other packages)")
                println("  2) App (executable program)")
                println("  3) Both (library with CLI tool)")
                println()
                print("Choice [1-3]: ")

                val choice = readLine()?.trim() ?: "1"

                when (choice) {
                    "1" -> {
                        createLib = true
                        createApp = false
                        println()
                        println("Creating library package...")
                    }
                    "2" -> {
                        createLib = false
                        createApp = true
                        println()
                        println("Creating app package...")
                    }
                    "3" -> {
                        createLib = true
                        createApp = true
                        println()
                        println("Creating library and app package...")
                    }
                    else -> {
                        println()
                        println("❌ Invalid choice. Defaulting to library package.")
                        createLib = true
                        createApp = false
                    }
                }
            }
        }

        println()

        // Get package name from directory name
        val packageName = currentDir.name.lowercase().replace("-", "_")

        try {
            validatePackageName(packageName)
        } catch (e: IllegalArgumentException) {
            println("❌ Error: Invalid directory name for package")
            println()
            println(e.message)
            println()
            println("💡 Please rename the directory to a valid package name (snake_case)")
            println("   Example: mkdir my_awesome_package && cd my_awesome_package && funk init")
            return
        }

        val version = "0.1.0"

        // Create Funk.toml
        createFunkToml(funkToml, packageName, version, createLib, createApp)
        println("  ✅ Created Funk.toml")

        // Create directory structure
        val srcDir = File(currentDir, "src")
        if (!srcDir.exists()) {
            srcDir.mkdirs()
            println("  ✅ Created src/")
        }

        // Create lib.funk if needed
        if (createLib) {
            val libFile = File(srcDir, "lib.funk")
            createLibFile(libFile, packageName)
            println("  ✅ Created src/lib.funk")
        }

        // Create app.funk if needed
        if (createApp) {
            val appFile = File(srcDir, "app.funk")
            createAppFile(appFile, packageName)
            println("  ✅ Created src/app.funk")
        }

        // Create .gitignore
        val gitignore = File(currentDir, ".gitignore")
        if (!gitignore.exists()) {
            createGitignore(gitignore)
            println("  ✅ Created .gitignore")
        }

        println()
        println("🎉 Package '$packageName' initialized successfully!")
        println()

        // Show next steps
        if (createLib && !createApp) {
            println("📝 Next steps:")
            println("   1. Add public functions to src/lib.funk")
            println("   2. Run 'funk check' to type check your code")
            println("   3. Other packages can import your library with: use $packageName")
        } else if (createApp && !createLib) {
            println("📝 Next steps:")
            println("   1. Edit src/app.funk to add your application logic")
            println("   2. Run 'funk exec' to execute your app")
            println("   3. Run 'funk compile' to create a standalone JAR")
        } else {
            println("📝 Next steps:")
            println("   1. Add public functions to src/lib.funk")
            println("   2. Edit src/app.funk to add your CLI tool logic")
            println("   3. Run 'funk exec' to execute your app")
            println("   4. Other packages can import your library with: use $packageName")
        }
    }

    private fun createFunkToml(file: File, packageName: String, version: String, hasLib: Boolean, hasApp: Boolean) {
        val content = buildString {
            appendLine("[package]")
            appendLine("name = \"$packageName\"")
            appendLine("version = \"$version\"")

            if (hasLib) {
                appendLine()
                appendLine("[lib]")
                appendLine("path = \"src/lib.funk\"")
            }

            if (hasApp) {
                appendLine()
                appendLine("[[app]]")
                appendLine("name = \"$packageName\"")
                appendLine("path = \"src/app.funk\"")
            }

            appendLine()
            appendLine("[dependencies]")

            appendLine()
            appendLine("[dev-dependencies]")
        }

        file.writeText(content)
    }

    private fun createLibFile(file: File, packageName: String) {
        val content = """
            pub let example = {} -> {
                message = "Hello from $packageName!"
            }
        """.trimIndent()

        file.writeText(content)
    }

    private fun createAppFile(file: File, packageName: String) {
        val content = """
            use std::effects::log::{println, Log, Console}

            let main = {} -> perform Console in do
                println { msg = "Hello from $packageName!" }
        """.trimIndent()

        file.writeText(content)
    }

    private fun createGitignore(file: File) {
        val content = """
            # Funk build artifacts
            Funk.lock
            funk_packages/

            # IDE
            .idea/
            .vscode/
            *.iml

            # OS
            .DS_Store
            Thumbs.db
        """.trimIndent()

        file.writeText(content)
    }
}
