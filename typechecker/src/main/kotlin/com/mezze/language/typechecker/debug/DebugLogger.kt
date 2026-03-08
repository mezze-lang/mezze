package com.mezze.language.typechecker.debug

/**
 * Simple debug logging utility for the typechecker.
 *
 * Enable debug output by setting the system property:
 *   -Dmezze.typecheck.debug=true
 *
 * Or via environment variable:
 *   MEZZE_TYPECHECK_DEBUG=true
 *
 * Categories can be filtered by setting:
 *   -Dmezze.typecheck.debug.categories=TypeChecker,Unification
 */
object DebugLogger {

    /**
     * Debug categories for filtering output
     */
    enum class Category {
        TypeChecker,
        ConstraintGenerator,
        ConstraintSolver,
        ExpressionConstraints,
        Effects,
        ModuleResolver,
        TypeValidator,
        Unification,
        Monomorphization
    }

    // Check if debug is enabled (cached for performance)
    private val isEnabled: Boolean by lazy {
        System.getProperty("mezze.typecheck.debug") == "true" ||
        System.getenv("MEZZE_TYPECHECK_DEBUG") == "true"
    }

    // Categories to include (null means all)
    private val enabledCategories: Set<Category>? by lazy {
        val categoriesStr = System.getProperty("mezze.typecheck.debug.categories")
            ?: System.getenv("MEZZE_TYPECHECK_DEBUG_CATEGORIES")

        categoriesStr?.split(",")
            ?.mapNotNull { name ->
                Category.entries.find { it.name.equals(name.trim(), ignoreCase = true) }
            }
            ?.toSet()
    }

    /**
     * Check if debug logging is enabled
     */
    fun isDebugEnabled(): Boolean = isEnabled

    /**
     * Check if a specific category is enabled
     */
    fun isCategoryEnabled(category: Category): Boolean {
        if (!isEnabled) return false
        val categories = enabledCategories
        return categories == null || category in categories
    }

    /**
     * Log a debug message
     */
    inline fun debug(category: Category, message: () -> String) {
        if (isCategoryEnabled(category)) {
            println("[DEBUG ${category.name}] ${message()}")
        }
    }

    /**
     * Log a debug message with a label
     */
    inline fun debug(category: Category, label: String, message: () -> String) {
        if (isCategoryEnabled(category)) {
            println("[DEBUG ${category.name}] $label: ${message()}")
        }
    }

    /**
     * Log multiple lines for a debug section
     */
    inline fun debugSection(category: Category, header: String, content: () -> List<String>) {
        if (isCategoryEnabled(category)) {
            println("[DEBUG ${category.name}] === $header ===")
            content().forEach { line ->
                println("[DEBUG ${category.name}]   $line")
            }
        }
    }
}
