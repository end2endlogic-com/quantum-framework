package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmTenantMembership;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessibleTenantInfo {
    private String tenantId;
    private String displayName;
    private String realmRefName;
    private String orgRefName;
    private String accountId;

    public static AccessibleTenantInfo fromDedicatedRealm(Realm realm) {
        if (realm == null || realm.getDomainContext() == null
                || realm.getDomainContext().getTenantId() == null
                || realm.getDomainContext().getTenantId().isBlank()) {
            return null;
        }
        return new AccessibleTenantInfo(
                realm.getDomainContext().getTenantId(),
                realm.getDisplayName() != null && !realm.getDisplayName().isBlank()
                        ? realm.getDisplayName()
                        : realm.getDomainContext().getTenantId(),
                realm.getRefName(),
                realm.getDomainContext().getOrgRefName(),
                realm.getDomainContext().getAccountId()
        );
    }

    public static AccessibleTenantInfo fromMembership(RealmTenantMembership membership) {
        if (membership == null || membership.getTenantId() == null || membership.getTenantId().isBlank()) {
            return null;
        }
        String display = membership.getDisplayName();
        if (display == null || display.isBlank()) {
            display = membership.getTenantId();
        }
        return new AccessibleTenantInfo(
                membership.getTenantId(),
                display,
                membership.getRealmRefName(),
                membership.getOrganizationRefName(),
                membership.getAccountId()
        );
    }
}
