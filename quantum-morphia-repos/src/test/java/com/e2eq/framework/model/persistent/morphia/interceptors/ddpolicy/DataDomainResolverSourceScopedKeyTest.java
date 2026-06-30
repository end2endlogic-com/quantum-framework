package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainComponentBinding;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Pure JUnit (no Mongo, no Quarkus boot) coverage for SLICE 2 of the DataDomainPolicy write-side:
 * per-(sourceId, entityType) policy key precedence.
 *
 * <p>S2 extends the precedence KEY LIST so a source-scoped policy entry can be matched per
 * (sourceId, entityType), searched BEFORE the existing {@code fa:fd} keys, WITHOUT changing
 * behavior when there are no SourceAttributes. The entry-resolution logic (FROM_SOURCE / FIXED /
 * FROM_CREDENTIAL) from S1 is unchanged; only WHICH keys are tried, and in what order, changes.</p>
 */
class DataDomainResolverSourceScopedKeyTest {

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    private DataDomain principalDD() {
        return DataDomain.builder()
                .orgRefName("PRINCIPAL-ORG")
                .accountNum("PRINCIPAL-ACCT")
                .tenantId("principal-tenant")
                .dataSegment(0)
                .ownerId("user@example.com")
                .build();
    }

    private void installPrincipal(DataDomain dd, DataDomainPolicy policy) {
        PrincipalContext pc = new PrincipalContext.Builder()
                .withDefaultRealm("system-com")
                .withDataDomain(dd)
                .withUserId("user@example.com")
                .withRoles(new String[]{"ADMIN"})
                .withScope("AUTHENTICATED")
                .build();
        pc.setDataDomainPolicy(policy);
        SecurityContext.setPrincipalContext(pc);
    }

    private DefaultDataDomainResolver newResolver() {
        DefaultDataDomainResolver resolver = new DefaultDataDomainResolver();
        resolver.globalPolicyProvider = new GlobalDataDomainPolicyProvider();
        return resolver;
    }

    private DataDomainPolicy policyWith(Map<String, DataDomainPolicyEntry> entries) {
        DataDomainPolicy policy = new DataDomainPolicy();
        policy.setPolicyEntries(entries);
        return policy;
    }

    /** A FIXED entry that returns a DataDomain whose orgRefName is the given marker. */
    private DataDomainPolicyEntry fixedEntry(String orgMarker) {
        DataDomain dd = DataDomain.builder()
                .orgRefName(orgMarker)
                .accountNum("ACCT-" + orgMarker)
                .tenantId("tenant-" + orgMarker)
                .dataSegment(0)
                .ownerId("system")
                .build();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(java.util.List.of(dd));
        return entry;
    }

    /** A FROM_SOURCE entry building tenant from an attribute, org/account literal from marker. */
    private DataDomainPolicyEntry fromSourceEntry(String orgMarker) {
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal(orgMarker));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("ACCT-" + orgMarker));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        return entry;
    }

    // ----- Regression: no SourceAttributes never consults a src/* entry -----

    @Test
    void noSourceAttributes_threeArgPath_neverConsultsSrcEntry_resolvesViaFaFd() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        // A src/* entry that, if ever consulted, would win and return a distinguishable org.
        entries.put("src/S/Order", fixedEntry("SRC-SCOPED-SHOULD-NEVER-WIN"));
        // The legacy fa:fd entry which is the only one allowed to match on the 3-arg path.
        entries.put("area:domain", fixedEntry("FA-FD-WINNER"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        // 3-arg path: attrs is null, so the key list is EXACTLY fa:fd -> ... -> *:* and the
        // src/S/Order entry is unreachable.
        DataDomain result = resolver.resolveForCreate("area", "domain");
        assertEquals("FA-FD-WINNER", result.getOrgRefName(),
                "3-arg path must resolve via fa:fd and NEVER consult a src/* entry");
    }

    @Test
    void nullAttrs_fourArgPath_neverConsultsSrcEntry_byteIdenticalToThreeArg() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/Order", fixedEntry("SRC-SCOPED-SHOULD-NEVER-WIN"));
        entries.put("area:domain", fixedEntry("FA-FD-WINNER"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        DataDomain legacy = resolver.resolveForCreate("area", "domain");
        // 4-arg with NULL attrs: the src/* keys must NOT be added; identical to the 3-arg path.
        DataDomain viaNullAttrs = resolver.resolveForCreate("area", "domain", null, null);
        assertEquals(legacy, viaNullAttrs,
                "4-arg path with null attrs is byte-identical to the 3-arg path (no src/* keys)");
        assertEquals("FA-FD-WINNER", viaNullAttrs.getOrgRefName());
    }

    // ----- Source-scoped match wins over *:* -----

    @Test
    void sourceScopedEntry_matchesAndWinsOverStarStar() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/Order", fromSourceEntry("SRC-S-ORDER"));
        entries.put("*:*", fixedEntry("STAR-STAR"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", "acme-tenant");
        SourceAttributes attrs = new SourceAttributes("S", "Order", values);

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertNotSame(DefaultDataDomainResolver.UNRESOLVABLE, result);
        assertEquals("SRC-S-ORDER", result.getOrgRefName(),
                "src/S/Order must be matched and win over a coexisting *:* entry");
        assertEquals("acme-tenant", result.getTenantId());
    }

    // ----- Precedence within the source namespace -----

    @Test
    void sourceNamespacePrecedence_mostSpecificWins() {
        DataDomain dd = principalDD();

        // All three present; src/S/Order is the most specific and must win.
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/Order", fixedEntry("MOST-SPECIFIC"));
        entries.put("src/S/*", fixedEntry("SOURCE-WILDCARD"));
        entries.put("src/*/Order", fixedEntry("ENTITY-WILDCARD"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("S", "Order", new HashMap<>());

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("MOST-SPECIFIC", result.getOrgRefName(),
                "src/S/Order beats src/S/* beats src/*/Order");

        // Remove the most specific: src/S/* must now win over src/*/Order.
        Map<String, DataDomainPolicyEntry> entries2 = new HashMap<>();
        entries2.put("src/S/*", fixedEntry("SOURCE-WILDCARD"));
        entries2.put("src/*/Order", fixedEntry("ENTITY-WILDCARD"));
        installPrincipal(dd, policyWith(entries2));
        DataDomain result2 = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("SOURCE-WILDCARD", result2.getOrgRefName(),
                "src/S/* beats src/*/Order");

        // Remove src/S/* too: src/*/Order is the only source key left and must win.
        Map<String, DataDomainPolicyEntry> entries3 = new HashMap<>();
        entries3.put("src/*/Order", fixedEntry("ENTITY-WILDCARD"));
        installPrincipal(dd, policyWith(entries3));
        DataDomain result3 = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("ENTITY-WILDCARD", result3.getOrgRefName(),
                "src/*/Order matches when no more-specific source key exists");
    }

    // ----- Source keys searched BEFORE fa:fd -----

    @Test
    void sourceScopedEntry_takesPrecedenceOverCoexistingFaFd() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/Order", fixedEntry("SOURCE-WINS"));
        entries.put("area:domain", fixedEntry("FA-FD"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("S", "Order", new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("SOURCE-WINS", result.getOrgRefName(),
                "source-scoped keys are searched before fa:fd");
    }

    // ----- Null / blank entityType: src/{sourceId}/* still matches, no NPE -----

    @Test
    void nullEntityType_sourceWildcardStillMatches_noNpe() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/*", fixedEntry("SOURCE-WILDCARD"));
        // A src/*/Order that must NOT be reachable since entityType is null.
        entries.put("src/*/Order", fixedEntry("ENTITY-WILDCARD-UNREACHABLE"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("S", null, new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("SOURCE-WILDCARD", result.getOrgRefName(),
                "src/S/* matches with a null entityType and no NPE");
    }

    @Test
    void blankEntityType_sourceWildcardStillMatches_noNpe() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/S/*", fixedEntry("SOURCE-WILDCARD"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("S", "   ", new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("SOURCE-WILDCARD", result.getOrgRefName(),
                "blank entityType still resolves via src/S/* with no NPE");
    }

    // ----- Blank sourceId: no src/* keys are added, falls back to fa:fd -----

    @Test
    void blankSourceId_noSrcKeysAdded_resolvesViaFaFd() {
        DataDomain dd = principalDD();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src//Order", fixedEntry("SHOULD-NEVER-WIN"));
        entries.put("area:domain", fixedEntry("FA-FD-WINNER"));
        installPrincipal(dd, policyWith(entries));
        DefaultDataDomainResolver resolver = newResolver();

        // Blank sourceId: source namespace must be skipped entirely.
        SourceAttributes attrs = new SourceAttributes("  ", "Order", new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals("FA-FD-WINNER", result.getOrgRefName(),
                "blank sourceId adds no src/* keys; falls through to fa:fd");
    }

    // ----- Defense-in-depth: tainted sourceId/entityType (key metachars) fall to legacy -----

    @Test
    void taintedSourceIdOrEntityType_withKeyMetachar_fallsThroughToLegacy_neverForgesWildcardMatch() {
        DataDomain dd = principalDD();
        DefaultDataDomainResolver resolver = newResolver();

        // Wildcard entries a forged value would try to select, plus the legitimate fa:fd fallback.
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put("src/*/Order", fixedEntry("CROSS-SOURCE-WILDCARD-MUST-NOT-WIN"));
        entries.put("src/S/*", fixedEntry("SOURCE-WILDCARD-MUST-NOT-WIN"));
        entries.put("area:domain", fixedEntry("FA-FD-WINNER"));
        installPrincipal(dd, policyWith(entries));

        // sourceId="*" would (without the guard) build src/*/Order and forge a match against the
        // cross-source wildcard entry. It must be rejected -> fall through to fa:fd.
        DataDomain r1 = resolver.resolveForCreate("area", "domain", null,
                new SourceAttributes("*", "Order", new HashMap<>()));
        assertEquals("FA-FD-WINNER", r1.getOrgRefName(),
                "sourceId='*' must be rejected, never match src/*/Order");

        // entityType="*" would (without the guard) build src/S/* and forge a match against the
        // source-wildcard entry. It must be rejected -> fall through to fa:fd.
        DataDomain r2 = resolver.resolveForCreate("area", "domain", null,
                new SourceAttributes("S", "*", new HashMap<>()));
        assertEquals("FA-FD-WINNER", r2.getOrgRefName(),
                "entityType='*' must be rejected, never match src/S/*");

        // Path-injection via '/' (and ':' into the legacy namespace) must also be rejected.
        DataDomain r3 = resolver.resolveForCreate("area", "domain", null,
                new SourceAttributes("x/y", "Order", new HashMap<>()));
        assertEquals("FA-FD-WINNER", r3.getOrgRefName(),
                "sourceId containing '/' must be rejected, falls through to fa:fd");
    }
}
