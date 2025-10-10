grammar BIAPIQuery;

query: (exprGroup | compoundExpr) (exprOp (exprGroup | compoundExpr))*;
exprGroup: lp=LPAREN (exprGroup | compoundExpr) (exprOp (exprGroup | compoundExpr))* rp=RPAREN;
compoundExpr: allowedExpr (exprOp allowedExpr)*;
allowedExpr:   inExpr |  basicExpr |  nullExpr | existsExpr | booleanExpr | notExpr | regexExpr | elemMatchExpr;
exprOp: op=(AND | OR);
existsExpr: field=STRING op=EXISTS;
booleanExpr: field=STRING op=(EQ | NEQ) value=(TRUE | FALSE);
inExpr : field=STRING op=(IN|NIN) value=valueListExpr;
valueListExpr:
    lp=LBRKT
    value=(STRING | QUOTED_STRING | VARIABLE | OID | REFERENCE) (COMMA value=(STRING | QUOTED_STRING | VARIABLE | OID | REFERENCE))*
    rp=RBRKT;

basicExpr: field=STRING op=(EQ|NEQ|LT|GT|LTE|GTE|EXISTS|IN) value=(STRING|VARIABLE|OID) #stringExpr
| field=STRING op=(EQ | NEQ ) value=QUOTED_STRING #quotedExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=NUMBER #numberExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=WHOLENUMBER #wholenumberExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=DATE #dateExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=DATETIME #dateTimeExpr
| field=STRING op=(EQ | NEQ) value=REFERENCE #referenceExpr;

notExpr: NOT allowedExpr;

regex: ((leftW=WILDCARD value=STRING rightW=WILDCARD)
| (leftw=WILDCARD value=QUOTED_STRING rightW=WILDCARD)
| (leftW=WILDCARD value=STRING)
| (leftW=WILDCARD value=QUOTED_STRING)
| (value=STRING rightW=WILDCARD)
| (value=QUOTED_STRING rightW=WILDCARD));

regexExpr: field=STRING op=(EQ | NEQ) regex;

nullExpr: field=STRING op=(EQ | NEQ) value=NULL;

elemMatchExpr: field=STRING op=EQ lp=LBRCE nested=query rp=RBRCE;


// Operators
EQ: ':';
NEQ: ':!';
LT: ':<';
GT: ':>';
LTE: ':<=';
GTE: ':>=';
EXISTS: ':~';
IN: ':^';
NIN: ':!^';
NULL: 'null';


// Logical
AND: '&&';
OR: '||';
NOT: '!!';
TRUE: 'TRUE' | 'true';
FALSE:'FALSE' | 'false';

// Grouping
RBRCE : '}';
LBRCE : '{';
RPAREN	: ')' ;
LPAREN	: '(' ;
RBRKT :']';
LBRKT :'[';
COMMA: ',';


// Special Values
WILDCARD: '*';
//WILDCARD: '%';
WILDCHAR:'?';



// Values
DATE: [0-9][0-9][0-9][0-9]'-'('12'|'11'|'10'|'0'[1-9])'-'[0-3][0-9];
DATETIME: [0-9][0-9][0-9][0-9]'-'('12'|'11'|'10'|'0'[1-9])'-'[0-3][0-9]'T'[0-2][0-9]':'[0-5][0-9]':'[0-5][0-9]('.'[0-9]+)?(('Z'|('+'|'-')[0-2][0-9]':'[0-5][0-9]))?;



WHOLENUMBER:'#'('-'?[0-9]+)
   {
     String s = getText();
     s = s.substring(1, s.length()); // strip the # off
     setText(s);
   }
   ;
NUMBER:'##'('-'?[0-9]+'.'[0-9]+)
   {
     String s = getText();
     s = s.substring(2, s.length()); // strip the ##
     setText(s);
   }
   ;
VARIABLE: '$''{' IDENT '}';

fragment IDENT: IDENT_START IDENT_PART*;
fragment IDENT_START: [a-zA-Z_];
fragment IDENT_PART: [a-zA-Z0-9_];
OID:[0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
       [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
       [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
       [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
       [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
       [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]
     ;

REFERENCE:'@@'OID
{
   String s = getText();
   s = s.substring(2, s.length()); // strip the @@ off
   setText(s);
 }
 ;

STRING: ([a-zA-Z0-9_.-]|' '|'\''|'/'|'@')+ ('&' STRING)*;

QUOTED_STRING
 : '"' (~[\r\n"] | '""')* '"'
   {
     String s = getText();
     s = s.substring(1, s.length() - 1); // strip the leading and trailing quotes
     s = s.replace("\"\"", "\""); // replace all double quotes with single quotes
     setText(s);
   }
 ;
