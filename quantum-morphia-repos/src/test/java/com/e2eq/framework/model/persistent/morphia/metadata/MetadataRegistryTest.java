package com.e2eq.framework.model.persistent.morphia.metadata;

import com.e2eq.framework.model.persistent.morphia.metadata.DefaultMetadataRegistry;
import com.e2eq.framework.model.persistent.morphia.metadata.JoinSpec;
import com.e2eq.framework.model.persistent.morphia.metadata.QueryMetadataException;
import com.e2eq.framework.model.security.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataRegistryTest {

    @Test
    public void resolveJoin_singleReference_onUserProfile() {
        DefaultMetadataRegistry md = new DefaultMetadataRegistry();
        JoinSpec js = md.resolveJoin(UserProfile.class, "credentialUserIdPasswordRef");
        assertNotNull(js);
        assertEquals("credentialUserIdPasswordRef.entityId", js.localIdExpr);
        assertEquals("_id", js.remoteIdField);
        assertEquals("dataDomain.tenantId", js.tenantField);
        assertFalse(js.localIsArray);
        // fromCollection resolved via @ReferenceTarget on the field
        assertNotNull(js.fromCollection);
        assertEquals("credentialUserIdPassword", js.fromCollection);
    }

    @Test
    public void resolveJoin_throws_onNonReferencePath() {
        DefaultMetadataRegistry md = new DefaultMetadataRegistry();
        assertThrows(QueryMetadataException.class, () -> md.resolveJoin(UserProfile.class, "email"));
    }

    @dev.morphia.annotations.Entity("suppliers")
    static class DummySupplier extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel {
        @Override public String bmFunctionalArea() { return "procurement"; }
        @Override public String bmFunctionalDomain() { return "suppliers"; }
    }

    @dev.morphia.annotations.Entity("orders")
    static class DummyOrder extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel {
        @com.e2eq.framework.model.persistent.base.ReferenceTarget(target = DummySupplier.class)
        com.e2eq.framework.model.persistent.base.EntityReference supplierRef;

        @Override public String bmFunctionalArea() { return "sales"; }
        @Override public String bmFunctionalDomain() { return "orders"; }
    }

    @dev.morphia.annotations.Entity("customers")
    static class DummyCustomer extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel {
        @com.e2eq.framework.model.persistent.base.ReferenceTarget(target = DummyOrder.class)
        com.e2eq.framework.model.persistent.base.EntityReference orderRef;

        @Override public String bmFunctionalArea() { return "crm"; }
        @Override public String bmFunctionalDomain() { return "customers"; }
    }

    @Test
    public void resolveJoin_multiHopReference_CustomerToOrderToSupplier() {
        DefaultMetadataRegistry md = new DefaultMetadataRegistry();
        JoinSpec js = md.resolveJoin(DummyCustomer.class, "orderRef.supplierRef");
        assertNotNull(js);
        assertEquals("suppliers", js.fromCollection);
        assertEquals("orderRef.supplierRef.entityId", js.localIdExpr);
        assertEquals("_id", js.remoteIdField);
        assertEquals("dataDomain.tenantId", js.tenantField);
        assertFalse(js.localIsArray);
        assertEquals(DummySupplier.class, js.targetType);
    }
}
