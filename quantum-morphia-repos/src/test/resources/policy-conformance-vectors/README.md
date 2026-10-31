# Policy conformance vectors

Canonical inputs and expected decisions for `RuleContext` (the Java policy engine).
Any other implementation of the same policy semantics, for example an embedded
evaluator in another language, must pass these files unchanged. These files are
the source of truth: copies elsewhere are checked against their digest, and a
change here is made together with the code that changes the behavior.

Each `<name>.json` holds:

* `principal`: userId, roles, defaultRealm and dataDomain;
* `resource`: area, functionalDomain, action, resourceId (null for a list
  request) and ownerId;
* `rules`: name, header (identity, area, functionalDomain, action), body (realm,
  orgRefName, accountNumber, tenantId, dataSegment, ownerId, resourceId), effect,
  priority, finalRule, and optionally andFilterString, orFilterString, joinOp and
  excludedFields;
* `defaultEffect`;
* `expected`:
  * `finalEffect`;
  * `filterConstraints`: an ordered list of `{ruleName, andFilterString,
    orFilterString, joinOp}`;
  * `excludedFields`: in order, from `RuleContext.getExcludedFieldPaths`.

The request side of a match is
`realm:orgRefName:accountNumber:tenantId:dataSegment:identity:resourceId` (the
principal's data domain, the identity being matched in the owner position, and
`*` for a list request), matched case-insensitively against the rule's header
and body as wildcard patterns. The `scoped-*` vectors pin each body field.

Scripts (pre- and postconditions) are deliberately out of scope. An
implementation that cannot evaluate them must refuse a rule that has one; the
Java runner refuses one too, rather than building the rule without it.
`index.json` lists the vectors. `PolicyConformanceVectorTest` runs each of them.
