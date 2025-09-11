grammar Funk;

// --- Parser Rules ---

program: (statement | NEWLINE)* EOF;

statement:
    letBinding
    | typeDeclaration
    | expressionStatement
    ;


// Declarations
letBinding:
    LET Identifier (COLON typeAnnotation)? ASSIGN expression NEWLINE?
    ;

typeDeclaration:
    TYPE Identifier typeParams? ASSIGN typeDefinition NEWLINE?
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
    MATCH expression LBRACE NEWLINE? (matchCase NEWLINE?)* RBRACE
    | ifExpression
    ;

matchCase:
    pattern ARROW expression
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
    IF expression THEN expression ELSE expression
    | updateExpression
    ;

updateExpression:
    comparisonExpression (WITH recordLiteral)?
    ;

comparisonExpression:
    additiveExpression ((GT | LT | GTE | LTE | EQ | NEQ) additiveExpression)*
    ;

additiveExpression:
    multiplicativeExpression ((PLUS | MINUS | CONCAT) multiplicativeExpression)*
    ;

multiplicativeExpression:
    memberAccessExpression ((MULTIPLY | DIVIDE) memberAccessExpression)*
    ;

memberAccessExpression:
    primaryExpression (DOT Identifier)*
    ;

primaryExpression:
    literal
    | Identifier
    | PLACEHOLDER                          // Pipe placeholder
    | LPAREN expression RPAREN
    | recordLiteral
    | doExpression                         // Do block
    | lambdaExpression                     // Lambda function
    | primaryExpression recordLiteral      // Function call or record update (disambiguated in AST builder)
    ;

lambdaExpression:
    recordType ARROW NEWLINE? expression                    // Typed parameters: { x: Int, y: Int } -> x + y
    | untypedRecordParams ARROW NEWLINE? expression         // Untyped parameters: { x, y } -> x + y
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
    LBRACE (recordEntry (COMMA recordEntry)*)? (COMMA ELLIPSIS Identifier)? RBRACE
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
    Identifier typeAnnotation*             // Type application (Color, Maybe Int, etc.)
    | TypeVariable                         // Type variable ('a, 'b, etc.)
    | recordType                           // Record type
    | variantType                          // Variant type
    | functionType                         // Function type
    ;

functionType:
    recordType ARROW typeAnnotation         // { params } -> ReturnType
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
WITH: 'with';
IF: 'if';
THEN: 'then';
ELSE: 'else';
MATCH: 'match';
DO: 'do';

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