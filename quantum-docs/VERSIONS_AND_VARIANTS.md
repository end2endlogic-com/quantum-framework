# How "Versions" Fit Into Feature Flags

## Quick Answer

**"Version" is just a common name for variants** - they mean the same thing in feature flag terminology.

```
Feature Flag Terminology:
- MULTIVARIATE flag
- variants: ["v1", "v2", "v3"]
- variant value: "v2"

Common Developer Language:
- Feature with multiple versions
- versions: ["v1", "v2", "v3"]
- version assigned: "v2"

→ These describe the SAME concept!
```

---

## Example: Rolling Out a New API Version

```json
{
  "refName": "API_VERSION",
  "displayName": "API Version",
  "description": "Gradual rollout of API v2",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["v1", "v2", "v3-beta"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "role",
      "operator": "equals",
      "values": ["INTERNAL"],
      "variant": "v3-beta"
    },
    {
      "attribute": "tenantId",
      "operator": "in",
      "values": ["T100", "T200"],
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
    "defaultVariant": "v1",
    "variants": {
      "v1": {
        "endpoint": "/api/v1",
        "features": ["basic"],
        "deprecated": true
      },
      "v2": {
        "endpoint": "/api/v2",
        "features": ["basic", "advanced", "pagination"],
        "stable": true
      },
      "v3-beta": {
        "endpoint": "/api/v3",
        "features": ["basic", "advanced", "pagination", "graphql"],
        "experimental": true
      }
    }
  }
}
```

### Who Gets Which Version:

| User Type | Version Assigned | Reason |
|-----------|------------------|--------|
| Internal employees | `v3-beta` | Dogfooding new features |
| Pilot tenants (T100, T200) | `v2` | Early adopters |
| 10% of other users | `v2` | Gradual rollout |
| Everyone else | `v1` | Stable default |

### In Your Code:

```javascript
// Get the version (variant) assigned to this user
const apiVersion = userProfile.features.API_VERSION;

switch(apiVersion) {
  case "v1":
    apiEndpoint = "/api/v1";
    break;
  case "v2":
    apiEndpoint = "/api/v2";
    break;
  case "v3-beta":
    apiEndpoint = "/api/v3";
    break;
}
```

---

## Common Use Cases for "Versions" as Variants

### 1. API Versioning
```json
{
  "type": "MULTIVARIATE",
  "variants": ["v1", "v2", "v3"]
}
```
**Use**: Gradually migrate users from old API to new API

### 2. Algorithm Versions
```json
{
  "type": "MULTIVARIATE",
  "variants": ["legacy", "ml-v1", "ml-v2"]
}
```
**Use**: Test new ML ranking algorithm against old one

### 3. UI Component Versions
```json
{
  "type": "MULTIVARIATE",
  "variants": ["classic", "modern", "experimental"]
}
```
**Use**: Roll out redesigned components progressively

### 4. Feature Maturity Levels
```json
{
  "type": "MULTIVARIATE",
  "variants": ["alpha", "beta", "stable", "deprecated"]
}
```
**Use**: Control access based on feature maturity

---

## Version vs Variant: When to Use Each Term

### Use "Version" When:
- ✅ Talking about API versions (v1, v2, v3)
- ✅ Algorithm iterations (legacy, new, experimental)
- ✅ Sequential improvements (1.0, 2.0, 3.0)
- ✅ Maturity stages (alpha, beta, stable)

### Use "Variant" When:
- ✅ A/B testing different approaches (not sequential)
- ✅ Different configurations (aggressive, conservative)
- ✅ Different styles (blue, green, orange)
- ✅ Different experiences (simple, advanced, expert)

### Both Work For:
- ✅ Gradual rollouts
- ✅ Experimentation
- ✅ Cohort targeting

**Bottom Line**: "Version" and "variant" are interchangeable in feature flags. Use whichever makes more sense for your use case!

---

## Complete Example: Search Algorithm Versions

```json
{
  "refName": "SEARCH_ALGORITHM",
  "displayName": "Search Algorithm Version",
  "description": "Testing new search implementations",
  "enabled": true,
  "type": "MULTIVARIATE",
  "variants": ["v1-keyword", "v2-semantic", "v3-hybrid"],
  "environment": "prod",
  "targetRules": [
    {
      "attribute": "role",
      "operator": "equals",
      "values": ["BETA"],
      "variant": "v3-hybrid"
    },
    {
      "attribute": "userId",
      "operator": "hashMod",
      "values": ["25"],
      "variant": "v2-semantic"
    }
  ],
  "jsonConfiguration": {
    "defaultVariant": "v1-keyword",
    "variants": {
      "v1-keyword": {
        "algorithm": "keyword-match",
        "released": "2022-01-01",
        "performance": "fast"
      },
      "v2-semantic": {
        "algorithm": "semantic-search",
        "released": "2023-06-01",
        "performance": "medium",
        "accuracy": "high"
      },
      "v3-hybrid": {
        "algorithm": "hybrid-ml",
        "released": "2024-01-01",
        "performance": "medium",
        "accuracy": "very-high",
        "experimental": true
      }
    }
  }
}
```

### Result:
- Beta users → Get v3 (newest, experimental)
- 25% of users → Get v2 (semantic search)
- 75% of users → Get v1 (stable, keyword-based)

### In Code:
```java
String searchVersion = featureFlags.get("SEARCH_ALGORITHM");

switch(searchVersion) {
    case "v1-keyword":
        return keywordSearchService.search(query);
    case "v2-semantic":
        return semanticSearchService.search(query);
    case "v3-hybrid":
        return hybridSearchService.search(query);
    default:
        return keywordSearchService.search(query);
}
```

---

## Summary

- **Versions** = Named iterations of a feature (v1, v2, v3)
- **Variants** = Named options in a multivariate flag
- **They're the same thing** in feature flag context
- Use "version" when talking about sequential improvements
- Use "variant" when talking about different approaches
- Both are implemented the same way in the framework
