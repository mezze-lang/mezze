package com.mezze.language.typechecker

sealed class SpreadSyntaxError : RuntimeException() {
    
        data class FieldAdditionNotAllowed(
        val fieldName: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot add new field '$fieldName' to record type. Only existing fields can be overridden. Available fields: ${availableFields.joinToString(", ")}"
    }
    
        data class NonRecordSpread(
        val actualType: String
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot spread non-record value of type $actualType"
    }
    
        data class NestedFieldNotFound(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot update field '$outerField.$innerField' because field '$outerField' does not exist in record type. Available fields: ${availableFields.joinToString(", ")}"
    }
    
        data class NestedFieldInNonRecord(
        val outerField: String,
        val innerField: String,
        val availableFields: List<String>
    ) : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot update field '$outerField.$innerField' because field '$innerField' does not exist in nested record type. Available fields: ${availableFields.joinToString(", ")}"
    }

        object MultipleSpreadsNotAllowed : SpreadSyntaxError() {
        override val message: String
            get() = "Cannot use multiple spreads in a single record literal. Only one spread is allowed."
    }
}