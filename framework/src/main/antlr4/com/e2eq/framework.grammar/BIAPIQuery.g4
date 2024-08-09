grammar BIAPIQuery;

query: (exprGroup | compoundExpr) (exprOp (exprGroup | compoundExpr))*;
exprGroup: lp=LPAREN (exprGroup | compoundExpr) (exprOp (exprGroup | compoundExpr))* rp=RPAREN;
compoundExpr: allowedExpr (exprOp allowedExpr)*;
allowedExpr: inExpr| basicExpr | existsExpr | booleanExpr;
exprOp: op=(AND | OR | NOT);
existsExpr: field=STRING op=EXISTS;
booleanExpr: field=STRING op=(EQ | NEQ) value=(TRUE | FALSE);
inExpr : field=STRING op=IN value=valueListExpr;
valueListExpr: lp=LBRKT value=(STRING | ',' | VARIABLE)+ rp=RBRKT;

basicExpr: field=STRING op=(EQ|NEQ|LT|GT|LTE|GTE|EXISTS|IN) value=(STRING|VARIABLE) #stringExpr
| field=STRING op=(EQ | NEQ ) value=QUOTED_STRING #quotedExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=NUMBER #numberExpr
| field=STRING op=(EQ | LT | GT | NEQ | LTE | GTE) value=WHOLENUMBER #wholenumberExpr;

// Operators
EQ: ':';
NEQ: ':!';
LT: ':<';
GT: ':>';
LTE: ':<=';
GTE: ':>=';
EXISTS: ':~';
IN: ':^';

// Logical
AND: '&&';
OR: '||';
NOT: '!!';
TRUE: 'TRUE';
FALSE:'FALSE';

// Grouping
RBRCE : '}';
LBRCE : '{';
RPAREN	: ')' ;
LPAREN	: '(' ;
RBRKT :']';
LBRKT :'[';

// Special Values
WILDCARD: '*';
WILDCHAR:'?';
VAR:'$';


// Values
DATE: [0-9][0-9][0-9][0-9]'-'('12'|'11'|'10'|'0'[1-9])'-'[0-3][0-9];
DATETIME: [0-9][0-9][0-9][0-9]'-'('12'|'11'|'10'|'0'[1-9])'-'[0-3][0-9]'T'[0-2][0-9]':'[0-5][0-9]':'[0-5][0-9];
WHOLENUMBER:'#'([0-9]+)
   {
     String s = getText();
     s = s.substring(1, s.length()); // strip the # off
     setText(s);
   }
   ;
NUMBER:'##'([0-9]+'.'[0-9]+)
   {
     String s = getText();
     s = s.substring(2, s.length());
     setText(s);
   }
   ;
VARIABLE:'$''{'('ownerId'|'principalId'|'resourceId'|'action'|'functionalDomain'|'pTenantId'|'pAccountId'|'rTenantId'|'rAccountId'|'realm'|'area')'}';
STRING: ([a-zA-Z0-9_.-]|' '|'\''|','|'/'|'@')+ ('&' STRING)*;
QUOTED_STRING
 : '"' (~[\r\n"] | '""')* '"'
   {
     String s = getText();
     s = s.substring(1, s.length() - 1); // strip the leading and trailing quotes
     s = s.replace("\"\"", "\""); // replace all double quotes with single quotes
     setText(s);
   }
 ;
