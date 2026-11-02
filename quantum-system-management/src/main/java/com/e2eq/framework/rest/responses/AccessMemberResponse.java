package com.e2eq.framework.rest.responses;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** One member of a tenant: a realm assignment that authorizes it. Never carries credentials or tokens. */
@Data
@Builder
public class AccessMemberResponse {
    private String userId;
    private String realmRefName;
    private String tenantId;
    /** active, invited or suspended. */
    private String status;
    /** Realm-scoped roles; they apply in every tenant the assignment authorizes. */
    private List<String> roles;
    private List<String> authorizedApplications;
    private String defaultApplication;
}
