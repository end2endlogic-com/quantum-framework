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

    public static AccessInviteAcceptResponse empty() {
        return AccessInviteAcceptResponse.builder()
            .grantedScopes(new ArrayList<>())
            .build();
    }
}
