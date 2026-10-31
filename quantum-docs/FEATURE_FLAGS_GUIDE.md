# Feature Flags in Quantum Framework: Complete Guide

## Overview

The Quantum Framework provides a sophisticated feature flag system that enables safe, gradual feature rollouts, A/B testing, and environment-specific configuration while integrating seamlessly with the authorization system.

---

## Overall Features

### 1. Flag Types
- **BOOLEAN**: Simple on/off toggles for features
- **MULTIVARIATE**: A/B testing and multi-variant experiments (e.g., "control", "v1", "v2")

### 2. Targeting & Segmentation
- **TargetRules**: Define which users/groups receive which variant
- **Attributes**: Target by userId, role, tenantId, location, plan, or custom attributes
- **Operators**: 
  - `equals`, `in`, `contains`, `startsWith`, `regex`
  - `hashMod` for percentage-based rollouts (e.g., 10% of users)

### 3. Environment Support
- Environment-specific flags (dev, staging, prod)
- Scope field for additional context-specific targeting
- JSON configuration for arbitrary feature settings

### 4. Integration with Permissions
- Feature flags complement (not replace) permission rules
- Can be referenced in `postconditionScript` within permission rules
- Enables safe, reversible rollouts with authorization enforcement

---

## Implementation Architecture

### Model Structure

**Location**: `quantum-models/src/main/java/com/e2eq/framework/model/general/FeatureFlag.java`

```java
@Entity
public class FeatureFlag extends BaseModel {
    private String description;           // Optional description for context
    private boolean enabled;              // Master on/off switch
    private FlagType type;                // BOOLEAN or MULTIVARIATE
    private List<String> variants;        // For multivariate: ["control", "v1", "v2"]
    private List<TargetRule> targetRules; // Targeting logic
    private Map<String, String> customAttributes; // Custom user properties
    private String environment;           // "dev", "staging", "prod"
    private String scope;                 // Optional scope qualifier
    private JsonNode jsonConfiguration;   // Arbitrary config data
    
    public enum FlagType {
        BOOLEAN, MULTIVARIATE
    }
}
```

### TargetRule Structure

```java
public static class TargetRule {
    private String attribute;        // e.g., "userId", "role", "tenantId"
    private String operator;         // e.g., "equals", "in", "hashMod"
    private List<String> values;     // Values to match
    private String variant;          // For multivariate: which variant to assign
}
```

### REST API

**Location**: `quantum-system-rest/src/main/java/com/e2eq/framework/rest/resources/security/FeatureFlagResource.java`

- **Base path**: `/features/flags`
- **Inherits full CRUD** from `BaseResource`
- **Operations**: list, get by id/refName, create, update, delete
- **Multi-tenant aware** via `X-Realm` header
- **Security**: Bearer JWT authentication required

### Repository

**Location**: `quantum-morphia-repos/src/main/java/com/e2eq/framework/model/persistent/morphia/FeatureFlagRepo.java`

- Extends `MorphiaRepo<FeatureFlag>`
- Provides MongoDB persistence with DataDomain filtering
- Automatic audit trails and validation

---

## Use Cases

### 1. Progressive Rollout by Tenant

Enable features for specific pilot customers first.

```json
{
  "refName": "NEW_DASHBOARD",
  "displayName": "New Dashboard UI",
  "description": "Redesigned dashboard with improved UX",
  "enabled": true,
  "type": "BOOLEAN",
  "environment": "prod",
  "targetRules": [
    { 
      "attribute": "tenantId", 
      "operator": "in", 
      "values": ["T100", "T200"] 
    }
  ]
}
```

**Use**: Roll out new dashboard to pilot customers T100 and T200 before general availability.

---

### 2. Role-Based Beta Access

Give beta testers early access with custom configuration.

```json
{
  "refName": "EXPORT_API",
  "displayName": "CSV Export API",
  "description": "Enable CSV export endpoint",
  "enabled": true,
  "type": "BOOLEAN",
  "environment": "prod",
  "targetRules": [
    { 
      "attribute": "role", 
      "operator": "equals", 
      "values": ["BETA"] 
    }
  ],
  "jsonConfiguration": { 
    "rateLimitPerMin": 60,
    "maxExportRows": 10000
  }
}
```

**Use**: Beta users get access to export API with rate limiting and row limits.

---

### 3. Percentage-Based A/B Testing

Roll out new features to a percentage of users for testing.

```json
{
  "refName": "SEARCH_V2",
  "displayName": "Search Algorithm v2",
  "description": "New search implementation with ML ranking",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["control", "v2"],
  "environment": "prod",
  "targetRules": [
    { 
      "attribute": "role", 
      "operator": "equals", 
      "values": ["BETA"], 
      "variant": "v2" 
    },
    { 
      "attribute": "userId", 
      "operator": "hashMod", 
      "values": ["10"], 
      "variant": "v2" 
    }
  ],
  "jsonConfiguration": { 
    "defaultVariant": "control",
    "trackingEnabled": true
  }
}
```

**Use**: All beta users + 10% of regular users get the new search algorithm.

**Variant Resolution**:
- Beta users → `v2` (explicit rule)
- 10% of regular users → `v2` (hashMod)
- All others → `control` (default)

---

### 4. Plan/Entitlement Tiers

Restrict premium features to paid plans.

```json
{
  "refName": "ADVANCED_ANALYTICS",
  "displayName": "Advanced Analytics",
  "description": "Premium analytics dashboard",
  "enabled": true,
  "type": "BOOLEAN",
  "environment": "prod",
  "targetRules": [
    { 
      "attribute": "plan", 
      "operator": "in", 
      "values": ["Pro", "Enterprise"] 
    }
  ],
  "jsonConfiguration": {
    "features": ["customReports", "dataExport", "apiAccess"]
  }
}
```

**Use**: Only Pro and Enterprise plan users can access advanced analytics.

---

### 5. Geographic Rollout

Enable region-specific features for compliance or testing.

```json
{
  "refName": "EU_COMPLIANCE_MODE",
  "displayName": "EU Compliance Features",
  "description": "GDPR-specific features for EU users",
  "enabled": true,
  "type": "BOOLEAN",
  "environment": "prod",
  "targetRules": [
    { 
      "attribute": "location", 
      "operator": "startsWith", 
      "values": ["EU"] 
    }
  ],
  "jsonConfiguration": {
    "dataRetentionDays": 90,
    "consentRequired": true
  }
}
```

**Use**: Enable GDPR-specific features only for EU-based users.

---

## How to Use Feature Flags

### 1. Creating a Feature Flag

```bash
curl -X POST http://localhost:8080/features/flags \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -H "X-Realm: system-com" \
  -d '{
    "refName": "EXPORT_API",
    "displayName": "CSV Export API",
    "description": "Enable CSV export endpoint",
    "enabled": true,
    "type": "BOOLEAN",
    "environment": "prod",
    "targetRules": [
      { "attribute": "role", "operator": "equals", "values": ["BETA"] }
    ],
    "jsonConfiguration": { "rateLimitPerMin": 60 }
  }'
```

---

### 2. Querying Feature Flags

**List all flags:**
```bash
curl -H "Authorization: Bearer $JWT" \
     -H "X-Realm: system-com" \
     "http://localhost:8080/features/flags/list?limit=50&sort=+refName"
```

**Get specific flag by refName:**
```bash
curl -H "Authorization: Bearer $JWT" \
     -H "X-Realm: system-com" \
     "http://localhost:8080/features/flags/refName/EXPORT_API"
```

**Filter by environment:**
```bash
curl -H "Authorization: Bearer $JWT" \
     -H "X-Realm: system-com" \
     "http://localhost:8080/features/flags/list?filter=environment:'prod'&enabled:true"
```

**Filter by type:**
```bash
curl -H "Authorization: Bearer $JWT" \
     "http://localhost:8080/features/flags/list?filter=type:'MULTIVARIATE'"
```

---

### 3. Updating a Feature Flag

```bash
curl -X POST http://localhost:8080/features/flags \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -H "X-Realm: system-com" \
  -d '{
    "id": "existing-flag-id",
    "refName": "EXPORT_API",
    "enabled": false
  }'
```

---

### 4. Deleting a Feature Flag

```bash
curl -X DELETE "http://localhost:8080/features/flags/refName/EXPORT_API" \
  -H "Authorization: Bearer $JWT" \
  -H "X-Realm: system-com"
```

---

## Integration with Permission Rules

Feature flags are evaluated and can be referenced in permission rule `postconditionScript`:

### Example: Require Feature Flag for Access

```yaml
- name: allow-export-when-flag-on
  description: Allow export only when feature flag is enabled for user
  securityURI:
    header:
      identity: ADMIN
      area: Reports
      functionalDomain: Export
      action: view
    body:
      realm: system-com
      accountNumber: '*'
      tenantId: '*'
      dataSegment: '*'
      ownerId: '*'
      resourceId: '*'
  postconditionScript: userProfile?.features?.EXPORT_API === true
  effect: ALLOW
  priority: 300
  finalRule: false
```

### How It Works:

1. **Feature flags are evaluated** for the current user/context
2. **Results are enriched** into `userProfile.features` map
3. **Permission rules check** the flag state before granting access
4. This provides **both** feature exposure control **and** authorization

### Multiple Roles Example:

```yaml
# Allow ADMIN role when flag is on
- name: allow-export-admin
  securityURI:
    header:
      identity: ADMIN
      area: Reports
      functionalDomain: Export
      action: view
    body:
      realm: system-com
      accountNumber: '*'
      tenantId: '*'
      dataSegment: '*'
      ownerId: '*'
      resourceId: '*'
  postconditionScript: userProfile?.features?.EXPORT_API === true
  effect: ALLOW
  priority: 300

# Allow REPORTER role when flag is on
- name: allow-export-reporter
  securityURI:
    header:
      identity: REPORTER
      area: Reports
      functionalDomain: Export
      action: view
    body:
      realm: system-com
      accountNumber: '*'
      tenantId: '*'
      dataSegment: '*'
      ownerId: '*'
      resourceId: '*'
  postconditionScript: userProfile?.features?.EXPORT_API === true
  effect: ALLOW
  priority: 300
```

---

## Feature Flags vs Permission Rules

### Feature Flags Control:
- ✅ Gradual, reversible rollouts
- ✅ A/B and multivariate experiments
- ✅ Environment gates (dev/staging/prod)
- ✅ Cohort targeting (tenants, roles, geography)
- ✅ Non-security configuration (limits, UI copy, thresholds)
- ✅ Temporary exposure control

### Permission Rules Control:
- ✅ Durable authorization logic
- ✅ Role-based access control (RBAC)
- ✅ Data domain constraints
- ✅ Compliance and least-privilege enforcement
- ✅ Fail-closed security enforcement
- ✅ Permanent access policies

---

## Recommended Pattern: Layered Enforcement

Use **grant-based permissions** (default DENY) + **feature flags** for exposure:

```
┌─────────────────────────────────────────────────────────┐
│ 1. REST API Annotations (@RolesAllowed, @PermitAll)    │
│    → Coarse-grained, code-level gates                   │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ 2. Feature Flags (exposure and variants)                │
│    → Is this capability ON? Which variant?              │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│ 3. Permission Rules (SecurityURI + postconditionScript) │
│    → Is this identity AUTHORIZED? Data domain filters   │
└─────────────────────────────────────────────────────────┘
                         ↓
                  ✅ Access Granted
```

### Decision Flow:

1. **Feature flag decides**: "Is this capability ON for this user?"
2. **Permission rule decides**: "Is this user AUTHORIZED to use it?"
3. **Both must pass** for access to be granted

### Example Scenario:

```
User: alice@acme.com
Role: REPORTER
Tenant: T100
Feature: EXPORT_API

Step 1: Check feature flag EXPORT_API
  - enabled: true
  - targetRules: role equals BETA → ❌ (alice is REPORTER, not BETA)
  - targetRules: tenantId in [T100, T200] → ✅ (alice is in T100)
  - Result: Feature is ON for alice

Step 2: Check permission rules
  - Rule: allow-export-reporter
  - identity: REPORTER → ✅ (alice has REPORTER role)
  - postconditionScript: userProfile?.features?.EXPORT_API === true → ✅
  - Result: ALLOW

Final: ✅ Access granted
```

---

## Multivariate Feature Flags: Deep Dive

### What Are Multivariate Flags?

Multivariate flags allow you to serve **different versions** of the same feature to different users, rather than just on/off. Think of it as "which flavor" instead of "yes or no."

### Terminology: Multivariate vs Variants

**Important**: These are NOT two different things. Variants are the building blocks of multivariate flags.

```
FeatureFlag
├── type: BOOLEAN          ← Simple on/off (no variants)
│   └── Result: true or false
│
└── type: MULTIVARIATE     ← Multiple options (HAS variants)
    └── variants: ["A", "B", "C"]
        └── Result: "A" or "B" or "C" or null
```

**Analogy**:
- **BOOLEAN flag** = Light switch (on/off)
- **MULTIVARIATE flag** = Thermostat (off/low/medium/high)
- **Variants** = The settings on the thermostat (low, medium, high)

**In Code**:
```java
public class FeatureFlag {
    private FlagType type;        // BOOLEAN or MULTIVARIATE
    private List<String> variants; // Only used when type = MULTIVARIATE
    
    public enum FlagType {
        BOOLEAN,      // Returns: enabled true/false
        MULTIVARIATE  // Returns: which variant string ("A", "B", "C")
    }
}
```

### Intent and Purpose

Multivariate flags are designed for:

#### 1. **A/B/C Testing (Experimentation)**
**Problem**: You have multiple ideas for how a feature should work, but don't know which performs best.  
**Solution**: Show variant A to 33% of users, variant B to 33%, variant C to 34%, then measure which wins.

**Example**: Three different checkout flows—measure which has the highest conversion rate.

#### 2. **Gradual Feature Rollout (Progressive Enhancement)**
**Problem**: A complex feature is risky to launch all at once.  
**Solution**: Roll out incrementally—basic version first, then intermediate, then full.

**Example**: AI assistant with variants: `off` → `basic` (autocomplete) → `advanced` (+code generation) → `full` (+refactoring)

#### 3. **Personalization (Cohort-Specific Experiences)**
**Problem**: Different user segments need different experiences.  
**Solution**: Serve tailored variants based on plan, location, role, or tenant.

**Example**: Free users see "value emphasis" pricing, trial users see "discount focus" with urgency, enterprise sees "custom quote."

#### 4. **Configuration Management (Environment-Specific Behavior)**
**Problem**: Same feature needs different settings per environment or customer.  
**Solution**: Use variants to encode different configurations without separate flags.

**Example**: Query optimizer with variants: `standard` (default), `aggressive` (large tenants), `conservative` (strict consistency), `adaptive` (auto-tuning)

#### 5. **Canary Deployments (Risk Mitigation)**
**Problem**: New implementation might have bugs; need to limit blast radius.  
**Solution**: Route 5% to new variant, 95% to old; if metrics degrade, kill the experiment.

**Example**: New search algorithm to 5% of users via `hashMod`, monitor error rates and latency.

---

### When to Use BOOLEAN vs MULTIVARIATE

| Use BOOLEAN When | Use MULTIVARIATE When |
|------------------|----------------------|
| Simple on/off toggle | Testing multiple implementations |
| Feature is ready for all | Need to compare performance |
| No experimentation needed | Gradual rollout with stages |
| Binary decision (yes/no) | Different configs per cohort |
| Kill switch for incidents | Personalization by segment |
| **Returns**: true/false | **Returns**: "variantA"/"variantB"/"variantC" |

**Rule of Thumb**: If you're asking "should we enable X?", use BOOLEAN. If you're asking "which version of X is best?", use MULTIVARIATE.

---

### What Variants Actually Are

Variants are the **named options** you want to test or roll out:

```json
// BOOLEAN flag - no variants needed
{
  "refName": "DARK_MODE",
  "type": "BOOLEAN",
  "enabled": true
  // User gets: feature ON or OFF
}

// MULTIVARIATE flag - variants define the options
{
  "refName": "THEME",
  "type": "MULTIVARIATE",
  "enabled": true,
  "variants": ["light", "dark", "auto", "highContrast"]
  // User gets: "light" OR "dark" OR "auto" OR "highContrast"
}
```

**Key Point**: The `variants` array is the list of possible values a user can receive. Each user gets assigned to exactly ONE variant.

---

### How Variants Are Assigned

Variants are assigned via **targetRules**:

```json
{
  "refName": "CHECKOUT_FLOW",
  "type": "MULTIVARIATE",
  "variants": ["standard", "express", "oneClick"],  // ← Define possible variants
  "targetRules": [                                    // ← Assign users to variants
    {
      "attribute": "plan",
      "operator": "equals",
      "values": ["Premium"],
      "variant": "oneClick"        // ← Premium users get "oneClick"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["50"],
      "variant": "express"          // ← 50% of others get "express"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "standard"   // ← Everyone else gets "standard"
  }
}
```

**Result for different users**:
- Premium user → `"oneClick"`
- Regular user (hash < 50) → `"express"`
- Regular user (hash ≥ 50) → `"standard"`

---

### Why Not Just Use Multiple Boolean Flags?

**Question**: Why not create three separate boolean flags: `CHECKOUT_STANDARD`, `CHECKOUT_EXPRESS`, `CHECKOUT_ONECLICK`?

**Answer**: Because you need **mutual exclusivity** and **consistent assignment**.

**Problem with multiple booleans**:
```json
// User could get multiple flags enabled = conflict!
{"refName": "CHECKOUT_STANDARD", "enabled": true}   // ✓ ON
{"refName": "CHECKOUT_EXPRESS", "enabled": true}    // ✓ ON  ← CONFLICT!
{"refName": "CHECKOUT_ONECLICK", "enabled": false}  // ✗ OFF

// Which checkout flow should the user see? 🤷
```

**Solution with multivariate + variants**:
```json
{
  "refName": "CHECKOUT_FLOW",
  "type": "MULTIVARIATE",
  "variants": ["standard", "express", "oneClick"]
}

// User gets exactly ONE variant: "express"
// No conflicts possible ✓
```

---

### Summary: The Relationship

```
FeatureFlag (the container)
  ├── type: MULTIVARIATE (the flag type)
  │
  ├── variants: ["A", "B", "C"] (the possible options)
  │
  ├── targetRules: [...] (the assignment logic)
  │     └── Each rule specifies WHICH variant to assign
  │
  └── jsonConfiguration.defaultVariant: "A" (the fallback)

Result: Each user gets assigned to exactly ONE variant
```

**In Plain English**:
- **Multivariate** = "This flag has multiple versions"
- **Variants** = "Here are the versions: A, B, C"
- **TargetRules** = "Give version A to these users, B to those users"
- **DefaultVariant** = "Everyone else gets version C"

---

### Complete Example Showing the Relationship

**❌ Anti-pattern (Multiple Boolean Flags)**:
```json
// DON'T: Create separate flags for each variant
{"refName": "SEARCH_V1", "enabled": false}
{"refName": "SEARCH_V2", "enabled": true}
{"refName": "SEARCH_V3", "enabled": false}
```
**Problems**: 
- User could get multiple variants (conflict)
- Hard to ensure mutual exclusivity
- Difficult to track experiment cohorts
- No guarantee of even distribution

**✅ Correct (Single Multivariate Flag)**:
```json
{
  "refName": "SEARCH_ALGORITHM",
  "type": "MULTIVARIATE",
  "variants": ["v1", "v2", "v3"],
  "targetRules": [
    {"attribute": "userId", "operator": "hashMod", "values": ["33"], "variant": "v1"},
    {"attribute": "userId", "operator": "hashMod", "values": ["66"], "variant": "v2"}
  ],
  "jsonConfiguration": {"defaultVariant": "v3"}
}
```
**Benefits**:
- Each user gets exactly one variant
- Guaranteed mutual exclusivity
- Consistent assignment (same user = same variant)
- Clean experiment tracking
- Easy to adjust distribution

---

### Real-World Scenario: E-commerce Checkout Optimization

**Business Goal**: Increase checkout completion rate

**Hypothesis**: Reducing steps will improve conversion

**Approach**: Test three checkout experiences

```json
{
  "refName": "CHECKOUT_FLOW",
  "type": "MULTIVARIATE",
  "variants": ["fourStep", "twoStep", "oneClick"],
  "targetRules": [
    // VIP customers get best experience immediately
    {"attribute": "plan", "operator": "equals", "values": ["VIP"], "variant": "oneClick"},
    
    // Split regular users evenly for testing
    {"attribute": "userId", "operator": "hashMod", "values": ["33"], "variant": "fourStep"},
    {"attribute": "userId", "operator": "hashMod", "values": ["66"], "variant": "twoStep"}
  ],
  "jsonConfiguration": {
    "defaultVariant": "oneClick",
    "experimentId": "CHECKOUT-2024-Q1",
    "metrics": ["completionRate", "timeToComplete", "abandonmentRate"],
    "variants": {
      "fourStep": {"steps": 4, "description": "Current flow"},
      "twoStep": {"steps": 2, "description": "Consolidated"},
      "oneClick": {"steps": 1, "description": "Express", "requiresSavedPayment": true}
    }
  }
}
```

**Outcome Path**:
1. **Week 1-2**: Collect data from 33/33/34 split
2. **Week 3**: Analyze—`twoStep` wins with 15% higher completion
3. **Week 4**: Shift to 10/80/10 split (validate winner)
4. **Week 5**: Roll out `twoStep` to 100% (update defaultVariant)
5. **Week 6**: Remove flag, make `twoStep` the permanent implementation

---

### Variant Assignment Logic

When evaluating a multivariate flag:

1. **Check each targetRule in order**
2. **First matching rule with a variant wins**
3. **If no rule matches, use defaultVariant from jsonConfiguration**
4. **If no defaultVariant, feature is considered OFF**

### Example 1: Three-Way UI Experiment

Test three different checkout flows:

```json
{
  "refName": "CHECKOUT_FLOW",
  "displayName": "Checkout Flow Experiment",
  "description": "A/B/C test of checkout UX",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["control", "oneClick", "progressive"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "tenantId",
      "operator": "equals",
      "values": ["T_VIP"],
      "variant": "oneClick"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["33"],
      "variant": "oneClick"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["66"],
      "variant": "progressive"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "control",
    "experimentId": "EXP-2024-Q1-CHECKOUT",
    "variants": {
      "control": {
        "description": "Current multi-step checkout",
        "steps": 4
      },
      "oneClick": {
        "description": "Amazon-style one-click",
        "steps": 1,
        "requiresSavedPayment": true
      },
      "progressive": {
        "description": "Progressive disclosure",
        "steps": 2,
        "showSummary": true
      }
    }
  }
}
```

**Distribution**:
- VIP tenant → `oneClick` (100%)
- 33% of regular users → `oneClick`
- 33% of regular users → `progressive`
- 34% of regular users → `control`

---

### Example 2: Pricing Tier Experiment

Test different pricing displays by plan:

```json
{
  "refName": "PRICING_DISPLAY",
  "displayName": "Pricing Display Variants",
  "description": "Test pricing presentation strategies",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["standard", "valueEmphasis", "discountFocus"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "plan",
      "operator": "equals",
      "values": ["Free"],
      "variant": "valueEmphasis"
    },
    {
      "attribute": "plan",
      "operator": "equals",
      "values": ["Trial"],
      "variant": "discountFocus"
    },
    {
      "attribute": "location",
      "operator": "startsWith",
      "values": ["US"],
      "variant": "valueEmphasis"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "standard",
    "variants": {
      "standard": {
        "showMonthlyPrice": true,
        "showAnnualPrice": false,
        "highlightSavings": false
      },
      "valueEmphasis": {
        "showMonthlyPrice": true,
        "showAnnualPrice": true,
        "highlightSavings": false,
        "showFeatureComparison": true
      },
      "discountFocus": {
        "showMonthlyPrice": true,
        "showAnnualPrice": true,
        "highlightSavings": true,
        "showCountdown": true
      }
    }
  }
}
```

**Variant Assignment**:
- Free plan users → `valueEmphasis` (encourage upgrade)
- Trial users → `discountFocus` (urgency)
- US users → `valueEmphasis` (market preference)
- Others → `standard`

---

### Example 3: Feature Rollout with Staged Variants

Gradually roll out feature complexity:

```json
{
  "refName": "AI_ASSISTANT",
  "displayName": "AI Assistant Features",
  "description": "Staged rollout of AI capabilities",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["off", "basic", "advanced", "full"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "role",
      "operator": "equals",
      "values": ["INTERNAL"],
      "variant": "full"
    },
    {
      "attribute": "tenantId",
      "operator": "in",
      "values": ["T_PILOT_1", "T_PILOT_2"],
      "variant": "advanced"
    },
    {
      "attribute": "plan",
      "operator": "in",
      "values": ["Pro", "Enterprise"],
      "variant": "basic"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["5"],
      "variant": "basic"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "off",
    "variants": {
      "off": {
        "enabled": false
      },
      "basic": {
        "enabled": true,
        "features": ["autocomplete", "suggestions"],
        "maxRequestsPerDay": 50
      },
      "advanced": {
        "enabled": true,
        "features": ["autocomplete", "suggestions", "codeGeneration"],
        "maxRequestsPerDay": 200
      },
      "full": {
        "enabled": true,
        "features": ["autocomplete", "suggestions", "codeGeneration", "refactoring", "debugging"],
        "maxRequestsPerDay": -1
      }
    }
  }
}
```

**Rollout Strategy**:
1. Internal users → `full` (dogfooding)
2. Pilot tenants → `advanced` (early adopters)
3. Pro/Enterprise plans → `basic` (paid feature)
4. 5% of free users → `basic` (growth experiment)
5. Everyone else → `off`

---

### Example 4: Regional Content Variants

Serve different content by geography:

```json
{
  "refName": "HOMEPAGE_CONTENT",
  "displayName": "Homepage Content Variants",
  "description": "Localized homepage experiences",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["global", "us", "eu", "apac"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "location",
      "operator": "startsWith",
      "values": ["US"],
      "variant": "us"
    },
    {
      "attribute": "location",
      "operator": "startsWith",
      "values": ["EU"],
      "variant": "eu"
    },
    {
      "attribute": "location",
      "operator": "in",
      "values": ["CN", "JP", "KR", "SG", "AU"],
      "variant": "apac"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "global",
    "variants": {
      "global": {
        "heroImage": "/images/hero-global.jpg",
        "ctaText": "Get Started",
        "testimonials": "global"
      },
      "us": {
        "heroImage": "/images/hero-us.jpg",
        "ctaText": "Start Free Trial",
        "testimonials": "us",
        "showPricing": true
      },
      "eu": {
        "heroImage": "/images/hero-eu.jpg",
        "ctaText": "Begin Your Journey",
        "testimonials": "eu",
        "showGDPRBadge": true
      },
      "apac": {
        "heroImage": "/images/hero-apac.jpg",
        "ctaText": "Start Now",
        "testimonials": "apac",
        "showLocalPartners": true
      }
    }
  }
}
```

---

### Example 5: Performance Optimization Variants

Test different performance strategies:

```json
{
  "refName": "QUERY_OPTIMIZER",
  "displayName": "Query Optimization Strategy",
  "description": "Test different query optimization approaches",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["standard", "aggressive", "conservative", "adaptive"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "tenantId",
      "operator": "in",
      "values": ["T_LARGE_1", "T_LARGE_2"],
      "variant": "aggressive"
    },
    {
      "attribute": "plan",
      "operator": "equals",
      "values": ["Enterprise"],
      "variant": "adaptive"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["25"],
      "variant": "aggressive"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["50"],
      "variant": "conservative"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "standard",
    "variants": {
      "standard": {
        "cacheEnabled": true,
        "cacheTTL": 300,
        "indexHints": false,
        "parallelQueries": false
      },
      "aggressive": {
        "cacheEnabled": true,
        "cacheTTL": 600,
        "indexHints": true,
        "parallelQueries": true,
        "prefetch": true
      },
      "conservative": {
        "cacheEnabled": true,
        "cacheTTL": 60,
        "indexHints": false,
        "parallelQueries": false,
        "strictConsistency": true
      },
      "adaptive": {
        "cacheEnabled": true,
        "cacheTTL": 300,
        "indexHints": true,
        "parallelQueries": true,
        "autoTune": true,
        "monitorPerformance": true
      }
    }
  }
}
```

---

### Multivariate Flag Evaluation in Code

While the framework doesn't include a built-in evaluator in the current implementation, here's how you would typically evaluate multivariate flags:

```java
// Pseudocode for evaluation logic
public String evaluateVariant(FeatureFlag flag, UserContext user) {
    if (!flag.isEnabled()) {
        return null; // Feature is off
    }
    
    // Evaluate target rules in order
    for (TargetRule rule : flag.getTargetRules()) {
        if (matchesRule(rule, user)) {
            return rule.getVariant(); // First match wins
        }
    }
    
    // Fall back to default variant
    JsonNode config = flag.getJsonConfiguration();
    if (config != null && config.has("defaultVariant")) {
        return config.get("defaultVariant").asText();
    }
    
    return null; // No variant assigned
}

private boolean matchesRule(TargetRule rule, UserContext user) {
    String attrValue = user.getAttribute(rule.getAttribute());
    
    switch (rule.getOperator()) {
        case "equals":
            return rule.getValues().contains(attrValue);
        case "in":
            return rule.getValues().contains(attrValue);
        case "hashMod":
            int modulo = Integer.parseInt(rule.getValues().get(0));
            int hash = Math.abs(attrValue.hashCode() % 100);
            return hash < modulo;
        // ... other operators
    }
    return false;
}
```

---

### Using Variants in Permission Rules

```yaml
# Allow different actions based on variant
- name: allow-ai-features-by-variant
  description: Grant AI features based on assigned variant
  securityURI:
    header:
      identity: USER
      area: AI
      functionalDomain: Assistant
      action: use
    body:
      realm: '*'
      accountNumber: '*'
      tenantId: '*'
      dataSegment: '*'
      ownerId: '*'
      resourceId: '*'
  postconditionScript: |
    var variant = userProfile?.features?.AI_ASSISTANT;
    variant === 'basic' || variant === 'advanced' || variant === 'full'
  effect: ALLOW
  priority: 300

# Restrict advanced features to specific variants
- name: allow-ai-code-generation
  description: Code generation only for advanced/full variants
  securityURI:
    header:
      identity: USER
      area: AI
      functionalDomain: Assistant
      action: generateCode
    body:
      realm: '*'
      accountNumber: '*'
      tenantId: '*'
      dataSegment: '*'
      ownerId: '*'
      resourceId: '*'
  postconditionScript: |
    var variant = userProfile?.features?.AI_ASSISTANT;
    variant === 'advanced' || variant === 'full'
  effect: ALLOW
  priority: 300
```

---

### Multivariate Flag Best Practices

#### DO:
- ✅ **Define clear variants** with meaningful names
- ✅ **Always specify defaultVariant** in jsonConfiguration
- ✅ **Order targetRules by specificity** (most specific first)
- ✅ **Use jsonConfiguration** to store variant-specific settings
- ✅ **Document variant behavior** in description fields
- ✅ **Monitor variant distribution** to ensure even splits
- ✅ **Use hashMod for consistent assignment** across sessions

#### DON'T:
- ❌ **Don't overlap hashMod ranges** (e.g., two rules with hashMod 50)
- ❌ **Don't change variant names** mid-experiment (breaks tracking)
- ❌ **Don't forget the control group** (always have a baseline)
- ❌ **Don't make variants too different** (hard to attribute results)
- ❌ **Don't run too many experiments** simultaneously
- ❌ **Don't ignore statistical significance** when analyzing results

---

### Variant Analytics and Tracking

Store experiment metadata in jsonConfiguration:

```json
{
  "jsonConfiguration": {
    "defaultVariant": "control",
    "experimentId": "EXP-2024-Q1-SEARCH",
    "startDate": "2024-01-15",
    "endDate": "2024-02-15",
    "metrics": [
      "clickThroughRate",
      "conversionRate",
      "timeOnPage"
    ],
    "successCriteria": {
      "primaryMetric": "conversionRate",
      "minimumImprovement": 0.05,
      "confidenceLevel": 0.95
    },
    "variants": {
      "control": { "description": "Current implementation" },
      "v2": { "description": "ML-powered ranking" }
    }
  }
}
```

---

## Target Rule Operators

### Supported Operators:

| Operator | Description | Example |
|----------|-------------|---------|
| `equals` | Exact match | `{"attribute": "role", "operator": "equals", "values": ["ADMIN"]}` |
| `in` | Match any value in list | `{"attribute": "tenantId", "operator": "in", "values": ["T1", "T2"]}` |
| `contains` | Substring match | `{"attribute": "email", "operator": "contains", "values": ["@acme.com"]}` |
| `startsWith` | Prefix match | `{"attribute": "location", "operator": "startsWith", "values": ["US"]}` |
| `regex` | Regular expression | `{"attribute": "userId", "operator": "regex", "values": ["^user-[0-9]+"]}` |
| `hashMod` | Percentage rollout | `{"attribute": "userId", "operator": "hashMod", "values": ["10"]}` |

### HashMod Operator (Percentage Rollouts):

The `hashMod` operator enables consistent percentage-based rollouts:

```json
{
  "attribute": "userId",
  "operator": "hashMod",
  "values": ["10"]
}
```

- Hashes the userId
- Takes modulo 100
- Matches if result < 10 (i.e., 10% of users)
- **Consistent**: Same user always gets same result
- **Distributed**: Evenly spreads across user base

---

## Business Usage Patterns

### 1. Progressive Rollout by Tenant
**TargetRule**: `tenantId in [T100, T200]`  
**Permission**: Add ALLOW for endpoints guarded by that flag so only those tenants can call them during rollout.

### 2. Role-Based Beta Access
**TargetRule**: `role equals BETA`  
**Permission**: Require both the BETA feature flag and standard role checks (e.g., USER/ADMIN) to ALLOW sensitive actions.

### 3. Plan/Entitlement Tiers
**TargetRule**: `plan in [Pro, Enterprise]`  
**Permission**: Enforce additional data-domain constraints (e.g., export size limits) while the flag simply turns the feature on for eligible plans.

### 4. Canary Deployments
**TargetRule**: `userId hashMod 5` (5% of users)  
**Permission**: Standard authorization rules apply; flag controls exposure only.

### 5. Environment-Specific Features
**TargetRule**: `environment equals dev`  
**Permission**: Same rules across environments; flag controls availability per environment.

---

## Key Design Principles

1. **Separation of Concerns**: Feature flags handle exposure/variants; permissions handle authorization
2. **Fail-Safe**: Disabled flag = feature unavailable, regardless of permissions
3. **Reversible**: Flags can be toggled without code deployment
4. **Auditable**: All flag changes tracked via BaseModel audit fields (createdBy, createdDate, modifiedBy, modifiedDate)
5. **Multi-Tenant**: Flags respect realm boundaries and DataDomain scoping
6. **Environment-Aware**: Different configurations per environment (dev/staging/prod)
7. **Testable**: Can be queried and validated via REST API

---

## Best Practices

### DO:
- ✅ Use feature flags for temporary exposure control
- ✅ Combine with permission rules for authorization
- ✅ Start with small cohorts and expand gradually
- ✅ Use `hashMod` for consistent percentage rollouts
- ✅ Document flag purpose in `description` field
- ✅ Use `jsonConfiguration` for feature-specific settings
- ✅ Clean up flags after full rollout

### DON'T:
- ❌ Use feature flags as a replacement for permissions
- ❌ Leave flags enabled indefinitely after rollout
- ❌ Skip permission checks when flag is on
- ❌ Use flags for permanent access control
- ❌ Forget to test flag evaluation logic
- ❌ Ignore audit trails and change history

---

## Troubleshooting

### Flag Not Taking Effect:

1. **Check enabled status**: `enabled: true`?
2. **Verify targetRules**: Do they match the user's attributes?
3. **Check environment**: Does it match current environment?
4. **Review realm**: Is the flag in the correct realm?
5. **Inspect audit logs**: When was it last modified?

### Permission Denied Despite Flag Being On:

1. **Check permission rules**: Flag ON doesn't grant authorization
2. **Review postconditionScript**: Is it checking the right flag?
3. **Verify role assignment**: Does user have required roles?
4. **Check data domain**: Is user in the correct tenant/org?

### Inconsistent Behavior:

1. **HashMod consistency**: Same userId should always get same result
2. **Cache issues**: Feature flag evaluation may be cached
3. **Realm mismatch**: Ensure X-Realm header matches flag realm
4. **Rule priority**: Check if multiple rules conflict

---

## API Reference Summary

### Endpoints:

| Method | Path | Description |
|--------|------|-------------|
| GET | `/features/flags/list` | List all flags with filtering/sorting |
| GET | `/features/flags/id/{id}` | Get flag by ID |
| GET | `/features/flags/refName/{refName}` | Get flag by refName |
| POST | `/features/flags` | Create or update flag |
| DELETE | `/features/flags/id/{id}` | Delete flag by ID |
| DELETE | `/features/flags/refName/{refName}` | Delete flag by refName |
| GET | `/features/flags/count` | Count flags matching filter |
| GET | `/features/flags/schema` | Get JSON schema for FeatureFlag |

### Headers:

- `Authorization: Bearer <JWT>` - Required
- `X-Realm: <tenant-id>` - Optional, defaults to user's realm
- `Content-Type: application/json` - For POST/PUT

### Query Parameters (list endpoint):

- `skip` - Pagination offset (default: 0)
- `limit` - Page size (default: 100)
- `filter` - ANTLR query filter
- `sort` - Sort fields (e.g., `+refName,-createdDate`)
- `projection` - Field projection (e.g., `+refName,+enabled,-description`)

---

## Summary

The Quantum feature flag implementation provides a production-ready system for:

- ✅ Safe, gradual feature rollouts
- ✅ A/B testing and experimentation
- ✅ Environment-specific configuration
- ✅ Cohort-based targeting
- ✅ Integration with fine-grained authorization
- ✅ Multi-tenant isolation
- ✅ Audit trails and change tracking

It follows industry best practices by keeping feature exposure separate from authorization while allowing them to work together through the permission rule system.

---

## Related Documentation

- **Permissions Guide**: See `quantum-docs/src/docs/asciidoc/user-guide/permissions.adoc`
- **REST CRUD**: See `quantum-docs/src/docs/asciidoc/user-guide/rest-crud.adoc`
- **Query Language**: See `quantum-docs/src/docs/asciidoc/user-guide/query-language.adoc`
- **Multi-Tenancy**: See `quantum-docs/src/docs/asciidoc/user-guide/tenant-models.adoc`

---

**Version**: 1.2.2-SNAPSHOT  
**Last Updated**: 2024  
**Module**: quantum-framework
