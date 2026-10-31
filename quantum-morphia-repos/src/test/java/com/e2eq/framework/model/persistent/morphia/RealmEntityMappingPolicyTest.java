package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.CodeList;
import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.security.AccessInvite;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealmEntityMappingPolicyTest {

    private static final String SYSTEM_REALM = "quantum-auth";
    private static final String TENANT_REALM = "century-logistics-com";

    @Test
    void systemRealmMapsIdentityAndSecurityEntities() {
        assertTrue(shouldMap(SYSTEM_REALM, CredentialUserIdPassword.class, false));
        assertTrue(shouldMap(SYSTEM_REALM, Realm.class, false));
        assertTrue(shouldMap(SYSTEM_REALM, Policy.class, false));
        assertTrue(shouldMap(SYSTEM_REALM, UserProfile.class, false));
    }

    @Test
    void tenantRealmDoesNotMapIdentityAndSecurityEntitiesByDefault() {
        assertFalse(shouldMap(TENANT_REALM, CredentialUserIdPassword.class, false));
        assertFalse(shouldMap(TENANT_REALM, Realm.class, false));
        assertFalse(shouldMap(TENANT_REALM, Policy.class, false));
        assertFalse(shouldMap(TENANT_REALM, UserProfile.class, false));
    }

    @Test
    void tenantRealmStillMapsTenantAndMigrationEntities() {
        assertTrue(shouldMap(TENANT_REALM, CodeList.class, false));
        assertTrue(shouldMap(TENANT_REALM, DatabaseVersion.class, false));
    }

    @Test
    void compatibilityFlagCanMapGlobalEntitiesToTenantRealm() {
        assertTrue(shouldMap(TENANT_REALM, CredentialUserIdPassword.class, true));
        assertTrue(shouldMap(TENANT_REALM, Policy.class, true));
    }

    @Test
    void remoteModeNeverMapsIdentityEntitiesEvenInTheSystemRealm() {
        assertFalse(RealmEntityMappingPolicy.shouldMapToRealm(
            SYSTEM_REALM,
            SYSTEM_REALM,
            UserProfile.class,
            false,
            true,
            RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES,
            ""
        ));
        assertFalse(RealmEntityMappingPolicy.shouldMapToRealm(
            SYSTEM_REALM,
            SYSTEM_REALM,
            CredentialUserIdPassword.class,
            true,
            true,
            RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES,
            ""
        ));
        assertTrue(RealmEntityMappingPolicy.shouldMapToRealm(
            SYSTEM_REALM,
            SYSTEM_REALM,
            AccessInvite.class,
            false,
            true,
            RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES,
            ""
        ));
    }

    @Test
    void explicitClassExclusionIsHonored() {
        assertFalse(RealmEntityMappingPolicy.shouldMapToRealm(
            TENANT_REALM,
            SYSTEM_REALM,
            CodeList.class,
            false,
            "",
            CodeList.class.getName()
        ));
    }

    private boolean shouldMap(String realm, Class<?> entityType, boolean mapGlobalResourcesToTenantRealms) {
        return RealmEntityMappingPolicy.shouldMapToRealm(
            realm,
            SYSTEM_REALM,
            entityType,
            mapGlobalResourcesToTenantRealms,
            RealmEntityMappingPolicy.DEFAULT_TENANT_EXCLUDED_ENTITY_PACKAGE_PREFIXES,
            ""
        );
    }
}
