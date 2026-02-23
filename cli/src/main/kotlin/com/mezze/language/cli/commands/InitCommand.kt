package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.validatePackageName
import com.mezze.language.cli.`package`.validateVersion
import java.io.File

/**
 * Handle `mezze init` command to create a new Mezze package
 *
 * Usage:
 *   mezze init <name>              # Interactive mode - creates folder <name>
 *   mezze init <name> --lib        # Create library package
 *   mezze init <name> --app        # Create app package
 *   mezze init <name> --lib --app  # Create both library and app
 */
class InitCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        // Parse flags and project name
        val hasLibFlag = args.contains("--lib")
        val hasAppFlag = args.contains("--app")

        // Get project name (first non-flag argument)
        val projectName = args.firstOrNull { !it.startsWith("--") }

        if (projectName == null) {
            println("❌ Error: Please provide a project name")
            println()
            println("Usage: mez init <project-name> [--lib] [--app]")
            println()
            println("Examples:")
            println("  mez init my_project          # Interactive mode")
            println("  mez init my_project --lib    # Create library package")
            println("  mez init my_project --app    # Create app package")
            return
        }

        // Create target directory
        val targetDir = File(currentDir, projectName)

        // Check if folder already exists
        if (targetDir.exists()) {
            println("❌ Error: Folder '$projectName' already exists")
            println()
            println("Location: ${targetDir.absolutePath}")
            println()
            println("💡 Please choose a different name or remove the existing folder.")
            return
        }

        // Create the project folder
        if (!targetDir.mkdirs()) {
            println("❌ Error: Failed to create folder '$projectName'")
            return
        }

        println("Creating project in ${targetDir.absolutePath}")
        println()

        // Use targetDir instead of currentDir from here on
        val projectDir = targetDir
        val mezzeToml = File(projectDir, "Mezze.toml")

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

        // Get package name from project name
        val packageName = projectName.lowercase().replace("-", "_")

        try {
            validatePackageName(packageName)
        } catch (e: IllegalArgumentException) {
            println("❌ Error: Invalid directory name for package")
            println()
            println(e.message)
            println()
            println("💡 Please rename the directory to a valid package name (snake_case)")
            println("   Example: mkdir my_awesome_package && cd my_awesome_package && mezze init")
            return
        }

        val version = "0.1.0"

        // Create Mezze.toml
        createMezzeToml(mezzeToml, packageName, version, createLib, createApp)
        println("  ✅ Created Mezze.toml")

        // Create directory structure
        val srcDir = File(projectDir, "src")
        if (!srcDir.exists()) {
            srcDir.mkdirs()
            println("  ✅ Created src/")
        }

        // Create lib.mz if needed
        if (createLib) {
            val libFile = File(srcDir, "lib.mz")
            createLibFile(libFile, packageName)
            println("  ✅ Created src/lib.mz")
        }

        // Create app.mz if needed
        if (createApp) {
            val appFile = File(srcDir, "app.mz")
            createAppFile(appFile, packageName)
            println("  ✅ Created src/app.mz")
        }

        // Create .gitignore
        val gitignore = File(projectDir, ".gitignore")
        if (!gitignore.exists()) {
            createGitignore(gitignore)
            println("  ✅ Created .gitignore")
        }

        println()
        println("🎉 Package '$packageName' initialized successfully!")
        println()

        // Show next steps
        println("📝 Next steps:")
        println("   cd $projectName")
        if (createLib && !createApp) {
            println("   # Edit src/lib.mz to add your library functions")
            println("   mez check        # Type check your code")
        } else if (createApp && !createLib) {
            println("   # Edit src/app.mz to add your application logic")
            println("   mez dev          # Run your app")
            println("   mez build        # Create a standalone JAR")
        } else {
            println("   # Edit src/lib.mz to add your library functions")
            println("   # Edit src/app.mz to add your CLI tool logic")
            println("   mez dev          # Run your app")
            println("   mez build        # Create a standalone JAR")
        }
    }

    private fun createMezzeToml(file: File, packageName: String, version: String, hasLib: Boolean, hasApp: Boolean) {
        val content = buildString {
            appendLine("[package]")
            appendLine("name = \"$packageName\"")
            appendLine("version = \"$version\"")

            if (hasLib) {
                appendLine()
                appendLine("[lib]")
                appendLine("path = \"src/lib.mz\"")
            }

            if (hasApp) {
                appendLine()
                appendLine("[[app]]")
                appendLine("name = \"$packageName\"")
                appendLine("path = \"src/app.mz\"")
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
            # Mezze build artifacts
            *.jar
            Mezze.lock
            mezze_packages/

            # Mezze codebase (generated)
            .mez/

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
