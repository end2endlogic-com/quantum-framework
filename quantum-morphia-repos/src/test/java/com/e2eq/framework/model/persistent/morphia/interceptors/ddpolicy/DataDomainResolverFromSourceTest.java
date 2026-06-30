package com.e2eq.framework.model.persistent.morphia.interceptors.ddpolicy;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DataDomainComponentBinding;
import com.e2eq.framework.model.security.DataDomainPolicy;
import com.e2eq.framework.model.security.DataDomainPolicyEntry;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pure JUnit (no Mongo, no Quarkus boot) coverage for SLICE 1 of the DataDomainPolicy
 * write-side: the FROM_SOURCE resolution mode plus the UNRESOLVABLE sentinel.
 *
 * Golden parity: FROM_CREDENTIAL and FIXED entries resolve byte-identically through the new
 * path as they do today, so existing persist behavior cannot regress.
 */
class DataDomainResolverFromSourceTest {

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

    private DataDomainPolicy policyWith(String key, DataDomainPolicyEntry entry) {
        DataDomainPolicy policy = new DataDomainPolicy();
        Map<String, DataDomainPolicyEntry> entries = new HashMap<>();
        entries.put(key, entry);
        policy.setPolicyEntries(entries);
        return policy;
    }

    // ----- Golden: FROM_CREDENTIAL parity -----

    @Test
    void fromCredential_threeArgAndNoPolicy_returnsPrincipalDD() {
        DataDomain dd = principalDD();
        installPrincipal(dd, null);
        DefaultDataDomainResolver resolver = newResolver();

        // SecurityContext.getPrincipalDataDomain() rebuilds a fresh DataDomain each call, so
        // golden parity is asserted by VALUE (DataDomain has @EqualsAndHashCode), not identity.
        DataDomain result = resolver.resolveForCreate("area", "domain");
        assertEquals(dd, result, "with no policy the principal DD must be returned unchanged (by value)");
    }

    @Test
    void fromCredential_explicitEntry_returnsPrincipalDD() {
        DataDomain dd = principalDD();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        // Both the legacy 3-arg path and the new 4-arg path must agree (golden: byte-identical
        // by value to the current resolver output).
        DataDomain legacy = resolver.resolveForCreate("area", "domain");
        DataDomain viaSource = resolver.resolveForCreate("area", "domain", null,
                new SourceAttributes("src-1", "Customer", new HashMap<>()));

        assertEquals(dd, legacy);
        assertEquals(legacy, viaSource);
    }

    // ----- Golden: FIXED parity -----

    @Test
    void fixed_returnsConfiguredDataDomain_identicalAcrossPaths() {
        DataDomain dd = principalDD();
        DataDomain fixed = DataDomain.builder()
                .orgRefName("FIXED-ORG")
                .accountNum("FIXED-ACCT")
                .tenantId("fixed-tenant")
                .dataSegment(2)
                .ownerId("system")
                .build();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FIXED);
        entry.setDataDomains(java.util.List.of(fixed));
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        DataDomain legacy = resolver.resolveForCreate("area", "domain");
        DataDomain viaSource = resolver.resolveForCreate("area", "domain", null,
                new SourceAttributes("src-1", "Customer", new HashMap<>()));

        assertSame(fixed, legacy, "FIXED returns the configured DataDomain instance");
        assertSame(fixed, viaSource, "FIXED behaves identically on the 4-arg path");
    }

    // ----- FROM_SOURCE: happy path -----

    @Test
    void fromSource_tenantFromAttribute_literalOrgAccount_buildsDataDomain() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));
        // dataSegment + ownerId left to defaults.

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", "acme-tenant");
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", values);

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);

        assertNotNull(result);
        assertNotSame(DefaultDataDomainResolver.UNRESOLVABLE, result);
        assertEquals("SRC-ORG", result.getOrgRefName());
        assertEquals("SRC-ACCT", result.getAccountNum());
        assertEquals("acme-tenant", result.getTenantId());
        assertEquals(0, result.getDataSegment());
        assertEquals("system", result.getOwnerId(), "ownerId defaults to 'system' when unbound");
    }

    @Test
    void fromSource_dataSegmentFromAttribute_coercedToInt() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.literal("t-1"));
        binding.setDataSegment(DataDomainComponentBinding.Binding.fromAttribute("seg"));
        binding.setOwnerId(DataDomainComponentBinding.Binding.literal("owner-1"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        Map<String, Object> values = new HashMap<>();
        values.put("seg", 7);
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", values);

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertEquals(7, result.getDataSegment());
        assertEquals("owner-1", result.getOwnerId());
    }

    // ----- FROM_SOURCE: UNRESOLVABLE sentinel -----

    @Test
    void fromSource_missingRequiredTenant_returnsUnresolvableSentinelNeverPrincipal() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        // tenant_id absent.
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);

        assertSame(DefaultDataDomainResolver.UNRESOLVABLE, result,
                "missing required component must return the UNRESOLVABLE sentinel");
        assertNotSame(dd, result, "UNRESOLVABLE must never be the principal's DD");
    }

    @Test
    void fromSource_blankRequiredTenant_returnsUnresolvable() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", "   ");
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", values);
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);

        assertSame(DefaultDataDomainResolver.UNRESOLVABLE, result);
        assertNotSame(dd, result);
    }

    @Test
    void fromSource_tenantKeyPresentButNullValue_returnsUnresolvable_neverNullString() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        // Key PRESENT with an explicit null value (not absent). Must coerce to null -> blank ->
        // UNRESOLVABLE, NEVER the literal string "null" (the String.valueOf(null) trap).
        Map<String, Object> values = new HashMap<>();
        values.put("tenant_id", null);
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", values);

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertSame(DefaultDataDomainResolver.UNRESOLVABLE, result,
                "key-present-but-null-value must be UNRESOLVABLE, not a tenantId of \"null\"");
        assertNotSame(dd, result);
    }

    @Test
    void fromSource_nullComponentBinding_returnsUnresolvable_noNpe() {
        DataDomain dd = principalDD();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        // componentBinding intentionally left null (misconfigured FROM_SOURCE policy).
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", new HashMap<>());
        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertSame(DefaultDataDomainResolver.UNRESOLVABLE, result,
                "FROM_SOURCE with no componentBinding must fail CLOSED to UNRESOLVABLE, not NPE");
    }

    @Test
    void fromSource_dataSegmentNonNumericAttribute_defaultsToZero_noException() {
        DataDomain dd = principalDD();
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setOrgRefName(DataDomainComponentBinding.Binding.literal("SRC-ORG"));
        binding.setAccountNum(DataDomainComponentBinding.Binding.literal("SRC-ACCT"));
        binding.setTenantId(DataDomainComponentBinding.Binding.literal("t-1"));
        binding.setDataSegment(DataDomainComponentBinding.Binding.fromAttribute("seg"));

        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        entry.setComponentBinding(binding);
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();

        // Non-numeric dataSegment must NOT escape an exception; it falls back to the default 0.
        Map<String, Object> values = new HashMap<>();
        values.put("seg", "not-a-number");
        SourceAttributes attrs = new SourceAttributes("src-1", "Customer", values);

        DataDomain result = resolver.resolveForCreate("area", "domain", null, attrs);
        assertNotSame(DefaultDataDomainResolver.UNRESOLVABLE, result);
        assertEquals(0, result.getDataSegment(), "non-numeric dataSegment attribute defaults to 0");
    }

    // ----- Back-compat: stored JSON without componentBinding -----

    @Test
    void legacyJsonWithoutComponentBinding_deserializes_andBehavesAsToday() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Pre-existing stored shape: no componentBinding field at all.
        String json = "{\"resolutionMode\":\"FROM_CREDENTIAL\",\"functionalActionString\":\"CREATE\"}";

        DataDomainPolicyEntry entry = mapper.readValue(json, DataDomainPolicyEntry.class);
        assertEquals(DataDomainPolicyEntry.ResolutionMode.FROM_CREDENTIAL, entry.getResolutionMode());
        assertNull(entry.getComponentBinding(), "absent componentBinding deserializes to null");

        // And it resolves to today's behavior (principal DD).
        DataDomain dd = principalDD();
        installPrincipal(dd, policyWith("area:domain", entry));
        DefaultDataDomainResolver resolver = newResolver();
        assertEquals(dd, resolver.resolveForCreate("area", "domain"));
    }

    @Test
    void newEnumValueSerializesRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DataDomainPolicyEntry entry = new DataDomainPolicyEntry();
        entry.setResolutionMode(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE);
        DataDomainComponentBinding binding = new DataDomainComponentBinding();
        binding.setTenantId(DataDomainComponentBinding.Binding.fromAttribute("tenant_id"));
        entry.setComponentBinding(binding);

        String json = mapper.writeValueAsString(entry);
        DataDomainPolicyEntry back = mapper.readValue(json, DataDomainPolicyEntry.class);
        assertEquals(DataDomainPolicyEntry.ResolutionMode.FROM_SOURCE, back.getResolutionMode());
        assertNotNull(back.getComponentBinding());
        assertEquals(DataDomainComponentBinding.Kind.FROM_ATTRIBUTE,
                back.getComponentBinding().getTenantId().getKind());
        assertEquals("tenant_id", back.getComponentBinding().getTenantId().getAttributeName());
    }
}
