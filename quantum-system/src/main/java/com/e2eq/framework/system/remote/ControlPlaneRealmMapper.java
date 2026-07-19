package com.e2eq.framework.system.remote;

import com.e2eq.framework.controlplane.model.RealmCatalogEntry;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.security.Realm;

/** Shared typed mapping at the generated control-plane contract boundary. */
public final class ControlPlaneRealmMapper {
    private ControlPlaneRealmMapper() {
    }

    public static Realm fromEntry(RealmCatalogEntry entry) {
        Realm realm = new Realm();
        realm.setRefName(entry.getRefName());
        realm.setDisplayName(entry.getDisplayName());
        realm.setDatabaseName(entry.getDatabaseName());
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
        entry.setEmailDomain(realm.getEmailDomain());
        entry.setConnectionString(realm.getConnectionString());
        if (realm.getDomainContext() != null) {
            entry.setTenantId(realm.getDomainContext().getTenantId());
            entry.setOrgRefName(realm.getDomainContext().getOrgRefName());
            entry.setAccountNumber(realm.getDomainContext().getAccountId());
        }
        return entry;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
