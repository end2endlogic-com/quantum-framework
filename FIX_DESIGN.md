# Quantum Framework - Critical & High Priority Fix Design

**Date:** 2024  
**Version:** 1.2.2-SNAPSHOT  
**Status:** Design Document

---

## Overview

This document provides detailed design specifications for fixing critical and high-priority security and code quality issues identified in the audit report. Each fix includes:

- Problem statement
- Design approach
- Implementation details
- Code examples
- Testing strategy
- Migration considerations

---

## 1. Critical Security Fixes

### 1.1 Script Execution Sandboxing Enhancement

**Status:** Partially implemented - needs hardening

**Current State:**
- Script execution has timeout and executor service
- Still allows permissive mode via `allowAllAccess=true`
- Default configuration is safer but permissive mode exists

**Problem:**
- Permissive mode (`allowAllAccess=true`) allows arbitrary code execution
- Even hardened mode allows `allowPublicAccess(true)` which may be too permissive
- No resource limits (memory, CPU) beyond timeout

**Design:**

#### 1.1.1 Remove Permissive Mode (Breaking Change)

**Approach:** Remove the permissive mode entirely or make it require explicit system property.

**Implementation:**

```java
// In RuleContext.java and SecurityFilter.java

// REMOVE or DEPRECATE this block:
if (allowAll) {
    // ... permissive mode code
}

// REPLACE with:
if (allowAll) {
    // Only allow in development/test environments
    String env = System.getProperty("quantum.security.scripting.allowPermissiveEnv", "false");
    if (!"true".equals(env) && !"dev".equals(env) && !"test".equals(env)) {
        Log.error("Permissive script mode requested but not allowed in this environment");
        throw new SecurityException("Permissive script execution not allowed");
    }
    Log.warn("Permissive script mode enabled - UNSAFE - only for development");
    // ... existing permissive code with additional warnings
}
```

**Configuration:**
- Add system property check: `quantum.security.scripting.allowPermissiveEnv`
- Default: `false` (disabled)
- Only allow in dev/test profiles

#### 1.1.2 Enhance Hardened Mode

**Approach:** Tighten security constraints in hardened mode.

**Implementation:**

```java
// Enhanced Context configuration
try (Context c = Context.newBuilder("js")
        .engine(eng)
        .allowAllAccess(false)
        // REMOVE: .allowHostAccess(HostAccess.newBuilder().allowPublicAccess(true).build())
        // REPLACE with:
        .allowHostAccess(HostAccess.newBuilder()
                .allowPublicAccess(false)  // Disable public access
                .allowArrayAccess(true)     // Only allow array access for bindings
                .allowListAccess(true)     // Only allow list access for bindings
                .build())
        .allowHostClassLookup(s -> false)  // Already correct
        .allowIO(false)                     // Already correct
        .allowNativeAccess(false)           // ADD: Disable native access
        .allowCreateThread(false)           // ADD: Disable thread creation
        .allowCreateProcess(false)          // ADD: Disable process creation
        .option("js.ecmascript-version", "2021")
        .option("engine.WarnInterpreterOnly", "false")
        .build()) {
    // ... rest of code
}
```

#### 1.1.3 Add Resource Limits

**Approach:** Add memory and CPU limits using GraalVM options.

**Implementation:**

```java
// Add to Context builder
.option("engine.MaxStatements", String.valueOf(maxStatements))  // Limit statement count
.option("engine.CompilationThreshold", "100")                   // Force compilation threshold

// Add memory limit check
long maxMemoryBytes = cfg.getOptionalValue("quantum.security.scripting.maxMemoryBytes", Long.class)
    .orElse(10_000_000L); // 10MB default

// Monitor memory usage (approximate)
Runtime runtime = Runtime.getRuntime();
long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
// ... execute script
long afterMemory = runtime.totalMemory() - runtime.freeMemory();
if (afterMemory - beforeMemory > maxMemoryBytes) {
    Log.warnf("Script exceeded memory limit: %d bytes", afterMemory - beforeMemory);
    return false;
}
```

**Configuration Properties:**
```properties
# Script execution limits
quantum.security.scripting.enabled=true
quantum.security.scripting.timeout.millis=250
quantum.security.scripting.allowAllAccess=false
quantum.security.scripting.maxMemoryBytes=10000000
quantum.security.scripting.maxStatements=10000
quantum.security.scripting.allowPermissiveEnv=false  # System property only
```

**Files to Modify:**
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java` (lines 700-781)
- `quantum-framework/src/main/java/com/e2eq/framework/rest/filters/SecurityFilter.java` (lines 239-320)

**Testing:**
- Unit tests for script timeout
- Unit tests for memory limits
- Unit tests for security constraints
- Integration tests with malicious scripts

---

### 1.2 Identity Case Sensitivity Normalization

**Status:** Not implemented

**Problem:**
- Rules are keyed by identity case-sensitively in `rules` map
- URI matching uses `IOCase.INSENSITIVE`
- Mismatch causes rules to be missed

**Design:**

#### 1.2.1 Normalize at Load Time

**Approach:** Normalize all identities to lowercase when loading rules.

**Implementation:**

```java
// In RuleContext.reloadFromRepo()

public synchronized void reloadFromRepo(@NotNull String realm) {
    // ... existing code ...
    
    // Create new map with normalized keys
    Map<String, List<Rule>> newRules = new HashMap<>();
    
    for (Policy policy : policies) {
        // ... existing policy processing ...
        
        for (Rule rule : policy.getRules()) {
            String identity = extractIdentity(rule, policy);
            if (identity == null || identity.isBlank()) {
                continue;
            }
            
            // NORMALIZE: Convert to lowercase
            String normalizedIdentity = identity.toLowerCase(Locale.ROOT).trim();
            
            newRules.computeIfAbsent(normalizedIdentity, k -> new ArrayList<>())
                    .add(rule);
        }
    }
    
    // Sort each list by priority
    for (List<Rule> ruleList : newRules.values()) {
        ruleList.sort(Comparator.comparingInt(Rule::getPriority));
    }
    
    // ATOMIC SWAP: Replace old map with new one
    this.rules = Collections.unmodifiableMap(newRules);
    
    // Rebuild index if enabled
    if (compiledIndex != null) {
        rebuildIndex(newRules);
    }
}
```

#### 1.2.2 Normalize at Lookup Time

**Approach:** Normalize identities when looking up rules.

**Implementation:**

```java
// In RuleContext.getApplicableRulesForPrincipalAndAssociatedRoles()

private List<Rule> getApplicableRulesForPrincipalAndAssociatedRoles(
        PrincipalContext pcontext, ResourceContext rcontext) {
    
    List<Rule> candidates = new ArrayList<>();
    
    // Normalize userId
    String normalizedUserId = pcontext.getUserId() != null 
        ? pcontext.getUserId().toLowerCase(Locale.ROOT).trim() 
        : null;
    
    if (normalizedUserId != null) {
        List<Rule> userRules = rules.get(normalizedUserId);
        if (userRules != null) {
            candidates.addAll(userRules);
        }
    }
    
    // Normalize roles
    if (pcontext.getRoles() != null) {
        for (String role : pcontext.getRoles()) {
            String normalizedRole = role.toLowerCase(Locale.ROOT).trim();
            List<Rule> roleRules = rules.get(normalizedRole);
            if (roleRules != null) {
                candidates.addAll(roleRules);
            }
        }
    }
    
    // ... rest of method
}
```

#### 1.2.3 Update RuleIndex

**Approach:** Ensure RuleIndex also normalizes identities.

**Implementation:**

```java
// In RuleIndex.build() or similar method

private void addRule(String identity, Rule rule) {
    // Normalize identity
    String normalized = identity != null 
        ? identity.toLowerCase(Locale.ROOT).trim() 
        : "*";
    
    // ... rest of index building
}
```

**Files to Modify:**
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`
  - `reloadFromRepo()` method (line 570)
  - `getApplicableRulesForPrincipalAndAssociatedRoles()` method
  - `rulesForIdentity()` helper methods
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleIndex.java`
  - Identity handling methods

**Migration:**
- Add migration script to normalize existing Policy.principalId values in database
- Document identity normalization in security model documentation

**Testing:**
- Unit tests with mixed case identities
- Integration tests with case-sensitive vs case-insensitive matching
- Test with existing data to ensure backward compatibility

---

### 1.3 Concurrency and Rules Map Visibility

**Status:** Partially implemented - needs improvement

**Problem:**
- `reloadFromRepo()` is synchronized but clears and mutates shared map
- Readers are unsynchronized and may see partial state
- Risk of `ConcurrentModificationException` or inconsistent rule sets

**Design:**

#### 1.3.1 Use Immutable Snapshots

**Approach:** Build new map off-thread, then atomically swap.

**Implementation:**

```java
// Change rules field to volatile
private volatile Map<String, List<Rule>> rules = Collections.emptyMap();

// Update reloadFromRepo()
public synchronized void reloadFromRepo(@NotNull String realm) {
    try {
        // Build new map (off-thread, but synchronized for consistency)
        Map<String, List<Rule>> newRules = new HashMap<>();
        
        // Add system rules
        addSystemRules(newRules);
        
        // Load policies and build rules
        List<Policy> policies = policyRepo.list(realm);
        for (Policy policy : policies) {
            // ... process policies ...
            for (Rule rule : policy.getRules()) {
                String identity = extractIdentity(rule, policy);
                if (identity == null || identity.isBlank()) {
                    continue;
                }
                String normalizedIdentity = identity.toLowerCase(Locale.ROOT).trim();
                newRules.computeIfAbsent(normalizedIdentity, k -> new ArrayList<>())
                        .add(rule);
            }
        }
        
        // Sort each list by priority
        for (List<Rule> ruleList : newRules.values()) {
            ruleList.sort(Comparator.comparingInt(Rule::getPriority));
            // Make list immutable to prevent modification
            ruleList = Collections.unmodifiableList(ruleList);
        }
        
        // Make map immutable
        Map<String, List<Rule>> immutableRules = Collections.unmodifiableMap(newRules);
        
        // ATOMIC SWAP: Single volatile write
        this.rules = immutableRules;
        
        // Rebuild index (also atomic)
        if (useCompiledIndex) {
            RuleIndex newIndex = new RuleIndex();
            newIndex.build(immutableRules);
            this.compiledIndex = newIndex; // Also volatile
        }
        
        Log.infof("Reloaded %d rules for realm %s", 
            immutableRules.values().stream().mapToInt(List::size).sum(), realm);
            
    } catch (Exception e) {
        Log.errorf(e, "Failed to reload rules from repo for realm %s", realm);
        throw new RuntimeException("Rule reload failed", e);
    }
}

// Update getter to be thread-safe (already is with volatile)
private List<Rule> rulesForIdentity(String identity) {
    String normalized = identity != null 
        ? identity.toLowerCase(Locale.ROOT).trim() 
        : null;
    if (normalized == null) {
        return Collections.emptyList();
    }
    // Volatile read - thread-safe
    Map<String, List<Rule>> currentRules = this.rules;
    return currentRules.getOrDefault(normalized, Collections.emptyList());
}
```

#### 1.3.2 Add Version Tracking

**Approach:** Track rule version for debugging and cache invalidation.

**Implementation:**

```java
// Add version field
private volatile long rulesVersion = 0L;
private volatile long lastReloadTimestamp = 0L;

// In reloadFromRepo()
this.rulesVersion++;
this.lastReloadTimestamp = System.currentTimeMillis();

// Add getter for monitoring
public long getRulesVersion() {
    return rulesVersion;
}

public long getLastReloadTimestamp() {
    return lastReloadTimestamp;
}
```

**Files to Modify:**
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`
  - Field declarations
  - `reloadFromRepo()` method
  - All methods that read `rules` map

**Testing:**
- Concurrent reload and read tests
- Stress tests with multiple threads
- Verify no `ConcurrentModificationException`
- Verify consistent rule sets during reload

---

### 1.4 OwnerId Population for Role Identities

**Status:** Bug exists

**Problem:**
- `createURLForIdentity()` sets `ownerId = identity` for all identities
- For role identities, this means `ownerId` becomes the role string
- Scripts expecting `ownerId` to be userId will fail

**Design:**

#### 1.4.1 Separate OwnerId from Identity

**Approach:** Always set `ownerId` to principal userId, pass role separately.

**Implementation:**

```java
// In RuleContext.createURLForIdentity()

SecurityURI createURLForIdentity(
        @NotNull String identity, 
        @NotNull @Valid PrincipalContext pcontext, 
        @NotNull @Valid ResourceContext rcontext) {

    SecurityURIHeader.Builder huri = new SecurityURIHeader.Builder()
            .withIdentity(identity)
            .withArea(rcontext.getArea())
            .withFunctionalDomain(rcontext.getFunctionalDomain())
            .withAction(rcontext.getAction());

    SecurityURIBody.Builder buri = new SecurityURIBody.Builder()
            .withRealm(pcontext.getDefaultRealm())
            .withOrgRefName(pcontext.getDataDomain().getOrgRefName())
            .withAccountNumber(pcontext.getDataDomain().getAccountNum())
            .withTenantId(pcontext.getDataDomain().getTenantId())
            // FIX: Always use userId for ownerId, not the identity (which may be a role)
            .withOwnerId(pcontext.getUserId() != null ? pcontext.getUserId() : "*")
            .withDataSegment(Integer.toString(pcontext.getDataDomain().getDataSegment()));

    buri.withResourceId(rcontext.getResourceId());
    
    SecurityURIHeader header = huri.build();
    SecurityURIBody body = buri.build();

    return new SecurityURI(header, body);
}
```

#### 1.4.2 Update Script Bindings

**Approach:** Expose role information separately in script bindings.

**Implementation:**

```java
// In RuleContext.installHelpersAndBindings()

private void installHelpersAndBindings(Context c, PrincipalContext pcontext, ResourceContext rcontext) {
    var jsBindings = c.getBindings("js");
    
    // ... existing code ...
    
    // ADD: Expose role information separately
    Map<String, Object> identityInfo = new HashMap<>();
    identityInfo.put("userId", pcontext.getUserId());
    identityInfo.put("roles", new ArrayList<>(pcontext.getRoles() != null ? pcontext.getRoles() : Collections.emptySet()));
    identityInfo.put("currentIdentity", pcontext.getUserId()); // For scripts that need current identity
    
    jsBindings.putMember("identityInfo", identityInfo);
    
    // ... rest of method
}
```

**Files to Modify:**
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`
  - `createURLForIdentity()` method (line 1724)
  - `installHelpersAndBindings()` method (line 786)

**Migration:**
- Document change in security model
- Update any scripts that rely on `ownerId` being the role
- Provide migration guide for script authors

**Testing:**
- Unit tests with role-based identities
- Verify ownerId is always userId
- Test script bindings with role information

---

### 1.5 DENY Rules Adding Filters

**Status:** Bug exists

**Problem:**
- `getFilters()` adds filters from all rules regardless of effect
- DENY rules should not add filters (they should subtract/restrict)

**Design:**

#### 1.5.1 Only Add Filters from ALLOW Rules

**Approach:** Check rule effect before adding filters.

**Implementation:**

```java
// In RuleContext.getFilters()

public List<Filter> getFilters(
        List<Filter> ifilters, 
        @Valid @NotNull PrincipalContext pcontext, 
        @Valid @NotNull ResourceContext rcontext, 
        Class<? extends UnversionedBaseModel> modelClass) {
    
    List<Filter> filters = new ArrayList<>();
    filters.addAll(ifilters);

    SecurityCheckResponse response = this.checkRules(pcontext, rcontext);

    List<Filter> andFilters = new ArrayList<>();
    List<Filter> orFilters = new ArrayList<>();

    MorphiaUtils.VariableBundle vars = resolveVariableBundle(pcontext, rcontext, modelClass);

    for (RuleResult result : response.getMatchedRuleResults()) {
        // Ignore Not Applicable rules
        if (result.getDeterminedEffect() == RuleDeterminedEffect.NOT_APPLICABLE) {
            continue;
        }

        Rule rule = result.getRule();
        
        // FIX: Only process ALLOW rules for filters
        if (rule.getEffect() != RuleEffect.ALLOW) {
            // DENY rules don't add filters - they restrict access at rule level
            // If we need DENY filters in future, implement separate subtractive mechanism
            continue;
        }

        // Reset filters for each rule to prevent cross-rule accumulation
        andFilters.clear();
        orFilters.clear();
        
        if (rule.getAndFilterString() != null && !rule.getAndFilterString().isEmpty()) {
            andFilters.add(MorphiaUtils.convertToFilter(rule.getAndFilterString(), vars, modelClass));
        }

        if (rule.getOrFilterString() != null && !rule.getOrFilterString().isEmpty()) {
            orFilters.add(MorphiaUtils.convertToFilter(rule.getOrFilterString(), vars, modelClass));
        }

        // Combine filters for this rule
        if (!andFilters.isEmpty() && !orFilters.isEmpty()) {
            FilterJoinOp joinOp = rule.getJoinOp() != null ? rule.getJoinOp() : FilterJoinOp.AND;
            if (joinOp == FilterJoinOp.AND) {
                andFilters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
                filters.add(Filters.and(andFilters.toArray(new Filter[andFilters.size()])));
            } else {
                orFilters.add(Filters.and(andFilters.toArray(new Filter[andFilters.size()])));
                filters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
            }
        } else {
            if (!andFilters.isEmpty()) {
                filters.addAll(andFilters);
            }
            if (!orFilters.isEmpty()) {
                filters.add(Filters.or(orFilters.toArray(new Filter[orFilters.size()])));
            }
        }
        
        if (rule.isFinalRule()) {
            break;
        }
    }

    // Deduplicate filters
    List<Filter> rc = new ArrayList<>();
    HashMap<String, Filter> filterMap = new HashMap<>();
    filters.forEach(filter -> {
        filterMap.put(filter.toString(), filter);
    });
    rc.addAll(filterMap.values());

    return rc;
}
```

**Files to Modify:**
- `quantum-morphia-repos/src/main/java/com/e2eq/framework/securityrules/RuleContext.java`
  - `getFilters()` method (line 1785)

**Testing:**
- Unit tests with DENY rules
- Verify DENY rules don't add filters
- Test filter accumulation with multiple ALLOW rules

---

### 1.6 ResourceContext Path Parsing Enhancement

**Status:** Partial implementation

**Problem:**
- Only handles exactly 3 path segments correctly
- Paths with more segments are logged but not properly handled
- May cause security bypass

**Design:**

#### 1.6.1 Enhanced Path Parsing

**Approach:** Handle variable path lengths with configurable patterns.

**Implementation:**

```java
// In SecurityFilter.determineResourceContext()

private ResourceContext determineResourceContext(ContainerRequestContext requestContext) {
    // ... existing annotation-based resolution ...
    
    // Enhanced path-based resolution
    String path = requestContext.getUriInfo().getPath();
    if (path == null || path.isEmpty()) {
        return ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
    }
    
    // Remove leading/trailing slashes and split
    path = path.startsWith("/") ? path.substring(1) : path;
    path = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    
    if (path.isEmpty()) {
        return ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
    }
    
    String[] segments = path.split("/");
    int segmentCount = segments.length;
    
    // Pattern 1: /area/domain/action (3 segments) - PRIMARY
    if (segmentCount == 3) {
        String area = segments[0];
        String functionalDomain = segments[1];
        String action = segments[2];
        
        ResourceContext rcontext = new ResourceContext.Builder()
                .withAction(action)
                .withArea(area)
                .withFunctionalDomain(functionalDomain)
                .build();
        SecurityContext.setResourceContext(rcontext);
        
        if (Log.isDebugEnabled()) {
            Log.debugf("Resource Context set from path (3 segments): area=%s, domain=%s, action=%s", 
                area, functionalDomain, action);
        }
        return rcontext;
    }
    
    // Pattern 2: /area/domain/action/id (4 segments) - ACTION with resource ID
    if (segmentCount == 4) {
        String area = segments[0];
        String functionalDomain = segments[1];
        String action = segments[2];
        String resourceId = segments[3];
        
        ResourceContext rcontext = new ResourceContext.Builder()
                .withAction(action)
                .withArea(area)
                .withFunctionalDomain(functionalDomain)
                .withResourceId(resourceId)
                .build();
        SecurityContext.setResourceContext(rcontext);
        
        if (Log.isDebugEnabled()) {
            Log.debugf("Resource Context set from path (4 segments): area=%s, domain=%s, action=%s, id=%s", 
                area, functionalDomain, action, resourceId);
        }
        return rcontext;
    }
    
    // Pattern 3: /area/domain (2 segments) - Infer action from HTTP method
    if (segmentCount == 2) {
        String area = segments[0];
        String functionalDomain = segments[1];
        String action = inferActionFromHttpMethod(requestContext.getMethod());
        
        ResourceContext rcontext = new ResourceContext.Builder()
                .withAction(action)
                .withArea(area)
                .withFunctionalDomain(functionalDomain)
                .build();
        SecurityContext.setResourceContext(rcontext);
        
        if (Log.isDebugEnabled()) {
            Log.debugf("Resource Context set from path (2 segments): area=%s, domain=%s, action=%s (inferred)", 
                area, functionalDomain, action);
        }
        return rcontext;
    }
    
    // Pattern 4: /area (1 segment) - Minimal context
    if (segmentCount == 1) {
        String area = segments[0];
        String action = inferActionFromHttpMethod(requestContext.getMethod());
        
        ResourceContext rcontext = new ResourceContext.Builder()
                .withAction(action)
                .withArea(area)
                .withFunctionalDomain("*")
                .build();
        SecurityContext.setResourceContext(rcontext);
        
        if (Log.isDebugEnabled()) {
            Log.debugf("Resource Context set from path (1 segment): area=%s, action=%s (inferred)", 
                area, action);
        }
        return rcontext;
    }
    
    // Pattern 5: More than 4 segments - Use first 3, log warning
    if (segmentCount > 4) {
        Log.warnf("Path has %d segments, using first 3 for resource context: %s", segmentCount, path);
        String area = segments[0];
        String functionalDomain = segments[1];
        String action = segments[2];
        String resourceId = segmentCount > 3 ? segments[3] : null;
        
        ResourceContext rcontext = new ResourceContext.Builder()
                .withAction(action)
                .withArea(area)
                .withFunctionalDomain(functionalDomain)
                .withResourceId(resourceId)
                .build();
        SecurityContext.setResourceContext(rcontext);
        
        if (Log.isDebugEnabled()) {
            Log.debugf("Resource Context set from path (%d segments, truncated): area=%s, domain=%s, action=%s", 
                segmentCount, area, functionalDomain, action);
        }
        return rcontext;
    }
    
    // Fallback: Anonymous context
    Log.debugf("Non-conformant path: %s (segments: %d) - setting to anonymous context", path, segmentCount);
    ResourceContext rcontext = ResourceContext.DEFAULT_ANONYMOUS_CONTEXT;
    SecurityContext.setResourceContext(rcontext);
    return rcontext;
}
```

**Files to Modify:**
- `quantum-framework/src/main/java/com/e2eq/framework/rest/filters/SecurityFilter.java`
  - `determineResourceContext()` method (line ~150)

**Testing:**
- Unit tests for all path patterns (1-4+ segments)
- Integration tests with various REST endpoints
- Verify correct resource context extraction

---

## 2. High Priority Code Quality Fixes

### 2.1 Exception Handling Standardization

**Status:** Multiple issues

**Problem:**
- 18+ instances of `printStackTrace()`
- 39+ instances of swallowed exceptions
- Inconsistent error handling

**Design:**

#### 2.1.1 Replace printStackTrace() with Logging

**Approach:** Create utility method and replace all instances.

**Implementation:**

```java
// Create new utility class: ExceptionLoggingUtils.java

package com.e2eq.framework.util;

import io.quarkus.logging.Log;
import java.io.PrintWriter;
import java.io.StringWriter;

public class ExceptionLoggingUtils {
    
    /**
     * Log exception with full stack trace at ERROR level
     */
    public static void logError(Throwable exception, String message, Object... args) {
        if (exception == null) {
            Log.errorf(message, args);
            return;
        }
        
        String stackTrace = getStackTrace(exception);
        if (args.length > 0) {
            String formattedMessage = String.format(message, args);
            Log.errorf("%s: %s%n%s", formattedMessage, exception.getMessage(), stackTrace);
        } else {
            Log.errorf("%s: %s%n%s", message, exception.getMessage(), stackTrace);
        }
    }
    
    /**
     * Log exception with full stack trace at WARN level
     */
    public static void logWarn(Throwable exception, String message, Object... args) {
        if (exception == null) {
            Log.warnf(message, args);
            return;
        }
        
        String stackTrace = getStackTrace(exception);
        if (args.length > 0) {
            String formattedMessage = String.format(message, args);
            Log.warnf("%s: %s%n%s", formattedMessage, exception.getMessage(), stackTrace);
        } else {
            Log.warnf("%s: %s%n%s", message, exception.getMessage(), stackTrace);
        }
    }
    
    /**
     * Get stack trace as string
     */
    public static String getStackTrace(Throwable exception) {
        if (exception == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        return sw.toString();
    }
}
```

**Replace in Exception Mappers:**

```java
// RunTimeExceptionMapper.java
@Override
public Response toResponse(RuntimeException exception) {
    ExceptionLoggingUtils.logError(exception, "An unexpected / uncaught exception occurred");
    
    RestError error = RestError.builder()
            .statusMessage(exception.getMessage())
            .status(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode())
            .reasonMessage("An unexpected / uncaught exception occurred")
            .debugMessage(ExceptionLoggingUtils.getStackTrace(exception))
            .build();

    return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(error).build();
}
```

**Files to Modify:**
- Create: `quantum-util/src/main/java/com/e2eq/framework/util/ExceptionLoggingUtils.java`
- Update all exception mappers:
  - `RunTimeExceptionMapper.java`
  - `JsonExceptionMapper.java`
  - `WriteErrorExceptionMapper.java`
  - `QValidationExceptionMapper.java`
  - `NotFoundExceptionMapper.java`
  - `ConstraintViolationExceptionMapper.java`
- Update other files with `printStackTrace()`:
  - `PermissionResource.java`
  - `MigrationService.java`
  - `CommonUtils.java`
  - And others identified in audit

#### 2.1.2 Improve Swallowed Exceptions

**Approach:** Log swallowed exceptions at DEBUG level minimum.

**Implementation:**

```java
// Replace patterns like:
} catch (Exception ignored) { }

// With:
} catch (Exception e) {
    if (Log.isDebugEnabled()) {
        Log.debugf(e, "Exception ignored in %s: %s", 
            Thread.currentThread().getStackTrace()[1].getMethodName(), 
            e.getMessage());
    }
}

// Or create helper:
private void logIgnoredException(Exception e, String context) {
    if (Log.isDebugEnabled()) {
        Log.debugf(e, "Exception ignored in %s: %s", context, e.getMessage());
    }
}

// Usage:
} catch (Exception e) {
    logIgnoredException(e, "rule processing");
}
```

**Files to Modify:**
- All files with `catch (Exception ignored) {}` or `catch (Exception ignore) {}`
- Focus on:
  - `RuleContext.java`
  - `QueryToFilterListener.java`
  - `OntologyMaterializer.java`
  - `AnnotatedEdgeExtractor.java`
  - And others identified in audit

---

## 3. Implementation Plan

### Phase 1: Critical Security (Week 1-2)
1. Script execution sandboxing enhancement
2. Identity case sensitivity normalization
3. Concurrency fixes for rules map

### Phase 2: Security Bugs (Week 3)
4. OwnerId population fix
5. DENY rules filter fix
6. ResourceContext path parsing enhancement

### Phase 3: Code Quality (Week 4)
7. Exception handling standardization
8. Swallowed exception logging

### Phase 4: Testing & Documentation (Week 5)
9. Comprehensive testing
10. Documentation updates
11. Migration guides

---

## 4. Testing Strategy

### Unit Tests
- Script execution security tests
- Identity normalization tests
- Concurrency stress tests
- Filter accumulation tests
- Path parsing tests

### Integration Tests
- End-to-end security rule evaluation
- Multi-threaded rule reload
- Script execution with various constraints

### Performance Tests
- Rule evaluation performance
- Concurrent access performance
- Script execution overhead

---

## 5. Migration Considerations

### Breaking Changes
1. **Permissive script mode removal** - May break existing deployments using `allowAllAccess=true`
   - Migration: Set system property for dev/test environments
   - Timeline: Deprecate in 1.2.3, remove in 1.3.0

2. **Identity normalization** - May affect existing policies with mixed-case identities
   - Migration: Run normalization script on database
   - Timeline: Implement in 1.2.3 with migration tool

### Non-Breaking Changes
- Exception handling improvements (backward compatible)
- Path parsing enhancements (additive)
- Filter fixes (behavioral fix, not breaking)

---

## 6. Configuration Changes

### New Properties
```properties
# Script execution
quantum.security.scripting.maxMemoryBytes=10000000
quantum.security.scripting.maxStatements=10000
quantum.security.scripting.allowPermissiveEnv=false  # System property

# Rule engine
quantum.security.rules.version.tracking=true
quantum.security.rules.identity.normalize=true
```

### Deprecated Properties
```properties
# Deprecated - will be removed in 1.3.0
quantum.security.scripting.allowAllAccess=false  # Use allowPermissiveEnv system property instead
```

---

## 7. Success Criteria

- [ ] All critical security vulnerabilities fixed
- [ ] All high-priority code quality issues addressed
- [ ] Comprehensive test coverage (>80% for security code)
- [ ] Performance benchmarks maintained or improved
- [ ] Documentation updated
- [ ] Migration guides provided
- [ ] No breaking changes without deprecation period

---

**Next Steps:**
1. Review and approve design
2. Create implementation tickets
3. Begin Phase 1 implementation
4. Set up continuous integration for security tests


