package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlPlaneRealmMapperTest {

    @Test
    void mapsRealmTenancyModeWithoutConflatingItWithDeploymentType() {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName("pooled-realm");
        entry.setDatabaseName("pooled-realm");
        entry.setEmailDomain("pooled.example.test");
        entry.setDeploymentType("SHARED");
        entry.setTenancyMode("MULTI_TENANT");

        var realm = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals(RealmTenancyMode.MULTI_TENANT, realm.getTenancyMode());
        assertEquals("MULTI_TENANT",
            ControlPlaneRealmMapper.toEntry(realm).getTenancyMode());
    }

    @Test
    void mapsRealmMembershipWritesWithoutDroppingOwnership() {
        RealmMembershipEntry entry = new RealmMembershipEntry();
        entry.setRealmRefName("helixor-code-D1");
        entry.setOrganizationRefName("HelixorAI");
        entry.setAccountId("0000000001");
        entry.setTenantId("development");
        entry.setMembershipRole("owner");
        entry.setParticipationStatus("ACTIVE");

        RealmTenantMembership membership = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals("HelixorAI-helixor-code-D1", membership.getRefName());
        assertEquals("owner", membership.getMembershipRole());
        assertEquals("HelixorAI", ControlPlaneRealmMapper.toEntry(membership).getOrganizationRefName());
    }

    @Test
    void mapsUserRealmRoleWritesWithoutDroppingApplicationGrants() {
        UserRealmRoleEntry entry = new UserRealmRoleEntry();
        entry.setUserId("mingardia@helixor.ai");
        entry.setRealmRefName("helixor-code-D1");
        entry.setRoles(List.of("system", "admin", "user"));
        entry.setAuthorizedApplications(List.of("helixor-code", "helixor-reasoning-ux"));
        entry.setDefaultApplication("helixor-code");
        entry.setAuthorizedTenantIds(List.of("tenant-a", "tenant-b"));
        entry.setSponsoringOrgRefName("HelixorAI");
        entry.setStatus("active");

        UserRealmRole role = ControlPlaneRealmMapper.fromEntry(entry);

        assertEquals("mingardia@helixor.ai-helixor-code-D1", role.getRefName());
        assertEquals(entry.getAuthorizedApplications(), role.getAuthorizedApplications());
        assertEquals(entry.getAuthorizedTenantIds(), role.getAuthorizedTenantIds());
        assertEquals("HelixorAI", ControlPlaneRealmMapper.toEntry(role).getSponsoringOrgRefName());
    }

    @Test
    void membershipReadModifyWritePreservesAllPersistedFields() throws Exception {
        RealmTenantMembership original = new RealmTenantMembership();
        populateMetadata(original);
        original.setRealmRefName("engineering");
        original.setOrganizationRefName("example-org");
        original.setRealmDisplayName("Engineering");
        original.setRealmEmailDomain("example.test");
        original.setDefaultAdminUserId("admin@example.test");
        original.setRealmEditionRefName("standard");
        original.setProvisioningMode("managed");
        original.setSetupStatus("ready");
        original.setSetupCompletionPercent(100);
        original.setAccountId("account");
        original.setTenantId("tenant");
        original.setParticipationStatus("ACTIVE");

        var wire = new com.fasterxml.jackson.databind.ObjectMapper();
        var entry = wire.readValue(wire.writeValueAsBytes(ControlPlaneRealmMapper.toEntry(original)),
            RealmMembershipEntry.class);
        RealmTenantMembership read = ControlPlaneRealmMapper.fromEntry(entry);
        read.setMembershipRole("participant");
        original.setMembershipRole("participant");
        assertEquals(original, ControlPlaneRealmMapper.fromEntry(ControlPlaneRealmMapper.toEntry(read)));
        assertEquals(original.getId().toHexString(), entry.getId());
    }

    @Test
    void roleReadModifyWritePreservesEntitlementAndMetadata() throws Exception {
        UserRealmRole original = new UserRealmRole();
        populateMetadata(original);
        original.setUserId("user@example.test");
        original.setSubject("stable-subject");
        original.setRealmRefName("engineering");
        original.setRoles(List.of("viewer"));
        original.setAuthorizedTenantRegEx("tenant-.*");
        original.setAuthorizedApplications(List.of("orders"));
        original.setDefaultApplication("orders");
        original.setAuthorizedTenantIds(List.of("tenant-a"));
        original.setSponsoringOrgRefName("example-org");
        original.setStatus("active");

        var wire = new com.fasterxml.jackson.databind.ObjectMapper();
        var entry = wire.readValue(wire.writeValueAsBytes(ControlPlaneRealmMapper.toEntry(original)),
            UserRealmRoleEntry.class);
        UserRealmRole read = ControlPlaneRealmMapper.fromEntry(entry);
        read.setRoles(List.of("admin"));
        original.setRoles(List.of("admin"));
        assertEquals(original, ControlPlaneRealmMapper.fromEntry(ControlPlaneRealmMapper.toEntry(read)));
        assertEquals("tenant-.*", entry.getAuthorizedTenantRegEx());
    }

    @Test
    void createPayloadDoesNotInventAnId() {
        var membership = new RealmTenantMembership();
        var role = new UserRealmRole();
        org.junit.jupiter.api.Assertions.assertNull(ControlPlaneRealmMapper.toEntry(membership).getId());
        org.junit.jupiter.api.Assertions.assertNull(ControlPlaneRealmMapper.toEntry(role).getId());
    }

    @Test
    void generatedContractsCoverEveryPersistedModelField() {
        assertPersistedFieldsCovered(RealmTenantMembership.class, RealmMembershipEntry.class);
        assertPersistedFieldsCovered(UserRealmRole.class, UserRealmRoleEntry.class);
    }

    private static void assertPersistedFieldsCovered(Class<?> model, Class<?> dto) {
        var names = java.util.Arrays.stream(dto.getDeclaredFields()).map(java.lang.reflect.Field::getName)
            .collect(java.util.stream.Collectors.toSet());
        for (Class<?> type = model; type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        && !field.isAnnotationPresent(dev.morphia.annotations.Transient.class)) {
                    org.junit.jupiter.api.Assertions.assertTrue(names.contains(field.getName()),
                        () -> "Contract drops persisted field " + field.getName());
                }
            }
        }
    }

    private static void populateMetadata(com.e2eq.framework.model.persistent.base.BaseModel model) {
        model.setId(new org.bson.types.ObjectId("507f1f77bcf86cd799439011"));
        model.setRefName("authoritative-ref");
        model.setDisplayName("Persisted display name");
        model.setVersion(7L);
        model.setDataDomain(new com.e2eq.framework.model.persistent.base.DataDomain(
            "example-org", "account", "tenant", 4, "owner"));
        model.getDataDomain().setBusinessTransactionId("transaction");
        model.getDataDomain().setLocationId("location");
        model.setAuditInfo(new com.e2eq.framework.model.persistent.base.AuditInfo(
            new java.util.Date(1700000000123L), "creator", new java.util.Date(1700000999456L), "editor"));
        model.setActiveStatus(com.e2eq.framework.model.persistent.base.ActiveStatus.ACTIVE);
        model.setTags(new String[] {"billing", "operations"});
        model.setUnmappedProperties(java.util.Map.of("futureField", "preserved"));
        var tag = new com.e2eq.framework.model.persistent.base.Tag();
        tag.setCategory("billing");
        tag.setTagDisplayName("Cost center");
        tag.setAdditionalData(java.util.Set.of("42"));
        model.setAdvancedTags(java.util.Set.of(tag));
        var event = new com.e2eq.framework.model.persistent.base.PersistentEvent();
        event.setEventType("provisioned");
        event.setEventDate(new java.util.Date(1700000555789L));
        event.setEventData(java.util.Map.of("source", "control-plane"));
        model.setPersistentEvents(List.of(event));
        var signatures = new com.e2eq.framework.model.persistent.base.Signatures();
        signatures.setEntityHash("entity-hash");
        signatures.setAuditInfoSignature("audit-signature");
        signatures.setReferencesSignature("references-signature");
        model.setSignatures(signatures);
        model.setReferences(java.util.Set.of(new com.e2eq.framework.model.persistent.base.ReferenceEntry(
            new org.bson.types.ObjectId("507f1f77bcf86cd799439012"), "Example", "ref")));
    }
}
