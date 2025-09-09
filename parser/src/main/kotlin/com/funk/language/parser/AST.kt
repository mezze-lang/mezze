package com.funk.language.parser

// Base interface for all AST nodes
sealed interface ASTNode

// Top-level program structure
data class Program(val items: List<ProgramItem>) : ASTNode

sealed interface ProgramItem : ASTNode

// Module System
data class ModuleDeclaration(
    val name: String,
    val statements: List<ModuleStatement>
) : ProgramItem

sealed interface ModuleStatement : ASTNode

data class ExposeDeclaration(
    val items: List<ExposeItem>
) : ModuleStatement

data class ExposeItem(
    val kind: ExposeKind?,
    val name: String
) : ASTNode

enum class ExposeKind { TYPE }

data class AccessDeclaration(
    val moduleName: String,
    val items: List<AccessItem>
) : ModuleStatement

data class AccessItem(
    val name: String,
    val alias: String?
) : ASTNode

// Rust-style Module System
data class ModDeclaration(
    val moduleName: String
) : ProgramItem, ModuleStatement

data class AccessModuleDeclaration(
    val modulePath: List<String>,
    val alias: String?,
    val isExposed: Boolean = false
) : ProgramItem, ModuleStatement

// Declarations
sealed interface Declaration : ProgramItem, ModuleStatement, Statement

data class LetBinding(
    val variableName: String,
    val typeAnnotation: TypeAnnotation?,
    val expression: Expression,
    val isExposed: Boolean = false
) : Declaration

data class TypeDeclaration(
    val name: String,
    val typeParams: List<String>,
    val definition: TypeDefinition,
    val isExposed: Boolean = false
) : Declaration

sealed interface TypeDefinition : ASTNode




// Expressions
sealed interface Expression : ASTNode

data class LambdaExpression(
    val parameters: LambdaParameters,
    val body: Expression
) : Expression

sealed interface LambdaParameters : ASTNode

data class TypedParameters(
    val recordType: RecordType
) : LambdaParameters

data class UntypedParameters(
    val names: List<String>
) : LambdaParameters

data class PipeExpression(
    val left: Expression,
    val right: Expression
) : Expression

data class MatchExpression(
    val expression: Expression,
    val cases: List<MatchCase>
) : Expression

data class MatchCase(
    val pattern: Pattern,
    val expression: Expression
) : ASTNode

sealed interface Pattern : ASTNode

object WildcardPattern : Pattern

data class VariablePattern(val name: String) : Pattern

data class ConstructorPattern(
    val constructor: String,
    val recordPattern: RecordPattern?
) : Pattern

data class RecordPattern(
    val entries: List<RecordPatternEntry>
) : Pattern

data class RecordPatternEntry(
    val name: String,
    val pattern: Pattern?
) : ASTNode

data class HandleExpression(
    val expression: Expression,
    val cases: List<HandleCase>,
    val withClause: RecordLiteral?
) : Expression

data class HandleCase(
    val effectName: String,
    val pattern: RecordPattern?,
    val statements: List<Statement>,
    val result: Expression
) : ASTNode

data class IfExpression(
    val condition: Expression,
    val thenBranch: Expression,
    val elseBranch: Expression
) : Expression

data class BinaryOperation(
    val left: Expression,
    val operator: String,
    val right: Expression
) : Expression

data class FunctionCall(
    val function: Expression,
    val arguments: Expression
) : Expression

data class RecordLiteral(
    val entries: List<RecordEntry>,
    val restField: String? = null
) : Expression

data class RecordEntry(
    val name: String,
    val value: Expression? = null  // null means punned (name = name)
) : ASTNode

data class MemberAccess(
    val receiver: Expression,
    val member: String
) : Expression

data class RecordUpdate(
    val record: Expression,
    val updates: List<RecordEntry>
) : Expression

data class BlockExpression(
    val statements: List<Statement>,
    val result: Expression
) : Expression

data class DoExpression(
    val statements: List<Statement>,
    val result: Expression
) : Expression

data class Literal<T>(val value: T) : Expression

data class Identifier(val name: String) : Expression

object PlaceholderExpression : Expression


// Variant Construction
data class VariantConstruction(
    val constructor: String,
    val value: Expression? = null
) : Expression

// Statements
sealed interface Statement : ASTNode, ProgramItem

data class ExpressionStatement(
    val expression: Expression
) : Statement

data class ResumeStatement(
    val expression: Expression
) : Statement

// Types
sealed interface TypeAnnotation : ASTNode, TypeDefinition

data class SimpleType(val name: String) : TypeAnnotation

data class RecordType(
    val entries: List<RecordTypeEntry>,
    val restField: String? = null
) : TypeAnnotation

data class RecordTypeEntry(
    val name: String,
    val type: TypeAnnotation
) : ASTNode

data class VariantType(
    val constructors: List<VariantConstructor>,
    val isOpen: Boolean = false
) : TypeAnnotation

data class VariantConstructor(
    val name: String,
    val recordType: RecordType?
) : ASTNode

data class FunctionType(
    val paramType: RecordType,
    val returnType: TypeAnnotation
) : TypeAnnotation
