package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.security.runtime.RuleContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoSecurityContextResolverTest {

    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void usesExistingRealmOverrideWhenResourceContextIsMissing() {
        DomainContext realmOverride = DomainContext.builder()
                .tenantId("tenant-a")
                .defaultRealm("tenant-a")
                .orgRefName("ORG-A")
                .accountId("ACCT-A")
                .dataSegment(0)
                .build();

        PrincipalContext principalContext = new PrincipalContext.Builder()
                .withDefaultRealm("system-com")
                .withDomainContext(realmOverride)
                .withDataDomain(realmOverride.toDataDomain("user@example.com"))
                .withOriginalDataDomain(DataDomain.builder()
                        .tenantId("system-com")
                        .orgRefName("SYSTEM")
                        .accountNum("SYSTEM")
                        .ownerId("user@example.com")
                        .dataSegment(0)
                        .build())
                .withUserId("user@example.com")
                .withRoles(new String[]{"ADMIN"})
                .withScope("AUTHENTICATED")
                .withRealmOverrideActive(true)
                .build();

        SecurityContext.setPrincipalContext(principalContext);

        RepoSecurityContextResolver resolver = new RepoSecurityContextResolver(
                null,
                null,
                null,
                new RuleContext(),
                "system-com");

        assertEquals("tenant-a", resolver.getSecurityContextRealmId());
        assertTrue(SecurityContext.getResourceContext().isPresent());
        assertSame(ResourceContext.DEFAULT_ANONYMOUS_CONTEXT, SecurityContext.getResourceContext().get());
    }

    @Test
    void theRebuiltApplicationIsTheConfiguredOneThenAV1AzpThenTheAuthoritysOwn() {
        io.quarkus.security.identity.SecurityIdentity v1 = io.quarkus.security.runtime.QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "alice").addAttribute("azp", "signed-in-app").build();
        io.quarkus.security.identity.SecurityIdentity v2 = io.quarkus.security.runtime.QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "alice").addAttribute("token_type", "access").build();
        assertEquals("configured", new RepoSecurityContextResolver(v1, null, null, new RuleContext(), "r",
                "configured", "quantum-system").rebuiltApplication());
        assertEquals("signed-in-app", new RepoSecurityContextResolver(v1, null, null, new RuleContext(), "r",
                null, "quantum-system").rebuiltApplication(), "a v1 identity keeps its azp where none is configured");
        assertEquals("quantum-system", new RepoSecurityContextResolver(v2, null, null, new RuleContext(), "r",
                null, "quantum-system").rebuiltApplication(), "a v2 identity names none: the authority's own");
        assertEquals(null, new RepoSecurityContextResolver(v2, null, null, new RuleContext(), "r",
                null, null).rebuiltApplication());
    }

    @Test
    void onlyAV2AccessTokenTakesTheAuthoritysOwnApplication() {
        // A v1 role-bearing service token and a v2 service token name no azp; SecurityFilter
        // resolves them for no application, so the repository must not pick Auth's own.
        io.quarkus.security.identity.SecurityIdentity v1Service = io.quarkus.security.runtime.QuarkusSecurityIdentity
                .builder().setPrincipal(() -> "svc").addRole("admin").build();
        io.quarkus.security.identity.SecurityIdentity v2Service = io.quarkus.security.runtime.QuarkusSecurityIdentity
                .builder().setPrincipal(() -> "svc").addAttribute("token_type", "service").build();
        assertEquals(null, new RepoSecurityContextResolver(v1Service, null, null, new RuleContext(), "r",
                null, "quantum-system").rebuiltApplication(), "a v1 service token at Auth keeps no application");
        assertEquals(null, new RepoSecurityContextResolver(v2Service, null, null, new RuleContext(), "r",
                null, "quantum-system").rebuiltApplication(), "a v2 service token at Auth keeps no application");
    }
}
