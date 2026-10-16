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

    /**
     * Optional. An invite is for exactly one realm: the realm of the request (its realm context /
     * X-Realm). When given, this must name that same realm; a wildcard or pattern is refused.
     */
    protected String realm;

    /**
     * Refused when it names more than one realm. Present only so a multi-realm request fails
     * explicitly ({@code 400 INVITE_SINGLE_REALM_REQUIRED}) instead of being silently narrowed;
     * granting access to several realms takes one invite per realm.
     */
    protected List<String> realms;

    /**
     * Applications the invitee may use in the invite's realm. Recorded on the invitee's
     * {@code UserRealmRole.authorizedApplications} for that realm on acceptance; when omitted that
     * field is left untouched.
     */
    protected List<String> authorizedApplications;

    protected Integer expiresInDays;

    protected String inviteMessage;
}
