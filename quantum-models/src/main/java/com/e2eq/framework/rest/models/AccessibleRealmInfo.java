package com.e2eq.framework.rest.models;

import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.RealmSetupStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.Date;

@Data
@NoArgsConstructor
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
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
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Schema(type = SchemaType.STRING, format = "date-time")
    private Date setupLastUpdated;
    /** Owning application refName (every realm belongs to exactly one application). */
    private String application;
    private String tenancyMode;
    private String deploymentType;

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
        AccessibleRealmInfo info = new AccessibleRealmInfo(
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
        if (realm.getApplicationRef() != null) {
            info.setApplication(realm.getApplicationRef().getEntityRefName());
        }
        if (realm.getTenancyMode() != null) {
            info.setTenancyMode(realm.getTenancyMode().name());
        }
        if (realm.getDeploymentType() != null) {
            info.setDeploymentType(realm.getDeploymentType().name());
        }
        return info;
    }
}
