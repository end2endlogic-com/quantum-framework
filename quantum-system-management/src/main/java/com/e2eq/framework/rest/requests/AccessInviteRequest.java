package com.e2eq.framework.rest.requests;

import jakarta.validation.constraints.Email;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode
@SuperBuilder
@ToString
@NoArgsConstructor
public class AccessInviteRequest {
    @Email
    protected String email;

    protected String targetUserId;

    protected List<String> scopeRefNames = new ArrayList<>();

    protected List<String> legalEntityRefNames = new ArrayList<>();

    protected List<String> grantedRoles = new ArrayList<>();

    protected List<String> allowedFunctionalAreas = new ArrayList<>();

    protected List<String> allowedFunctionalDomains = new ArrayList<>();

    protected List<String> allowedActions = new ArrayList<>();

    protected Integer expiresInDays;

    protected String inviteMessage;
}
