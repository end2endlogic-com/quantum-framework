package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRepoApplicationScopeTest {

    private final PolicyRepo repo = new PolicyRepo();

    @Test
    void policyMustMatchBothApplicationAndOperatingRealm() {
        Policy schedulerAcme = policy("scheduler", "acme-com");

        assertTrue(repo.matchesScope(schedulerAcme, "scheduler", "acme-com", false));
        assertFalse(repo.matchesScope(schedulerAcme, "reporting", "acme-com", false));
        assertFalse(repo.matchesScope(schedulerAcme, "scheduler", "other-com", false));
    }

    @Test
    void wildcardPolicyAppliesAcrossApplicationsAndRealms() {
        assertTrue(repo.matchesScope(policy("*", "*"), "scheduler", "acme-com", false));
    }

    @Test
    void strictModeRejectsLegacyUnscopedPolicies() {
        Policy legacy = policy(null, null);

        assertFalse(repo.matchesScope(legacy, "scheduler", "acme-com", false));
        assertTrue(repo.matchesScope(legacy, "scheduler", "acme-com", true));
    }

    @Test
    void admissionRejectsActionsOutsideTheApplicationVocabulary() {
        PolicyRepo admissionRepo = admissionRepo("read");
        Policy invalid = policy("scheduler", "acme-com");
        invalid.setRules(List.of(rule("delete")));

        assertThrows(IllegalArgumentException.class, () -> admissionRepo.setDefaultValues(invalid));
    }

    @Test
    void admissionAcceptsApplicationScopedCatalogActions() {
        PolicyRepo admissionRepo = admissionRepo("read");
        Policy valid = policy("scheduler", "acme-com");
        valid.setRules(List.of(rule("read")));

        admissionRepo.setDefaultValues(valid);
        assertTrue(valid.getId() != null);
    }

    @Test
    void functionalCatalogUsesTheConfiguredCentralPolicyStore() {
        FunctionalDomainRepo functionalDomainRepo = new FunctionalDomainRepo();
        functionalDomainRepo.defaultRealm = "scheduler-tenant-acme";
        functionalDomainRepo.policyStoreRealm = Optional.of("quantum-auth");

        assertTrue("quantum-auth".equals(functionalDomainRepo.getSecurityContextRealmId()));
    }

    private static Policy policy(String applicationId, String realmRefName) {
        Policy policy = new Policy();
        policy.setApplicationId(applicationId);
        policy.setRealmRefName(realmRefName);
        return policy;
    }

    private static PolicyRepo admissionRepo(String allowedAction) {
        Application application = new Application();
        application.setRefName("scheduler");

        FunctionalAction action = new FunctionalAction();
        action.setRefName(allowedAction);
        FunctionalDomain domain = new FunctionalDomain();
        domain.setApplicationId("scheduler");
        domain.setArea("workforce");
        domain.setRefName("schedule");
        domain.setFunctionalActions(List.of(action));

        PolicyRepo admissionRepo = new PolicyRepo();
        admissionRepo.policyAdmissionEnabled = true;
        admissionRepo.applicationRepo = new ApplicationRepo() {
            @Override
            public Optional<Application> findByRefNameWithIgnoreRules(String refName) {
                return "scheduler".equals(refName) ? Optional.of(application) : Optional.empty();
            }
        };
        admissionRepo.functionalDomainRepo = new FunctionalDomainRepo() {
            @Override
            public List<FunctionalDomain> findForApplicationWithIgnoreRules(String applicationId) {
                return "scheduler".equals(applicationId) ? List.of(domain) : List.of();
            }
        };
        return admissionRepo;
    }

    private static Rule rule(String action) {
        SecurityURIHeader header = new SecurityURIHeader("scheduler-user", "workforce", "schedule", action);
        return new Rule.Builder()
            .withName("scheduler-" + action)
            .withSecurityURI(new SecurityURI(header, new SecurityURIBody()))
            .withEffect(RuleEffect.ALLOW)
            .build();
    }
}
