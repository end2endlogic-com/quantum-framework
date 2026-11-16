package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class AccessInviteAcceptResponse {
    private String userId;
    private String email;
    private String defaultRealm;
    private String inviteRefName;
    private List<String> grantedScopes;
    /** The tenant the invitee was placed in within the realm (pooled realms), else the realm's tenant. */
    private String tenantId;
    /**
     * The applications the accepted invite itself named (trimmed, de-duplicated, in the invite's
     * order); empty, never null, when the invite named none. This is the invite's own list, not the
     * union of every application the person can now use in the realm: a caller that hands the
     * invitee straight into the inviting application does so when this names exactly one.
     */
    @Builder.Default
    private List<String> authorizedApplications = new ArrayList<>();

    /** The invite's own applications; an explicit null reads as empty, never null. */
    public List<String> getAuthorizedApplications() {
        return authorizedApplications == null ? new ArrayList<>() : authorizedApplications;
    }

    public static AccessInviteAcceptResponse empty() {
        return AccessInviteAcceptResponse.builder()
            .grantedScopes(new ArrayList<>())
            .authorizedApplications(new ArrayList<>())
            .build();
    }
}
