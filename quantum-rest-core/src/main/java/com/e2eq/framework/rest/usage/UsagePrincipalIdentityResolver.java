package com.e2eq.framework.rest.usage;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Resolves the effective post-authentication identity established by the framework security filter. */
@ApplicationScoped
public class UsagePrincipalIdentityResolver {

    private final Supplier<Optional<PrincipalContext>> contextSupplier;

    public UsagePrincipalIdentityResolver() {
        this(SecurityContext::getPrincipalContext);
    }

    UsagePrincipalIdentityResolver(Supplier<Optional<PrincipalContext>> contextSupplier) {
        this.contextSupplier = Objects.requireNonNull(contextSupplier, "contextSupplier");
    }

    public UsagePrincipalIdentity resolve() {
        PrincipalContext context = contextSupplier.get()
                .orElseThrow(() -> unavailable("Framework PrincipalContext is unavailable"));
        String tenant = context.getDataDomain() == null
                ? null
                : trimToNull(context.getDataDomain().getTenantId());
        if (tenant == null) {
            tenant = trimToNull(context.getDefaultRealm());
        }
        String subject = trimToNull(context.getSubjectId());
        if (subject == null) {
            subject = trimToNull(context.getUserId());
        }
        if (tenant == null || subject == null) {
            throw unavailable("Framework PrincipalContext is missing effective tenant/realm or subject data");
        }
        return new UsagePrincipalIdentity(tenant, subject);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UsageEnforcementStateException unavailable(String message) {
        return new UsageEnforcementStateException(
                UsageEnforcementStateException.PRINCIPAL_IDENTITY_UNAVAILABLE, message);
    }
}
