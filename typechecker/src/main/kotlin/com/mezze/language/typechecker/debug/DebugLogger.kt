package com.mezze.language.typechecker.debug

fun interface DebugSink {
    fun emit(category: DebugLogger.Category, message: String)
}

object DebugLogger {

    enum class Category {
        TypeChecker,
        ConstraintGenerator,
        ConstraintSolver,
        ExpressionConstraints,
        Effects,
        ModuleResolver,
        TypeValidator,
        Unification,
        Monomorphization,
        Compiler,
        Stdlib
    }

    @Volatile
    @PublishedApi
    internal var sink: DebugSink = DebugSink { _, message -> println(message) }
        private set

    fun installSink(newSink: DebugSink) {
        sink = newSink
    }

    fun resetSink() {
        sink = DebugSink { _, message -> println(message) }
    }

    private val isEnabled: Boolean by lazy {
        System.getProperty("mezze.typecheck.debug") == "true" ||
        System.getenv("MEZZE_TYPECHECK_DEBUG") == "true"
    }

    private val enabledCategories: Set<Category>? by lazy {
        val categoriesStr = System.getProperty("mezze.typecheck.debug.categories")
            ?: System.getenv("MEZZE_TYPECHECK_DEBUG_CATEGORIES")

        categoriesStr?.split(",")
            ?.mapNotNull { name ->
                Category.entries.find { it.name.equals(name.trim(), ignoreCase = true) }
            }
            ?.toSet()
    }

    fun isCategoryEnabled(category: Category): Boolean {
        if (!isEnabled) return false
        val categories = enabledCategories
        return categories == null || category in categories
    }

    inline fun debug(category: Category, message: () -> String) {
        if (isCategoryEnabled(category)) {
            sink.emit(category, "[DEBUG ${category.name}] ${message()}")
        }
    }

    inline fun debug(category: Category, label: String, message: () -> String) {
        if (isCategoryEnabled(category)) {
            sink.emit(category, "[DEBUG ${category.name}] $label: ${message()}")
        }
    }

    inline fun debugSection(category: Category, header: String, content: () -> List<String>) {
        if (isCategoryEnabled(category)) {
            sink.emit(category, "[DEBUG ${category.name}] === $header ===")
            content().forEach { line ->
                sink.emit(category, "[DEBUG ${category.name}]   $line")
            }
        }
    }
}
