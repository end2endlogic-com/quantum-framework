package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@Builder
public class AccessInviteResponse {
    private String id;
    private String refName;
    private String displayName;
    private String email;
    private String targetUserId;
    private List<String> scopeRefNames;
    private List<String> legalEntityRefNames;
    private List<String> grantedRoles;
    private List<String> allowedFunctionalAreas;
    private List<String> allowedFunctionalDomains;
    private List<String> allowedActions;
    private String status;
    private Date expiresAt;
    private Date acceptedAt;
    private String acceptedUserId;
    private String inviteToken;

    public static AccessInviteResponse empty() {
        return AccessInviteResponse.builder()
            .scopeRefNames(new ArrayList<>())
            .legalEntityRefNames(new ArrayList<>())
            .grantedRoles(new ArrayList<>())
            .allowedFunctionalAreas(new ArrayList<>())
            .allowedFunctionalDomains(new ArrayList<>())
            .allowedActions(new ArrayList<>())
            .build();
    }
}
