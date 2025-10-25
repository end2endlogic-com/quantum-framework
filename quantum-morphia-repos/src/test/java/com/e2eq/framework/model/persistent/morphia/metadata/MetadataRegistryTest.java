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
        assertEquals("CredentialUserIdPassword", js.fromCollection);
    }

    @Test
    public void resolveJoin_throws_onNonReferencePath() {
        DefaultMetadataRegistry md = new DefaultMetadataRegistry();
        assertThrows(QueryMetadataException.class, () -> md.resolveJoin(UserProfile.class, "email"));
    }
}
