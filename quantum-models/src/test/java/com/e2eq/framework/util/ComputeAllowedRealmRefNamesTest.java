package com.e2eq.framework.util;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecurityUtils#computeAllowedRealmRefNames}
 * covering the authorizedRealms, realmRegEx, and default-realm fallback paths.
 */
class ComputeAllowedRealmRefNamesTest {

    private SecurityUtils securityUtils;

    @BeforeEach
    void setUp() {
        securityUtils = new SecurityUtils();
    }

    private CredentialUserIdPassword buildCredential(
            List<CredentialUserIdPassword.RealmEntry> authorizedRealms,
            String realmRegEx,
            String defaultRealm) {
        CredentialUserIdPassword cred = new CredentialUserIdPassword();
        cred.setAuthorizedRealms(authorizedRealms);
        cred.setRealmRegEx(realmRegEx);
        cred.setUserId("test-user");
        cred.setSubject("test-user");
        cred.setLastUpdate(new Date());
        cred.setDomainContext(DomainContext.builder()
                .tenantId("t1")
                .defaultRealm(defaultRealm)
                .orgRefName("org1")
                .accountId("acct1")
                .build());
        return cred;
    }

    private CredentialUserIdPassword.RealmEntry entry(String refName) {
        CredentialUserIdPassword.RealmEntry e = new CredentialUserIdPassword.RealmEntry();
        e.setRealmRefName(refName);
        return e;
    }

    @Test
    void authorizedRealmsIntersectsCandidates() {
        List<CredentialUserIdPassword.RealmEntry> entries = List.of(entry("realm-a"), entry("realm-c"));
        CredentialUserIdPassword cred = buildCredential(entries, null, "realm-a");
        List<String> candidates = List.of("realm-a", "realm-b", "realm-c", "realm-d");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("realm-a", "realm-c"), result);
    }

    @Test
    void authorizedRealmsCaseInsensitive() {
        List<CredentialUserIdPassword.RealmEntry> entries = List.of(entry("Realm-A"));
        CredentialUserIdPassword cred = buildCredential(entries, null, "realm-a");
        List<String> candidates = List.of("realm-a");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(1, result.size());
        assertEquals("realm-a", result.get(0));
    }

    @Test
    void regexWildcardMatchesAll() {
        CredentialUserIdPassword cred = buildCredential(null, "*", "realm-a");
        List<String> candidates = List.of("realm-a", "realm-b", "realm-c");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(candidates, result);
    }

    @Test
    void regexPatternFilters() {
        CredentialUserIdPassword cred = buildCredential(null, "acme-.*", "other");
        List<String> candidates = List.of("acme-prod", "acme-dev", "other-realm");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("acme-prod", "acme-dev"), result);
    }

    @Test
    void fallbackToDefaultRealm() {
        CredentialUserIdPassword cred = buildCredential(null, null, "realm-b");
        List<String> candidates = List.of("realm-a", "realm-b", "realm-c");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("realm-b"), result);
    }

    @Test
    void defaultRealmNotInCandidatesReturnsEmpty() {
        CredentialUserIdPassword cred = buildCredential(null, null, "realm-x");
        List<String> candidates = List.of("realm-a", "realm-b");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyAuthorizedRealmsListFallsToRegex() {
        CredentialUserIdPassword cred = buildCredential(new ArrayList<>(), "realm-b", "other");
        List<String> candidates = List.of("realm-a", "realm-b");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("realm-b"), result);
    }

    @Test
    void nullCredentialThrowsNPE() {
        assertThrows(NullPointerException.class,
                () -> securityUtils.computeAllowedRealmRefNames(null, List.of("r")));
    }

    @Test
    void authorizedRealmsUnionsDefaultRealm() {
        List<CredentialUserIdPassword.RealmEntry> entries = List.of(entry("realm-a"));
        CredentialUserIdPassword cred = buildCredential(entries, null, "realm-c");
        List<String> candidates = List.of("realm-a", "realm-b", "realm-c");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("realm-a", "realm-c"), result);
    }

    @Test
    void authorizedRealmsDefaultRealmNotInCatalogIsExcluded() {
        List<CredentialUserIdPassword.RealmEntry> entries = List.of(entry("realm-a"));
        CredentialUserIdPassword cred = buildCredential(entries, null, "realm-x");
        List<String> candidates = List.of("realm-a", "realm-b");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("realm-a"), result);
    }

    @Test
    void regexUnionsDefaultRealm() {
        CredentialUserIdPassword cred = buildCredential(null, "acme-.*", "other-realm");
        List<String> candidates = List.of("acme-prod", "acme-dev", "other-realm");

        List<String> result = securityUtils.computeAllowedRealmRefNames(cred, candidates);

        assertEquals(List.of("acme-prod", "acme-dev", "other-realm"), result);
    }
}
