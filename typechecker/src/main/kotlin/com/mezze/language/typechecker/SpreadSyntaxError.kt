package com.mezze.language.typechecker

/**
 * Spread syntax validation errors
 */
sealed class SpreadSyntaxError : RuntimeException() {
    
    /**
     * Error when attempting to add new fields through spread syntax
     */
    data class FieldAdditionNotAllowed(
        val fieldName: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot add new field '$fieldName' to record type. Only existing fields can be overridden. Available fields: ${availableFields.joinToString(", ")}"
    }
    
    /**
     * Error when attempting to spread a non-record type
     */
    data class NonRecordSpread(
        val actualType: String
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot spread non-record value of type $actualType"
    }
    
    /**
     * Error when attempting to update nested fields that don't exist
     */
    data class NestedFieldNotFound(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot update field '$outerField.$innerField' because field '$outerField' does not exist in record type. Available fields: ${availableFields.joinToString(", ")}"
    }
    
    /**
     * Error when attempting to update nested fields in non-record types
     */
    data class NestedFieldInNonRecord(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot update field '$outerField.$innerField' because field '$innerField' does not exist in nested record type. Available fields: ${availableFields.joinToString(", ")}"
    }

    /**
     * Error when attempting to use multiple spreads in a single record literal
     */
    object MultipleSpreadsNotAllowed : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot use multiple spreads in a single record literal. Only one spread is allowed."
    }
}