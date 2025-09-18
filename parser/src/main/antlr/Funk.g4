grammar Funk;

// --- Parser Rules ---

program: (statement | NEWLINE)* EOF;

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
    LET Identifier (COLON typeAnnotation)? ASSIGN expression NEWLINE?
    ;

typeDeclaration:
    TYPE Identifier typeParams? ASSIGN typeDefinition NEWLINE?
    ;

effectDeclaration:
    EFFECT Identifier LBRACE NEWLINE? (effectOperation NEWLINE?)* RBRACE NEWLINE?
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
    ABILITY Identifier TypeVariable+ abilityDependencies? WHERE LBRACE NEWLINE? (abilityOperation NEWLINE?)* RBRACE NEWLINE?
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
    MATCH expression IS NEWLINE? (matchCase NEWLINE?)*
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
    unaryExpression ((MULTIPLY | DIVIDE) unaryExpression)*
    ;

unaryExpression:
    MINUS memberAccessExpression               // Unary minus (no double negatives)
    | memberAccessExpression
    ;

memberAccessExpression:
    primaryExpression (DOT Identifier)* (recordLiteral)?  // Allow function calls on member access
    ;

primaryExpression:
    literal
    | Identifier
    | PLACEHOLDER                          // Pipe placeholder
    | LPAREN expression RPAREN
    | recordLiteral
    | doExpression                         // Do block
    | lambdaExpression                     // Lambda function
    | performExpression                    // Perform expression
    | primaryExpression recordLiteral      // Function call or record update (disambiguated in AST builder)
    ;

performExpression:
    PERFORM performHandler (COMMA performHandler)* IN NEWLINE? expression
    ;

performHandler:
    Identifier (recordLiteral)?
    ;


lambdaExpression:
    recordType ARROW typeAnnotation? functionAbilityConstraints? ASSIGN expression    // Typed with constraints: { x: Int } -> String with Show Int = ...
    | recordType ARROW NEWLINE? expression                                            // Typed parameters: { x: Int, y: Int } -> x + y
    | untypedRecordParams ARROW NEWLINE? expression                                   // Untyped parameters: { x, y } -> x + y
    ;


untypedRecordParams:
    LBRACE (untypedParam (COMMA untypedParam)*)? RBRACE
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
    LBRACE (recordEntry (COMMA recordEntry)*)? (COMMA ELLIPSIS)? RBRACE
    | LBRACE expression PIPE_DELIM (recordUpdateEntry (COMMA recordUpdateEntry)*)? RBRACE
    ;

recordUpdateEntry:
    Identifier ASSIGN expression
    | Identifier DOT Identifier ASSIGN expression  // For nested updates like y.z = v
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
    Identifier                             // Simple type (Int, String, etc.)
    | TypeVariable                         // Type variable ('a, 'b, etc.)
    | LPAREN Identifier typeAnnotation+ RPAREN  // Type application (Maybe Int), etc.
    | recordType                           // Record type
    | variantType                          // Variant type
    | functionType                         // Function type
    ;

functionType:
    recordType ARROW typeAnnotation effectRow? functionAbilityConstraints?    // { params } -> ReturnType <Effects> with Abilities
    ;

effectRow:
    LT (Identifier (COMMA Identifier)*)? GT
    ;



literal:
    Integer
    | Float
    | String
    | Boolean
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

// Operators
PLUS: '+';
MINUS: '-';
MULTIPLY: '*';
DIVIDE: '/';
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

// Delimiters
LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
LBRACKET: '[';
RBRACKET: ']';
PIPE_DELIM: '|';
COLON: ':';
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
