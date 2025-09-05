grammar Funk;

// --- Parser Rules ---

program: (statement | NEWLINE)* EOF;

statement:
    letBinding
    | typeDeclaration
    | effectDeclaration
    | abilityDeclaration
    | moduleDeclaration
    | exposeDeclaration
    | accessDeclaration
    | resumeStatement
    | expressionStatement
    ;

// Module System
moduleDeclaration:
    MODULE Identifier WHERE NEWLINE
    ;

exposeDeclaration:
    EXPOSE exposeList NEWLINE?
    ;

exposeList:
    exposeItem (COMMA exposeItem)*
    ;

exposeItem:
    (TYPE | EFFECT | ABILITY)? Identifier
    ;

accessDeclaration:
    ACCESS Identifier DOT LBRACE accessList RBRACE NEWLINE?
    ;

accessList:
    accessItem (COMMA accessItem)*
    ;

accessItem:
    Identifier (AS Identifier)?
    ;

// Declarations
letBinding:
    LET Identifier (COLON typeAnnotation)? ASSIGN expression NEWLINE?
    ;

typeDeclaration:
    TYPE Identifier typeParams? ASSIGN typeDefinition NEWLINE?
    ;

typeParams:
    Identifier+
    ;

typeDefinition:
    recordType
    | variantType
    ;

variantType:
    PIPE_DELIM variantConstructor (COMMA variantConstructor)* (COMMA ELLIPSIS)? PIPE_DELIM
    ;

variantConstructor:
    qualifiedIdentifier (recordType)?
    ;

qualifiedIdentifier:
    Identifier (DOT Identifier)*
    ;

effectDeclaration:
    EFFECT Identifier COLON recordType ARROW variantType NEWLINE?
    ;

abilityDeclaration:
    ABILITY Identifier WHERE NEWLINE
    (effectSignature)*
    ;

effectSignature:
    Identifier COLON recordType ARROW variantType NEWLINE?
    ;

resumeStatement:
    RESUME expression NEWLINE?
    ;

expressionStatement:
    expression NEWLINE?
    ;

// Expressions
expression:
    pipeExpression
    ;

pipeExpression:
    matchExpression (PIPE matchExpression)*
    ;

matchExpression:
    MATCH expression LBRACE NEWLINE? (matchCase NEWLINE?)* RBRACE
    | handleExpression
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

handleExpression:
    HANDLE expression LBRACE handleCase* RBRACE (WITH recordLiteral)?
    | ifExpression
    ;

handleCase:
    Identifier recordPattern? ARROW expression NEWLINE?
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
    | blockExpression
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

blockExpression:
    LBRACE (statement | NEWLINE)* expression RBRACE
    ;

doExpression:
    DO NEWLINE (doStatement NEWLINE)* expression
    ;

doStatement:
    letBinding
    | resumeStatement
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
    LBRACE (recordTypeEntry (COMMA recordTypeEntry)*)? (COMMA ELLIPSIS Identifier)? RBRACE
    ;

recordTypeEntry:
    Identifier COLON typeAnnotation
    ;

typeAnnotation:
    Identifier                             // Simple type
    | recordType                           // Record type
    | variantType                          // Variant type
    | functionType                         // Function type with effects
    | typeAnnotation LBRACKET effectList RBRACKET    // Simple type with effects
    ;

functionType:
    recordType ARROW variantType LBRACKET effectList RBRACKET    // { params } -> | results | [ effects ]
    ;

effectList:
    (Identifier (COMMA Identifier)*)?
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
HANDLE: 'handle';
RESUME: 'resume';
EFFECT: 'effect';
ABILITY: 'ability';
WHERE: 'where';
MODULE: 'module';
EXPOSE: 'expose';
ACCESS: 'access';
AS: 'as';
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

// Comments and Whitespace
Comment: '#' ~[\r\n]* -> skip;
WS: [ \t\r]+ -> skip;
NEWLINE: '\n' | '\r\n';