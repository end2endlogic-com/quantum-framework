package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.controlplane.model.RealmMembershipEntry;
import com.e2eq.framework.controlplane.model.UserRealmRoleEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmDeploymentType;
import com.e2eq.framework.model.security.RealmTenancyMode;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.rest.models.ObjectIdJsonSerializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.bson.types.ObjectId;

import java.io.IOException;

/** Shared typed mapping at the generated control-plane contract boundary. */
public final class ControlPlaneRealmMapper {
    // Use the generated schema for every persisted field, including nested metadata.
    // ObjectIds cross the JSON seam as their exact hexadecimal string; dates use epoch millis.
    private static final ObjectMapper MEMBERSHIP_MAPPER = membershipMapper();

    private static ObjectMapper membershipMapper() {
        var module = new SimpleModule();
        module.addSerializer(ObjectId.class,
            new ObjectIdJsonSerializer());
        module.addDeserializer(ObjectId.class,
            new JsonDeserializer<ObjectId>() {
                @Override
                public ObjectId deserialize(JsonParser parser,
                        DeserializationContext context) throws IOException {
                    return new ObjectId(parser.getValueAsString());
                }
            });
        return new ObjectMapper().registerModule(module);
    }

    private ControlPlaneRealmMapper() {
    }

    public static Realm fromEntry(RealmCatalogEntry entry) {
        Realm realm = new Realm();
        realm.setRefName(entry.getRefName());
        realm.setDisplayName(entry.getDisplayName());
        realm.setDatabaseName(entry.getDatabaseName());
        realm.setDeploymentType(parseDeploymentType(entry.getDeploymentType()));
        realm.setTenancyMode(parseTenancyMode(entry.getTenancyMode()));
        realm.setEmailDomain(entry.getEmailDomain());
        realm.setConnectionString(entry.getConnectionString());
        if (hasText(entry.getTenantId()) && hasText(entry.getOrgRefName())
                && hasText(entry.getAccountNumber())) {
            realm.setDomainContext(DomainContext.builder()
                .tenantId(entry.getTenantId())
                .orgRefName(entry.getOrgRefName())
                .accountId(entry.getAccountNumber())
                .defaultRealm(entry.getRefName())
                .build());
        }
        return realm;
    }

    public static RealmCatalogEntry toEntry(Realm realm) {
        RealmCatalogEntry entry = new RealmCatalogEntry();
        entry.setRefName(realm.getRefName());
        entry.setDisplayName(realm.getDisplayName());
        entry.setDatabaseName(realm.getDatabaseName());
        entry.setDeploymentType(realm.getDeploymentType().name());
        entry.setTenancyMode(realm.getTenancyMode().name());
        entry.setEmailDomain(realm.getEmailDomain());
        entry.setConnectionString(realm.getConnectionString());
        if (realm.getDomainContext() != null) {
            entry.setTenantId(realm.getDomainContext().getTenantId());
            entry.setOrgRefName(realm.getDomainContext().getOrgRefName());
            entry.setAccountNumber(realm.getDomainContext().getAccountId());
        }
        return entry;
    }

    public static RealmTenantMembership fromEntry(RealmMembershipEntry entry) {
        RealmTenantMembership membership = MEMBERSHIP_MAPPER.convertValue(entry, RealmTenantMembership.class);
        // Legacy/create callers omit refName; existing records carry their authoritative identity.
        if (membership.getRefName() == null) {
            membership.setRefName(entry.getOrganizationRefName() + "-" + entry.getRealmRefName());
        }
        return membership;
    }

    public static RealmMembershipEntry toEntry(RealmTenantMembership membership) {
        return MEMBERSHIP_MAPPER.convertValue(membership, RealmMembershipEntry.class);
    }

    public static UserRealmRole fromEntry(UserRealmRoleEntry entry) {
        UserRealmRole role = MEMBERSHIP_MAPPER.convertValue(entry, UserRealmRole.class);
        if (role.getRefName() == null) {
            role.setRefName(entry.getUserId() + "-" + entry.getRealmRefName());
        }
        if (role.getSubject() == null) {
            role.setSubject(entry.getUserId());
        }
        return role;
    }

    public static UserRealmRoleEntry toEntry(UserRealmRole role) {
        return MEMBERSHIP_MAPPER.convertValue(role, UserRealmRoleEntry.class);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static RealmDeploymentType parseDeploymentType(String value) {
        if (!hasText(value)) {
            return RealmDeploymentType.DEDICATED;
        }
        try {
            return RealmDeploymentType.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                "Unsupported realm deploymentType from control plane: " + value,
                error);
        }
    }

    private static RealmTenancyMode parseTenancyMode(String value) {
        if (!hasText(value)) {
            return RealmTenancyMode.SINGLE_TENANT;
        }
        try {
            return RealmTenancyMode.valueOf(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                "Unsupported realm tenancyMode from control plane: " + value,
                error);
        }
    }
}
