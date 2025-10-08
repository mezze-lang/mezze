grammar Funk;

// --- Parser Rules ---

program: useDeclaration* pubUseDeclaration* (statement | NEWLINE)* EOF;

statement:
    letBinding
    | typeDeclaration
    | effectDeclaration
    | implDeclaration
    | abilityDeclaration
    | implAbilityDeclaration
    | expressionStatement
    ;


// Declarations
letBinding:
    PUB? LET Identifier (COLON typeAnnotation)? ASSIGN NEWLINE? expression NEWLINE?
    ;

typeDeclaration:
    PUB? TYPE Identifier typeParams? ASSIGN typeDefinition NEWLINE?
    ;

effectDeclaration:
    PUB? EFFECT Identifier LBRACE NEWLINE? (effectOperation (COMMA NEWLINE? effectOperation)*)? RBRACE NEWLINE?
    ;

effectOperation:
    Identifier COLON recordType ARROW typeAnnotation effectRow?
    ;

implDeclaration:
    IMPL Identifier FOR Identifier performerParams? WHERE LBRACE NEWLINE? (performerOperation | NEWLINE)* RBRACE NEWLINE?
    ;

performerParams:
    LBRACE (performerParam (COMMA performerParam)*)? RBRACE
    ;

performerParam:
    Identifier COLON typeAnnotation
    ;

performerOperation:
    Identifier COLON recordType ARROW typeAnnotation effectRow? ASSIGN expression
    ;

abilityDeclaration:
    PUB? ABILITY Identifier TypeVariable+ abilityDependencies? WHERE LBRACE NEWLINE? (abilityOperation NEWLINE?)* RBRACE NEWLINE?
    ;

abilityConstraints:
    WHERE LBRACE (abilityConstraint (COMMA abilityConstraint)*)? RBRACE
    ;

abilityConstraint:
    Identifier TypeVariable
    ;

abilityDependencies:
    WITH abilityDependency (COMMA abilityDependency)*
    ;

abilityDependency:
    Identifier typeAnnotation*
    ;

functionAbilityConstraints:
    WITH functionAbilityConstraint (COMMA functionAbilityConstraint)*
    ;

functionAbilityConstraint:
    Identifier typeAnnotation*
    ;

abilityOperation:
    Identifier COLON recordType ARROW typeAnnotation
    ;

implAbilityDeclaration:
    IMPL Identifier Identifier+ WHERE LBRACE NEWLINE? (implAbilityOperation NEWLINE?)* RBRACE NEWLINE?
    ;

implAbilityOperation:
    Identifier COLON recordType ARROW typeAnnotation ASSIGN expression
    ;

// Module system declarations
useDeclaration:
    USE usePath (AS Identifier)? NEWLINE
    ;

pubUseDeclaration:
    PUB usePath (AS Identifier)? NEWLINE
    ;

usePath:
    moduleSegments                          // math::geometry
    | moduleSegments DOUBLE_COLON LBRACE useList RBRACE  // math::geometry::{area, Shape}
    ;

moduleSegments:
    Identifier (DOUBLE_COLON Identifier)*   // math or math::geometry
    ;

useList:
    useItem (COMMA useItem)*
    ;

useItem:
    Identifier (AS Identifier)?
    ;

typeParams:
    TypeVariable+
    ;

typeDefinition:
    recordType
    | variantType
    ;

variantType:
    PIPE_DELIM variantConstructor (COMMA variantConstructor)* (COMMA ELLIPSIS)? PIPE_DELIM
    ;

variantConstructor:
    Identifier (recordType)?
    ;


expressionStatement:
    expression NEWLINE?
    ;

// Expressions
expression:
    pipeOperatorExpression
    ;

pipeOperatorExpression:
    variantExpression (PIPE variantExpression)*
    ;

variantExpression:
    matchExpression
    ;

matchExpression:
    MATCH expression IS NEWLINE? matchCase (NEWLINE? matchCase)*
    | ifExpression
    ;

matchCase:
    patternList guardCondition? ARROW expression
    ;

patternList:
    pattern (PIPE_DELIM pattern)*
    ;

guardCondition:
    IF expression
    ;

pattern:
    WILDCARD                               // Wildcard
    | Identifier                           // Variable binding
    | Identifier recordPattern?            // Constructor pattern
    | recordPattern                        // Record pattern
    ;

recordPattern:
    LBRACE (recordPatternEntry (COMMA recordPatternEntry)*)? RBRACE
    ;

recordPatternEntry:
    Identifier (ASSIGN pattern)?
    ;


ifExpression:
    IF expression NEWLINE? THEN NEWLINE? expression NEWLINE? ELSE NEWLINE? expression
    | updateExpression
    ;

updateExpression:
    logicalOrExpression
    ;

logicalOrExpression:
    logicalAndExpression (OR logicalAndExpression)*
    ;

logicalAndExpression:
    comparisonExpression (AND comparisonExpression)*
    ;

comparisonExpression:
    additiveExpression ((GT | LT | GTE | LTE | EQ | NEQ) additiveExpression)*
    ;

additiveExpression:
    multiplicativeExpression ((PLUS | MINUS | CONCAT) multiplicativeExpression)*
    ;

multiplicativeExpression:
    unaryExpression ((MULTIPLY | DIVIDE | FRACTION | MODULO) unaryExpression)*
    ;

unaryExpression:
    MINUS memberAccessExpression               // Unary minus (no double negatives)
    | NOT memberAccessExpression               // Logical NOT
    | memberAccessExpression
    ;

memberAccessExpression:
    primaryExpression (DOT Identifier | LBRACKET expression RBRACKET)* (recordLiteral)?  // Allow member access and array indexing
    ;

primaryExpression:
    literal
    | qualifiedIdentifier
    | PLACEHOLDER                          // Pipe placeholder
    | LPAREN expression RPAREN
    | lambdaExpression                     // Lambda function (must come before recordLiteral to avoid ambiguity)
    | doExpression                         // Do block
    | recordLiteral                        // Record literal
    | performExpression                    // Perform expression
    | primaryExpression recordLiteral      // Function call or record update (disambiguated in AST builder)
    ;

qualifiedIdentifier:
    Identifier (DOUBLE_COLON Identifier)*  // Support both simple and qualified identifiers
    ;

performExpression:
    PERFORM performHandler (COMMA performHandler)* IN NEWLINE? expression
    ;

performHandler:
    Identifier (recordLiteral)?
    ;


lambdaExpression:
    recordType ARROW typeAnnotation functionAbilityConstraints? ASSIGN NEWLINE? expression    // Typed with return type and constraints: { x: Int } -> String with Show Int = ...
    | recordType ARROW functionAbilityConstraints ASSIGN NEWLINE? expression                  // Typed with just constraints: { x: Int } with Show Int = ...
    | recordType ARROW ASSIGN NEWLINE? expression                                             // Typed params only: { x: Int } = ...
    | recordType ARROW NEWLINE? expression                                                     // Typed parameters: { x: Int, y: Int } -> x + y
    | untypedRecordParams ARROW NEWLINE? expression                                            // Untyped parameters: { x, y } -> x + y
    ;


untypedRecordParams:
    LBRACE (untypedParam (COMMA untypedParam)*)? (COMMA ELLIPSIS)? RBRACE
    ;

untypedParam:
    Identifier
    ;

doExpression:
    DO NEWLINE (doStatement NEWLINE)* expression
    ;

doStatement:
    letBinding
    | expressionStatement
    ;

recordLiteral:
    LBRACE (recordLiteralEntry (COMMA recordLiteralEntry)*)? (COMMA ELLIPSIS)? RBRACE
    ;

recordUpdateEntry:
    Identifier ASSIGN expression
    | Identifier DOT Identifier ASSIGN expression  // For nested updates like y.z = v
    ;

recordLiteralEntry:
    ELLIPSIS expression             // Spread syntax: ...record
    | Identifier ASSIGN expression  // Full syntax: name = value
    | Identifier                     // Punned syntax: name (expands to name = name)
    ;

recordEntry:
    Identifier ASSIGN expression    // Full syntax: name = value
    | Identifier                     // Punned syntax: name (expands to name = name)
    ;

recordType:
    LBRACE (recordTypeEntry (COMMA recordTypeEntry)*)? (COMMA ELLIPSIS)? RBRACE
    ;

recordTypeEntry:
    Identifier COLON typeAnnotation
    ;

typeAnnotation:
    functionType                           // Function type (must come first to avoid ambiguity with TypeVariable/Identifier)
    | LPAREN Identifier typeAnnotation+ RPAREN  // Type application with parens (Option 'a), etc.
    | LPAREN typeAnnotation RPAREN         // Parenthesized type for disambiguation
    | recordType                           // Record type
    | variantType                          // Variant type
    | arrayListType                        // ArrayList type
    | Identifier nonIdentifierTypeAnnotation+   // Type application without parens: Set 'a, Map 'k 'v (but not Set String to avoid ambiguity with ability constraints)
    | Identifier                             // Simple type (Int, String, etc.)
    | TypeVariable                         // Type variable ('a, 'b, etc.)
    ;

nonIdentifierTypeAnnotation:
    functionType                           // Function type
    | LPAREN Identifier typeAnnotation+ RPAREN  // Type application with parens
    | LPAREN typeAnnotation RPAREN         // Parenthesized type
    | recordType                           // Record type
    | variantType                          // Variant type
    | arrayListType                        // ArrayList type
    | Identifier nonIdentifierTypeAnnotation+   // Nested type application
    | TypeVariable                         // Type variable ('a, 'b, etc.) - this is the key difference!
    ;

arrayListType:
    LBRACKET typeAnnotation RBRACKET       // [Int], [String], ['a], etc.
    ;

functionType:
    recordType functionAbilityConstraints? ARROW typeAnnotation effectRow? functionAbilityConstraints?    // { params } with Abilities -> ReturnType <Effects> with Abilities
    | simpleType ARROW typeAnnotation effectRow? functionAbilityConstraints?  // Shorthand: 'a -> 'b, Int -> String, etc.
    ;

simpleType:
    Identifier
    | TypeVariable
    | LPAREN Identifier typeAnnotation+ RPAREN  // Type application
    | LPAREN typeAnnotation RPAREN              // Parenthesized
    | arrayListType
    ;

effectRow:
    LT (Identifier (COMMA Identifier)*)? GT
    ;



literal:
    Integer
    | Float
    | String
    | Boolean
    | arrayListLiteral
    ;

arrayListLiteral:
    LBRACKET (expression (COMMA expression)* COMMA?)? RBRACKET
    ;

// --- Lexer Rules ---

// Keywords
LET: 'let';
TYPE: 'type';
IF: 'if';
THEN: 'then';
ELSE: 'else';
MATCH: 'match';
IS: 'is';
DO: 'do';
EFFECT: 'effect';
IMPL: 'impl';
FOR: 'for';
WHERE: 'where';
PERFORM: 'perform';
ABILITY: 'ability';
WITH: 'with';
IN: 'in';
USE: 'use';
PUB: 'pub';
AS: 'as';

// Operators
PLUS: '+';
MINUS: '-';
MULTIPLY: '*';
DIVIDE: '/';
FRACTION: '//';
MODULO: '%';
CONCAT: '++';
ASSIGN: '=';
PIPE: '|>';
ARROW: '->';
PLACEHOLDER: '@';
WILDCARD: '_';
ELLIPSIS: '...';

// Comparison operators
GT: '>';
LT: '<';
GTE: '>=';
LTE: '<=';
EQ: '==';
NEQ: '!=';

// Logical operators
AND: '&&';
OR: '||';
NOT: '!';

// Delimiters
LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
LBRACKET: '[';
RBRACKET: ']';
PIPE_DELIM: '|';
COLON: ':';
DOUBLE_COLON: '::';
COMMA: ',';
DOT: '.';

// Literals
Integer: [0-9]+;
Float: [0-9]+ '.' [0-9]+;
String: '"' (~["\r\n] | '\\' .)* '"';
Boolean: 'true' | 'false';

// Identifiers
Identifier: [a-zA-Z_] [a-zA-Z0-9_]*;
TypeVariable: '\'' [a-zA-Z] [a-zA-Z0-9_]*;

// Comments and Whitespace
Comment: '#' ~[\r\n]* -> skip;
WS: [ \t\r]+ -> skip;
NEWLINE: '\n' | '\r\n';
