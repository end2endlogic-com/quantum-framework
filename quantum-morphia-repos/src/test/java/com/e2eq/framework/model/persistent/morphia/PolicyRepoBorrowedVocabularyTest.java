package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Code;
import com.e2eq.framework.model.security.ApplicationRole;
import com.e2eq.framework.model.security.FunctionalAction;
import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.VocabularyReference;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Policy admission over vocabulary an application borrows through {@code uses}: admitted only for
 * the domains and actions the owning application holds, refused with a typed code otherwise.
 */
class PolicyRepoBorrowedVocabularyTest {

    private static final String OWNER = "quantum-system";
    private static final String BORROWER = "helixor-licensing";

    @Test
    void admitsBorrowedDomainTheOwnerHolds() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE", "VIEW", "DECIDE"));
        Policy policy = policy("registration-review", rule("registration-review", "SECURITY", "REGISTRATION_INTAKE", "DECIDE"));

        repo.setDefaultValues(policy);
        assertNotNull(policy.getId());
    }

    @Test
    void admitsBorrowedDomainCaseInsensitively() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE"));
        repo.setDefaultValues(policy("registration-intake", rule("registration-intake", "security", "registration_intake", "create")));
    }

    @Test
    void refusesBorrowedActionTheOwnerDoesNotHold() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE", "VIEW"));
        Policy policy = policy("registration-review", rule("registration-review", "SECURITY", "REGISTRATION_INTAKE", "DECIDE"));

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(policy));
        assertEquals(Code.POLICY_TRIPLE_UNDECLARED, refused.code());
        assertTrue(refused.getMessage().startsWith("POLICY_TRIPLE_UNDECLARED: "));
        assertTrue(refused.getMessage().contains(OWNER));
    }

    @Test
    void refusesBorrowedDomainTheOwnerDoesNotHold() {
        PolicyRepo repo = repo(List.of(), List.of());
        Policy policy = policy("registration-intake", rule("registration-intake", "SECURITY", "REGISTRATION_INTAKE", "CREATE"));

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(policy));
        assertEquals(Code.USES_UNKNOWN, refused.code());
    }

    @Test
    void refusesDomainOfAnAreaTheApplicationDoesNotList() {
        FunctionalDomain other = domain(OWNER, "SECURITY", "IDENTITY_CONTRIBUTION", "CREATE");
        List<FunctionalDomain> ownerRows = new ArrayList<>(ownerIntake("CREATE"));
        ownerRows.add(other);
        PolicyRepo repo = repo(List.of(), ownerRows);
        Policy policy = policy("registration-intake", rule("registration-intake", "SECURITY", "IDENTITY_CONTRIBUTION", "CREATE"));

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(policy));
        assertEquals(Code.POLICY_TRIPLE_UNDECLARED, refused.code());
    }

    @Test
    void refusesBorrowedGrantToARoleTheApplicationDoesNotDeclare() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE"));
        Application application = repo.applicationRepo.findByRefNameWithIgnoreRules(BORROWER).orElseThrow();
        application.setRoles(List.of(role("registration-intake")));
        Policy policy = policy("admin", rule("admin", "SECURITY", "REGISTRATION_INTAKE", "CREATE"));

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(policy));
        assertEquals(Code.POLICY_BORROWED_FOREIGN_ROLE, refused.code());

        repo.setDefaultValues(policy("registration-intake", rule("registration-intake", "SECURITY", "REGISTRATION_INTAKE", "CREATE")));
    }

    @Test
    void borrowedOnlyApplicationCannotWriteWildcards() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE"));
        for (Rule rule : List.of(rule("registration-intake", "SECURITY", "*", "*"),
                                 rule("registration-intake", "*", "*", "*"),
                                 rule("registration-intake", "OTHER", "*", "VIEW"))) {
            PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class,
                () -> repo.setDefaultValues(policy("registration-intake", rule)));
            assertEquals(Code.POLICY_WILDCARD_OUTSIDE_APPLICATION, refused.code());
        }
    }

    @Test
    void wildcardDomainOverABorrowedAreaIsRefusedEvenWhenTheApplicationOwnsOtherAreas() {
        PolicyRepo repo = repo(List.of(domain(BORROWER, "LICENSING", "LICENSE_REQUEST", "VIEW")), ownerIntake("CREATE"));
        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class,
            () -> repo.setDefaultValues(policy("registration-intake", rule("registration-intake", "SECURITY", "*", "CREATE"))));
        assertEquals(Code.POLICY_WILDCARD_OUTSIDE_APPLICATION, refused.code());

        // Over an area it owns, a wildcard domain is admitted as before.
        repo.setDefaultValues(policy("registration-intake", rule("registration-intake", "LICENSING", "*", "VIEW")));
    }

    @Test
    void borrowedReferenceTheOwnerDoesNotHoldIsNoVocabulary() {
        // Even a wildcard rule is refused before the wildcard shortcut: nothing is held to lend.
        PolicyRepo repo = repo(List.of(), List.of());
        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class,
            () -> repo.setDefaultValues(policy("registration-intake", rule("registration-intake", "SECURITY", "*", "*"))));
        assertEquals(Code.USES_UNKNOWN, refused.code());
    }

    @Test
    void borrowedRoleCheckDoesNotDependOnThePrincipalType() {
        PolicyRepo repo = repo(List.of(), ownerIntake("CREATE"));
        Application application = repo.applicationRepo.findByRefNameWithIgnoreRules(BORROWER).orElseThrow();
        application.setRoles(List.of(role("registration-intake")));
        Policy asUser = policy("user", rule("user", "SECURITY", "REGISTRATION_INTAKE", "CREATE"));
        asUser.setPrincipalType(Policy.PrincipalType.USER);

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(asUser));
        assertEquals(Code.POLICY_BORROWED_FOREIGN_ROLE, refused.code());
    }

    @Test
    void ownedDomainWinsOverABorrowedReferenceOfTheSameKey() {
        // dev/prod still hold licensing 1.1.0's owned REGISTRATION_INTAKE row; it is judged as owned.
        PolicyRepo repo = repo(List.of(domain(BORROWER, "SECURITY", "REGISTRATION_INTAKE", "CREATE", "VIEW", "DECIDE")), List.of());
        repo.setDefaultValues(policy("registration-review", rule("registration-review", "SECURITY", "REGISTRATION_INTAKE", "DECIDE")));
    }

    @Test
    void ownVocabularyStillRefusesUnknownActionsWithTheirCode() {
        PolicyRepo repo = repo(List.of(domain(BORROWER, "LICENSING", "LICENSE_REQUEST", "VIEW")), List.of());
        Policy policy = policy("registration-intake", rule("registration-intake", "LICENSING", "LICENSE_REQUEST", "DELETE"));

        PolicyAdmissionRefusedException refused = assertThrows(PolicyAdmissionRefusedException.class, () -> repo.setDefaultValues(policy));
        assertEquals(Code.POLICY_TRIPLE_UNDECLARED, refused.code());
    }

    private static List<FunctionalDomain> ownerIntake(String... actions) {
        return List.of(domain(OWNER, "SECURITY", "REGISTRATION_INTAKE", actions));
    }

    private static PolicyRepo repo(List<FunctionalDomain> borrowerRows, List<FunctionalDomain> ownerRows) {
        Application application = new Application();
        application.setRefName(BORROWER);
        VocabularyReference use = new VocabularyReference();
        use.setApplication(OWNER);
        use.setArea("SECURITY");
        use.setDomains(List.of("REGISTRATION_INTAKE"));
        application.setUses(List.of(use));

        PolicyRepo repo = new PolicyRepo();
        repo.policyAdmissionEnabled = true;
        repo.applicationRepo = new ApplicationRepo() {
            @Override
            public Optional<Application> findByRefNameWithIgnoreRules(String refName) {
                return BORROWER.equals(refName) ? Optional.of(application) : Optional.empty();
            }
        };
        repo.functionalDomainRepo = new FunctionalDomainRepo() {
            @Override
            public List<FunctionalDomain> findForApplicationWithIgnoreRules(String applicationId) {
                // As the real repo does, '*'-tagged rows come back for every application.
                List<FunctionalDomain> out = new ArrayList<>();
                List<FunctionalDomain> all = new ArrayList<>(borrowerRows);
                all.addAll(ownerRows);
                for (FunctionalDomain row : all) {
                    if ("*".equals(row.getApplicationId()) || row.getApplicationId().equalsIgnoreCase(applicationId)) {
                        out.add(row);
                    }
                }
                return out;
            }
        };
        return repo;
    }

    private static FunctionalDomain domain(String applicationId, String area, String refName, String... actions) {
        FunctionalDomain domain = new FunctionalDomain();
        domain.setApplicationId(applicationId);
        domain.setArea(area);
        domain.setRefName(refName);
        List<FunctionalAction> rows = new ArrayList<>();
        for (String name : actions) {
            FunctionalAction action = new FunctionalAction();
            action.setRefName(name);
            rows.add(action);
        }
        domain.setFunctionalActions(rows);
        return domain;
    }

    private static ApplicationRole role(String name) {
        ApplicationRole role = new ApplicationRole();
        role.setName(name);
        return role;
    }

    private static Policy policy(String principal, Rule rule) {
        Policy policy = new Policy();
        policy.setApplicationId(BORROWER);
        policy.setRealmRefName("*");
        policy.setPrincipalId(principal);
        policy.setPrincipalType(Policy.PrincipalType.ROLE);
        policy.setRules(List.of(rule));
        return policy;
    }

    private static Rule rule(String identity, String area, String domain, String action) {
        SecurityURIHeader header = new SecurityURIHeader(identity, area, domain, action);
        return new Rule.Builder()
            .withName(identity + "-" + action)
            .withSecurityURI(new SecurityURI(header, new SecurityURIBody()))
            .withEffect(RuleEffect.ALLOW)
            .build();
    }
}
