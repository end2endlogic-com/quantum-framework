# Policy-Driven Ontology Relationship Filtering & Edge Metadata Specification

**Status:** Architecture Specification & Implementation Guide  
**Date:** 2026-09-20  
**Scope:** Quantum Security Engine, Shield Policy Enforcement, BIAPI Query Compiler, Ontology Graph Model (`OntologyEdge` / `EdgeRecord`)  
**Related Documents:**
- `framework/docs/design/ontology-facet-segmentation-design-2026-09.md`
- `framework/docs/design/ontology-edge-schema-improvements.md`
- `framework/docs/design/ontology-facet-segmentation-design.html`

---

## 1. Executive Summary

In enterprise multi-tenant architectures, authorization decisions cannot be made solely by inspecting isolated entity attributes (e.g., `user.department == 'Sales'`). Complex business constraints require evaluating **relationships in the ontology graph** (e.g., *"Is this associate assigned to the organization that owns this account, in an ACTIVE status, with an APPROVER role?"*).

This specification details:
1. **How policies specify filters on ontology relationships** across declarative rules (`Rule`), tenancy policies (`DataDomainPolicy`), and procedural scripts (`ScriptHelpers`).
2. **The full spectrum of metadata currently associated with ontology edges** (`OntologyEdge` and `EdgeRecord`).
3. **High-impact architectural metadata extensions** (temporal validity, security classification, confidence scores, purpose-of-use tracking, cryptographic attestations, and mutual-exclusion constraints) that dramatically expand governance, compliance, and zero-trust capabilities.

---

## 2. Specifying Filters on Ontology Relationships in Policies

### 2.1 The Query Language Grammar

The BIAPI query parser (`BIAPIQuery.g4`) natively admits graph traversal operators with optional **edge property qualification blocks** (`{ edgeFilter }`):

| Operator Syntax | Semantics | Direction |
|:---|:---|:---:|
| `hasEdge(predicate, dst, { edgeFilter }?)` | Asserts target entity has an outgoing edge `(entity --p--> dst)` matching `edgeFilter`. | Outgoing |
| `hasOutgoingEdge(predicate, dst, { edgeFilter }?)` | Syntactic alias for `hasEdge` providing symmetry with incoming traversals. | Outgoing |
| `hasIncomingEdge(predicate, src, { edgeFilter }?)` | Asserts target entity has an incoming edge `(src --p--> entity)` matching `edgeFilter`. | Incoming |

#### Grammar Definition (`BIAPIQuery.g4`)
```antlr
hasEdgeExpr:
    HASEDGE LPAREN predicate=(STRING|TEXT|QUOTED_STRING|VARIABLE) COMMA 
    dst=(STRING|TEXT|QUOTED_STRING|VARIABLE|OID|REFERENCE) 
    (COMMA lp=LBRCE edgeFilter=query rp=RBRCE)? RPAREN;

hasIncomingEdgeExpr:
    HASINCOMGINEDGE LPAREN predicate=(STRING|TEXT|QUOTED_STRING|VARIABLE) COMMA 
    src=(STRING|TEXT|QUOTED_STRING|VARIABLE|OID|REFERENCE) 
    (COMMA lp=LBRCE edgeFilter=query rp=RBRCE)? RPAREN;
```

---

### 2.2 Declarative Security Rules (`Rule.java`)

Declarative access control in `quantum-framework` uses `Rule` objects configured in YAML, JSON, or datastore collections. Relationship filters are injected directly into `andFilterString` or `orFilterString`.

```yaml
id: 1042
name: "Regional Sales Manager Account Access"
description: "Allows account view if associate has an active PRIMARY manager edge to the owning organization"
securityURI: "sec://accounts/read"
effect: ALLOW
priority: 10
andFilterString: >
  hasEdge("managedByOrg", "${dcOrgRefName}", { role: "PRIMARY" && status: "ACTIVE" }) &&
  status:OPEN
excludedFields:
  - "financials.taxIdentifier"
  - "internalAuditNotes"
```

#### Dynamic Context Variable Binding
Rule filters support automatic `${var}` parameterization from `RuleContext` / `VariableBundle`:
- `${pTenantId}` / `${tenantId}`: Authenticated tenant identifier.
- `${dcOrgRefName}`: Organization reference name.
- `${dcAccountId}`: Account identifier.
- `${dcDataSegment}`: Data segment integer.
- `${userId}` / `${ownerId}`: Authenticated subject / resource owner.
- `${policyFilter}`: Tenant/facet baseline policy filter.

---

### 2.3 Tenancy & Data Domain Scoping (`DataDomainPolicyEntry.java`)

At the tenancy partition boundary, `DataDomainPolicyEntry` governs data reach across organizations and segments. A policy entry can mandate relationship qualifications that apply globally to any query within that tenant scope:

```json
{
  "functionalDomainString": "OrderManagement",
  "functionalActionString": "ViewOrders",
  "resolutionMode": "FROM_CREDENTIAL",
  "filter": "hasEdge('placedInOrg', '${dcOrgRefName}', { complianceLevel: 'SOC2' }) && jurisdiction:US",
  "facetFilters": {
    "clearance": "CONFIDENTIAL",
    "retentionTier": "HOT"
  }
}
```

The `DefaultDataDomainResolver` extracts this `filter`, publishing it as `${policyFilter}` into the `RuleContext` variable bundle to parameterize all subsequent query evaluations.

---

### 2.4 Programmatic & Script-Based Policies (`ScriptHelpers`)

In procedural policy rules executed in GraalJS / Nashorn runtime environments (`RuleScriptExecutor`), relationship filters can be checked programmatically:

```javascript
// Postcondition verification in JavaScript rule
var subjectId = context.principal.id;
var locationId = resource.assignedLocationId;

// 1. Literal Map Filter
if (!ScriptHelpers.hasEdge(subjectId, "canAccessLocation", locationId, {
    role: "SHIFT_SUPERVISOR",
    active: true
})) {
    return false; // Deny access
}

// 2. Functional Predicate Filter
var isAuthorized = ScriptHelpers.hasIncomingEdge("governedByOrg", resource.orgRefName, function(props) {
    return props.clearanceLevel >= 3 && props.emergencyOverride !== true;
});
if (!isAuthorized) {
    return false;
}
```

---

### 2.5 Dual-Tier Enforcement Engine

Policies specifying relationship filters are enforced identically across both persistence and memory tiers:

```
                          ┌────────────────────────┐
                          │   Policy Definition    │
                          │ (hasEdge with filters) │
                          └───────────┬────────────┘
                                      │
                 ┌────────────────────┴────────────────────┐
                 ▼                                         ▼
   ┌───────────────────────────┐             ┌───────────────────────────┐
   │    Persistence Tier       │             │      In-Memory Tier       │
   │  (QueryToFilterListener)  │             │ (QueryToPredicateJson)    │
   └─────────────┬─────────────┘             └─────────────┬─────────────┘
                 │                                         │
        ┌────────┴────────┐                       ┌────────┴────────┐
        ▼                 ▼                       ▼                 ▼
 ┌─────────────┐   ┌─────────────┐         ┌─────────────┐   ┌─────────────┐
 │ Index Scan  │   │ Aggregation │         │ Entity JSON │   │ Facts Map   │
 │ props.<key> │   │   Pipeline  │         │   (POJO)    │   │ (Resource)  │
 └─────────────┘   └─────────────┘         └─────────────┘   └─────────────┘
```

1. **Datastore Tier (`QueryToFilterListener`)**:
   - Translates `{ edgeFilter }` property references into MongoDB paths: `props.<key>`.
   - Pushes queries directly into compound index scans (`idx_domain_p_dst_props`).
   - In `$lookup` joins, `MongoAggregationCompiler` pushes edge restrictions into inner pipeline `$match` stages so unpermitted nodes are pruned before materialization.
2. **In-Memory Tier (`QueryToPredicateJsonListener` & `RuleFilterApplicabilityEvaluator`)**:
   - Isolates inner `{ edgeFilter }` execution using stack scoping markers (`opTypeMarkers`, `predStackMarkers`).
   - Reflectively resolves edge IDs via `OntologyEdgeRepo` or fails closed (`node -> false`).
   - Matches resolved IDs against root `id`, `_id` (`ObjectId`), `refName`, and nested `resource.*` attributes.
   - Bypasses root entity schema validation for open edge properties via `ValidatingQueryToPredicateJsonListener`.

---

## 3. Metadata Currently Associated with Ontology Relationships

In `OntologyEdge` (Morphia MongoDB document) and `EdgeRecord` (transport-neutral DTO), relationships currently store the following structured metadata:

| Attribute | Java Type | Purpose | Example |
|:---|:---|:---|:---|
| `dataDomain` | `DataDomain` | Multitenant partition boundary (`orgRefName`, `accountNum`, `tenantId`, `dataSegment`, `ownerId`). | `tenantId: "TENANT-1", orgRefName: "CORP"` |
| `src` / `srcType` | `String` | Source entity identity (ObjectId hex string or refName) and model class identifier. | `src: "usr_59a1", srcType: "Associate"` |
| `p` | `String` | Predicate / relationship type identifier. | `"managesTerritory"`, `"placedInOrg"` |
| `dst` / `dstType` | `String` | Destination entity identity and model class identifier. | `dst: "loc_88b2", dstType: "Location"` |
| `inferred` | `boolean` | True if edge was inferred by OWL/RDFS TBox reasoner rule chains. | `true` |
| `derived` | `boolean` | True if edge was computed dynamically by a `ComputedEdgeProvider`. | `true` |
| `props` | `Map<String, Object>` | Open property map containing edge qualifiers, status, and custom attributes. | `{"role": "PRIMARY", "weight": 85}` |
| `prov` | `Map<String, Object>` | General provenance metadata (source system, ingest run). | `{"sourceSystem": "SAP-HR", "batchId": "B-9"}` |
| `support` | `List<Support>` | Proof chain for derived edges (`ruleId` and contributing `pathEdgeIds`). | `ruleId: "transitive-manager", pathEdgeIds: [...]` |
| `ts` | `Date` | Timestamp of edge assertion or derivation. | `2026-09-20T18:30:00Z` |

---

## 4. High-Impact Missing Metadata: Analysis & Proposals

While `props` allows storing arbitrary untyped key/value pairs, formalizing key metadata dimensions into the ontology and policy framework produces major positive impacts on security, compliance, performance, and explainability.

---

### Proposal 1: Temporal Validity Windows (`validFrom` / `validTo`)
> **Architectural Pattern:** Bitemporal Graph Authorization  
> **Impact:** High (Eliminates batch deletion jobs, enables point-in-time compliance audits, and native expiration)

#### Problem
In real-world security, permissions are rarely permanent. Common enterprise requirements include:
- A contractor granted access expiring at midnight on Friday.
- A doctor assigned as "Acting Chief of Surgery" for a two-week rotation.
- Point-in-time regulatory audits: *"Did Alice have permission to view Account X on March 15th?"*

Currently, expiries require asynchronous cron jobs to delete edges, leading to synchronization lag and destroying the historical audit trail.

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected Date validFrom;  // Inclusive start time (null = beginning of time)
    protected Date validTo;    // Exclusive expiration time (null = forever)
}
```

#### Policy Usage Example
```
hasEdge("actingManager", "DEPT-5", { validFrom:<=##now && validTo:>##now })
```

#### System Benefit
- **Zero-Latency Expiration**: The moment `now >= validTo`, the query fails to match. No deletion batch job required.
- **Historical As-Of Queries**: Audit queries can evaluate authorization states as of any historical timestamp (`validFrom <= ##asOfDate && validTo > ##asOfDate`).

---

### Proposal 2: Security Classification & Compartment Labels (`securityLabel` / `handlingRestrictions`)
> **Architectural Pattern:** Mandatory Access Control (MAC) / Bell-LaPadula Graph Guards  
> **Impact:** High (Prevents graph-based side-channel leaks and satisfies defense/healthcare standards)

#### Problem
In classified, healthcare, or financial environments, **the existence of a relationship is itself classified**, even if both endpoints are public:
- An association between an undercover operative and a field office.
- An association between a patient and an oncology specialist (reveals medical condition under HIPAA).
- An association between a corporate executive and an acquisition target (insider trading risk).

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected String securityLabel;          // E.g., "UNCLASSIFIED", "CONFIDENTIAL", "SECRET"
    protected List<String> compartments;     // E.g., ["ITAR", "NOFORN", "PII", "PHI"]
}
```

#### Policy Usage Example
```
hasEdge("assignedToProject", "PROJECT-TITAN", { securityLabel: "SECRET" && compartments:^ ["ITAR"] })
```

#### System Benefit
- **Zero-Leakage Traversal**: If a principal lacks the `SECRET` clearance clearance level, the edge is withheld at the database index stage. The user cannot deduce the existence of the relationship via timing attacks or error codes.

---

### Proposal 3: Certainty & Confidence Score (`confidence: Double` [0.0 - 1.0])
> **Architectural Pattern:** Probabilistic & AI-Derived Graph Governance  
> **Impact:** High (Governs machine-learning inferences, automated entity resolution, and link prediction)

#### Problem
Modern enterprise ontologies increasingly incorporate edges generated by AI models, vector-similarity clustering, or heuristic entity resolution (e.g., *"Vendor 123 is 87% likely to be the same legal entity as Supplier 456"*). Treating AI inferences with the same trust as human-certified data introduces severe liability.

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected Double confidence;             // 0.0 to 1.0 (1.0 = human asserted / ground truth)
    protected String assertionMethod;        // "MANUAL", "RULE_DERIVED", "VECTOR_SIMILARITY", "LLM_INFERENCE"
}
```

#### Policy Usage Example
```
// Sensitive financial operations require high-confidence links
hasEdge("subsidiaryOf", "PARENT-CORP", { confidence:>=##0.95 && assertionMethod:"MANUAL" })
```

#### System Benefit
- **Risk-Tiered Access**: High-risk operations (e.g. fund disbursement, access grants) enforce `confidence: 1.0`, while low-risk exploratory discovery features permit `confidence:>=##0.70`.

---

### Proposal 4: Purpose of Use & Lawful Basis (`purposeOfUse` / `consentId`)
> **Architectural Pattern:** Purpose-Based Access Control (PBAC) / GDPR Article 6/9 Compliance  
> **Impact:** High (Eliminates compliance fines under GDPR, CCPA, and HIPAA)

#### Problem
Privacy regulations dictate that data can only be accessed for the **specific purpose** for which consent was granted or a contract exists. A doctor caring for a patient has an association for `TREATMENT`, but using that same relationship for `MARKETING` or `RESEARCH` is illegal unless explicitly covered.

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected List<String> allowedPurposes;   // E.g., ["TREATMENT", "BILLING", "ANALYTICS"]
    protected String consentId;              // Identifier of the registered patient consent record
}
```

#### Policy Usage Example
```
hasEdge("caresFor", "${patientId}", { allowedPurposes:^ ["${context.requestPurpose}"] })
```

#### System Benefit
- **Automated Privacy Gating**: Integrates purpose directly into query lowering. A billing clerk passing `requestPurpose=BILLING` gets results; a marketer passing `requestPurpose=MARKETING` is rejected at the index scan level.

---

### Proposal 5: Cryptographic Attestation & Lineage Signature (`attestation`)
> **Architectural Pattern:** Zero-Trust Verifiable Lineage / Supply Chain Security  
> **Impact:** High (Enables cross-tenant federated trust and tamper-evident audit trails)

#### Problem
In multi-tenant B2B ecosystems (e.g., Supplier &rarr; Manufacturer &rarr; Distributor), edges asserted by one tenant or third-party identity provider must be verifiable without trusting an intermediary database administrator.

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected EdgeAttestation attestation;
}

public class EdgeAttestation {
    protected String issuerDid;              // Decentralized Identifier or public key thumbprint
    protected String keyId;                  // Key identifier
    protected String signature;              // Ed25519 or ECDSA signature over canonical (domain + src + p + dst)
    protected String algorithm;              // "EdDSA", "ES256"
}
```

#### System Benefit
- **Tamper Evidence**: Proves an edge was generated by an authorized agent or hardware module and was not injected via database compromise.
- **Federated Verification**: Allows Tenant B to verify that Tenant A’s accredited auditor signed the compliance edge before granting access.

---

### Proposal 6: Structural Constraints: Cardinality & Mutual Exclusion
> **Architectural Pattern:** Separation of Duties (SoD) / Graph Schema Integrity  
> **Impact:** Medium-High (Automates SOX/ISO-27001 conflict-of-interest prevention)

#### Problem
Internal controls mandate Separation of Duties (SoD). For example:
- An employee who created a purchase order cannot approve it.
- A user cannot hold both the `Auditor` and `Auditee` relationships within the same department.

#### Recommended Edge Metadata
```java
public class OntologyEdge extends UnversionedBaseModel {
    // ...
    protected List<String> mutuallyExclusiveWith; // Predicates or roles that cannot co-exist with this edge
}
```

#### Policy Usage Example
```
// Enforce that approver does NOT have a conflicting creator edge to the same order
hasEdge("approvedBy", "${userId}") && !!hasEdge("createdBy", "${userId}")
```

---

## 5. Summary Matrix of Metadata Capabilities

| Dimension | Field Name | Type | Core Value Proposition |
|:---|:---|:---|:---|
| **Temporal** | `validFrom`, `validTo` | `Date` | Zero-job automatic expiration; bitemporal point-in-time compliance audits. |
| **Security Classification** | `securityLabel`, `compartments` | `String`, `List<String>` | Prevents graph structure side-channel leakage; enforces MAC & Bell-LaPadula. |
| **Probabilistic Trust** | `confidence`, `assertionMethod` | `Double`, `String` | Safely governs AI/ML inferences, LLM links, and vector entity resolution. |
| **Data Privacy** | `allowedPurposes`, `consentId` | `List<String>`, `String` | Enforces GDPR/HIPAA Purpose-Based Access Control (PBAC) at the datastore layer. |
| **Cryptographic Proof** | `attestation` | `EdgeAttestation` | Non-repudiation, tamper-evident cross-tenant federation, and supply-chain trust. |
| **Governance Constraints** | `mutuallyExclusiveWith` | `List<String>` | Automated Separation of Duties (SoD) under SOX/ISO-27001 regulatory frameworks. |

---

## 6. Implementation Checklist & Migration Strategy

1. **Phase 1: Ingestion & Storage (Non-Breaking)**:
   - Add optional typed fields (`validFrom`, `validTo`, `confidence`, `securityLabel`, `allowedPurposes`) to `OntologyEdge` and `EdgeRecord`.
   - Existing open properties in `props` continue to function without migration.
2. **Phase 2: Index Optimization**:
   - Update MongoDB compound indexes:
     ```java
     @Index(options = @IndexOptions(name = "idx_domain_p_dst_temporal"),
            fields = {
                @Field("dataDomain.tenantId"),
                @Field("p"),
                @Field("dst"),
                @Field("validFrom"),
                @Field("validTo")
            })
     ```
3. **Phase 3: Policy Grammar Macros**:
   - Add shorthand functions to `BIAPIQuery.g4` (e.g. `hasActiveEdge(p, dst)` expanding to `hasEdge(p, dst, { validFrom:<=##now && validTo:>##now })`).
4. **Phase 4: Audit Receipt Export**:
   - Include edge provenance, validity window, and confidence in the cryptographic decision receipt produced by `RuleFilterApplicabilityEvaluator`.
