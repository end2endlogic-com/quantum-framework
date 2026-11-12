package com.e2eq.framework.security.principal;

/** An identity named in a resolution: the impersonator or the acting service. */
public record PrincipalRef(String userId, String subject) {
}
