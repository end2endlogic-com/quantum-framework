package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainComponentBinding;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit (no Mongo, no Quarkus boot) coverage for the S3 tagged-result resolution
 * ({@link DataDomainResolution}) via {@link DataDomainResolver#resolveIngestRow}. This is the unit
 * half of the N3 acceptance: Resolved vs Unresolvable, decided by the TYPE TAG and NOT by any
 * {@code ==} on a look-alike DataDomain. It also asserts the entry takes the policy EXPLICITLY (no
 * SecurityContext/principal dependency — that is S4).
 */
class DataDomainResolutionTest {

    private DefaultDataDomainResolver newResolver() {
        DefaultDataDomainResolver resolver = new DefaultDataDomainResolver();
        resolver.globalPolicyProvider = new GlobalDataDomainPolicyProvider();
        return resolver;
    }

    private DataDomainPolicy policyWith(String key, DataDomainPolicyEntry entry) {
        DataDomainPolicy policy = new DataDomainPolicy();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put(key, entry);
        policy.setPolicyEntries(entries);
        return policy;
    }

    private DataDomainPolicyEntry fromSource(DataDomainComponentBinding binding) {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        return entry;
    }

    @Test
    void resolved_allRequiredComponentsPresent_carriesDataDomain_noSecurityContext() {
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicy policy = policyWith("ontology:edge", fromSource(binding));
        DefaultDataDomainResolver resolver = newResolver();

        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", "acme-tenant");
        SourceAttributes attrs = new SourceAttributes("src-1", "Order", values);

        // No SecurityContext.setPrincipalContext() at all — the policy is supplied explicitly.
        DataDomainResolution res = resolver.resolveIngestRow(policy, "ontology", "edge", attrs);

        assertTrue(res.isResolved(), "all required components present → Resolved");
        assertTrue(res instanceof DataDomainResolution.Resolved);
        DataDomain dd = res.dataDomain();
        assertNotNull(dd);
        assertEquals("SRC-ORG", dd.getOrgRefName());
        assertEquals("SRC-ACCT", dd.getAccountNum());
        assertEquals("acme-tenant", dd.getTenantId());
    }

    @Test
    void unresolvable_missingRequiredTenant_isTaggedNotAValue() {
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicy policy = policyWith("ontology:edge", fromSource(binding));
        DefaultDataDomainResolver resolver = newResolver();

        // tenant_id absent.
        SourceAttributes attrs = new SourceAttributes("src-1", "Order", new HashMap<>());
        DataDomainResolution res = resolver.resolveIngestRow(policy, "ontology", "edge", attrs);

        assertFalse(res.isResolved(), "missing required tenant → Unresolvable");
        assertTrue(res instanceof DataDomainResolution.Unresolvable);
        assertNotNull(res.reason());
        assertTrue(res.reason().contains("tenantId"), "reason names the missing component");
        // The decision is the TAG — there is no DataDomain to mis-compare. Asking for one throws.
        assertThrows(IllegalStateException.class, res::dataDomain,
                "Unresolvable carries NO DataDomain; the quarantine decision can never be defeated by a look-alike value");
    }

    @Test
    void unresolvable_nullPolicy() {
        DefaultDataDomainResolver resolver = newResolver();
        SourceAttributes attrs = new SourceAttributes("src-1", "Order", new HashMap<>());
        DataDomainResolution res = resolver.resolveIngestRow(null, "ontology", "edge", attrs);
        assertFalse(res.isResolved());
    }

    @Test
    void unresolvable_fromCredentialEntryOnIngestPath_hasNoCredential() {
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL);
        DataDomainPolicy policy = policyWith("ontology:edge", entry);
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("src-1", "Order", new HashMap<>());
        DataDomainResolution res = resolver.resolveIngestRow(policy, "ontology", "edge", attrs);
        assertFalse(res.isResolved(), "FROM_CREDENTIAL is unresolvable on the principal-less ingest path");
    }

    @Test
    void resolved_fixedEntry_returnsConfiguredDomain() {
        DataDomain fixed = DataDomain.builder()
                .orgRefName("FIXED-ORG").accountNum("FIXED-ACCT").tenantId("fixed-tenant")
                .dataSegment(2).ownerId("system").build();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(List.of(fixed));
        DataDomainPolicy policy = policyWith("ontology:edge", entry);
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("src-1", "Order", new HashMap<>());
        DataDomainResolution res = resolver.resolveIngestRow(policy, "ontology", "edge", attrs);
        assertTrue(res.isResolved());
        assertEquals("fixed-tenant", res.dataDomain().getTenantId());
    }

    @Test
    void sourceScopedKey_isPreferred() {
        // A src/{sourceId}/{entityType} FROM_SOURCE entry must be selected over a generic one.
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SCOPED-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SCOPED-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.literal("scoped-tenant"));

        DataDomainPolicy policy = policyWith("src/src-1/Order", fromSource(binding));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("src-1", "Order", new HashMap<>());
        DataDomainResolution res = resolver.resolveIngestRow(policy, "ontology", "edge", attrs);
        assertTrue(res.isResolved());
        assertEquals("scoped-tenant", res.dataDomain().getTenantId());
    }
}
