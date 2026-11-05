package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.securityrules.PrincipalContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Internal immutable projection after principal admission. It is not a resource permission result or a new public SPI. */
record ResolvedAuthorizationContext(String issuer, String authenticatedSubject, String principalSubject, String userId,
                                    String applicationId, String realm, String scope,
                                    Set<String> roles, Domain domain,
                                    String impersonatedBySubject, String actingOnBehalfOfSubject) {
    ResolvedAuthorizationContext {
        roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

    static ResolvedAuthorizationContext from(String issuer, String authenticatedSubject, PrincipalContext principal) {
        var data = principal.getDataDomain();
        Domain domain = data == null ? null : new Domain(data.getOrgRefName(), data.getAccountNum(),
                data.getTenantId(), data.getDataSegment(), data.getOwnerId(), data.getLocationId(),
                data.getBusinessTransactionId());
        return new ResolvedAuthorizationContext(issuer, authenticatedSubject, principal.getSubjectId(),
                principal.getUserId(), principal.getApplicationId(), principal.getDefaultRealm(), principal.getScope(),
                new LinkedHashSet<>(Arrays.asList(principal.getRoles())), domain,
                principal.getImpersonatedBySubject(), principal.getActingOnBehalfOfSubject());
    }

    record Domain(String organization, String account, String tenant, int segment,
                  String owner, String location, String businessTransaction) { }
}
