package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.security.Realm;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class AccessibleRealmInfo {
    private String refName;
    private String displayName;
    private String defaultRealm;

    public static AccessibleRealmInfo fromRealm(Realm realm) {
        if (realm == null) {
            return new AccessibleRealmInfo();
        }
        return new AccessibleRealmInfo(
                realm.getRefName(),
                realm.getDisplayName(),
                realm.getDomainContext() != null ? realm.getDomainContext().getDefaultRealm() : null
        );
    }
}
