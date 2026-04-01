package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmSetupStatus;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
@RegisterForReflection
public class AccessibleRealmInfo {
    private String refName;
    private String displayName;
    private RealmSetupStatus setupStatus;
    private Integer setupCompletionPercent;
    private String setupSummary;
    private Integer configuredSolutionCount;
    private Integer readySolutionCount;
    private Integer pendingSeedPackCount;
    private Integer pendingMigrationCount;
    private Date setupLastUpdated;

    public AccessibleRealmInfo(String refName, String displayName) {
        this.refName = refName;
        this.displayName = displayName;
    }

    public AccessibleRealmInfo(String refName,
                               String displayName,
                               RealmSetupStatus setupStatus,
                               Integer setupCompletionPercent,
                               String setupSummary,
                               Integer configuredSolutionCount,
                               Integer readySolutionCount,
                               Integer pendingSeedPackCount,
                               Integer pendingMigrationCount,
                               Date setupLastUpdated) {
        this.refName = refName;
        this.displayName = displayName;
        this.setupStatus = setupStatus;
        this.setupCompletionPercent = setupCompletionPercent;
        this.setupSummary = setupSummary;
        this.configuredSolutionCount = configuredSolutionCount;
        this.readySolutionCount = readySolutionCount;
        this.pendingSeedPackCount = pendingSeedPackCount;
        this.pendingMigrationCount = pendingMigrationCount;
        this.setupLastUpdated = setupLastUpdated;
    }

    public static AccessibleRealmInfo fromRealm(Realm realm) {
        if (realm == null) {
            return new AccessibleRealmInfo();
        }
        return new AccessibleRealmInfo(
                realm.getRefName(),
                realm.getDisplayName(),
                realm.getSetupStatus(),
                realm.getSetupCompletionPercent(),
                realm.getSetupSummary(),
                realm.getConfiguredSolutionCount(),
                realm.getReadySolutionCount(),
                realm.getPendingSeedPackCount(),
                realm.getPendingMigrationCount(),
                realm.getSetupLastUpdated()
        );
    }
}
