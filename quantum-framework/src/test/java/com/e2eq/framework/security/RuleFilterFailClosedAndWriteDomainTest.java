package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityCheckException;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.TestBookRepo;
import com.e2eq.framework.test.BookModel;
import com.e2eq.framework.tests.securityrules.TestFailClosedResolvers;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end (replica-set Mongo) checks for two security behaviours of rule filters:
 *
 * <ol>
 *   <li>Fail closed: a matched ALLOW rule whose filter references a variable that cannot be
 *       resolved (missing, or its AccessListResolver threw) returns NO rows, never all rows.</li>
 *   <li>Write data-domain check: a create/update must satisfy the matched ALLOW rule filters for
 *       the write action, so a client-supplied dataDomain the caller has no privilege for is
 *       rejected, while the resolver-assigned domain still works.</li>
 * </ol>
 */
@QuarkusTest
public class RuleFilterFailClosedAndWriteDomainTest extends BaseRepoTest {

    private static final String AREA = "TEST";
    private static final String DOMAIN = "TEST";

    @Inject
    TestBookRepo bookRepo;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        SecurityContext.clear();
        deleteTestBooks();
        SecurityContext.clear();
    }

    // ---------------------------------------------------------------- Fix 1: reads fail closed

    @Test
    public void unresolvableVariableReturnsNoRows() {
        seedBooks();
        addRule("rwfc-missing", "title:^[${rwfcNoSuchVariable}]");

        assertEquals(Set.of(), visibleTitles("rwfc-missing"),
                "a rule filter with an unresolvable variable must match nothing, not everything");
    }

    @Test
    public void throwingResolverReturnsNoRows() {
        seedBooks();
        addRule("rwfc-broken", "title:^[${rwfcBroken}]");

        assertEquals(Set.of(), visibleTitles("rwfc-broken"),
                "a rule whose AccessListResolver threw must match nothing, not everything");
    }

    @Test
    public void resolvableVariableStillFilters() {
        seedBooks();
        addRule("rwfc-ok", "title:^[${rwfcTitles}]");

        assertEquals(Set.copyOf(TestFailClosedResolvers.ALLOWED_TITLES), visibleTitles("rwfc-ok"));
    }

    // ------------------------------------------------- Fix 2: writes check the record's domain

    @Test
    public void createWithResolverAssignedDomainSucceeds() {
        addRule("rwfc-writer", "dataDomain.tenantId:${pTenantId}");
        PrincipalContext writer = principal("rwfc-writer");

        BookModel saved;
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(writer, resource("save"))) {
            saved = bookRepo.save(testUtils.getTestRealm(), book("rwfc-own"));
        }
        assertNotNull(saved.getId());
        assertEquals(testUtils.getTestDataDomain().getTenantId(), saved.getDataDomain().getTenantId());
    }

    @Test
    public void createWithForeignClientSuppliedDomainIsDenied() {
        addRule("rwfc-writer", "dataDomain.tenantId:${pTenantId}");
        PrincipalContext writer = principal("rwfc-writer");

        BookModel foreign = book("rwfc-foreign");
        foreign.setDataDomain(foreignDomain());

        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(writer, resource("save"))) {
            SecurityCheckException ex = assertThrows(SecurityCheckException.class,
                    () -> bookRepo.save(testUtils.getTestRealm(), foreign));
            assertTrue(ex.getMessage().contains("rwfc-other-tenant"), ex.getMessage());
            assertTrue(ex.getMessage().toLowerCase().contains("area=" + AREA.toLowerCase()), ex.getMessage());
        }
        assertEquals(Set.of(), allTestTitles(), "the denied record must not have been written");
    }

    @Test
    public void updateMovingRecordIntoForeignDomainIsDenied() {
        addRule("rwfc-writer", "dataDomain.tenantId:${pTenantId}");
        PrincipalContext writer = principal("rwfc-writer");

        BookModel saved;
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(writer, resource("save"))) {
            saved = bookRepo.save(testUtils.getTestRealm(), book("rwfc-move"));
        }
        saved.setDataDomain(foreignDomain());
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(writer, resource("update"))) {
            assertThrows(SecurityCheckException.class, () -> bookRepo.save(testUtils.getTestRealm(), saved));
        }
    }

    @Test
    public void ignoreRulesScopeStillBypassesWriteCheck() {
        addRule("rwfc-writer", "dataDomain.tenantId:${pTenantId}");
        PrincipalContext writer = principal("rwfc-writer");

        BookModel foreign = book("rwfc-privileged");
        foreign.setDataDomain(foreignDomain());

        BookModel saved;
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(writer, resource("save"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            saved = bookRepo.save(testUtils.getTestRealm(), foreign);
        }
        assertNotNull(saved.getId());
        assertEquals("rwfc-other-tenant", saved.getDataDomain().getTenantId());
    }

    // ---------------------------------------------------------------------------- helpers

    private void seedBooks() {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("save"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            for (String title : List.of("rwfc-alpha", "rwfc-beta", "rwfc-gamma")) {
                BookModel b = book(title);
                b.setDataDomain(testUtils.getTestDataDomain());
                bookRepo.save(testUtils.getTestRealm(), b);
            }
        }
        assertEquals(Set.of("rwfc-alpha", "rwfc-beta", "rwfc-gamma"), allTestTitles(), "seed rows exist");
    }

    private Set<String> visibleTitles(String role) {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(principal(role), resource("list"))) {
            return bookRepo.getListByQuery(testUtils.getTestRealm(), 0, 500, null, null, null).stream()
                    .map(BookModel::getTitle)
                    .filter(t -> t != null && t.startsWith("rwfc-"))
                    .collect(Collectors.toSet());
        }
    }

    private Set<String> allTestTitles() {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("list"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            return bookRepo.getAllList(testUtils.getTestRealm()).stream()
                    .map(BookModel::getTitle)
                    .filter(t -> t != null && t.startsWith("rwfc-"))
                    .collect(Collectors.toSet());
        }
    }

    private void deleteTestBooks() {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("delete"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            for (BookModel b : bookRepo.getAllList(testUtils.getTestRealm())) {
                if (b.getTitle() != null && b.getTitle().startsWith("rwfc-")) {
                    bookRepo.delete(testUtils.getTestRealm(), b);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("cleanup failed", e);
        }
    }

    private void addRule(String role, String andFilter) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(role).withArea(AREA).withFunctionalDomain(DOMAIN).withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        Rule rule = new Rule.Builder()
                .withName(role + "-rule")
                .withSecurityURI(new SecurityURI(header, body))
                .withAndFilterString(andFilter)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .withFinalRule(true)
                .build();
        ruleContext.addRule(header, rule);
    }

    private PrincipalContext principal(String role) {
        return testUtils.getTestPrincipalContext(role + "@test.local", new String[]{role});
    }

    private ResourceContext resource(String action) {
        return new ResourceContext.Builder().withArea(AREA).withFunctionalDomain(DOMAIN).withAction(action).build();
    }

    private static BookModel book(String title) {
        BookModel b = new BookModel();
        b.setTitle(title);
        b.setRefName(title);
        return b;
    }

    private DataDomain foreignDomain() {
        DataDomain own = testUtils.getTestDataDomain();
        return new DataDomain(own.getOrgRefName(), own.getAccountNum(), "rwfc-other-tenant", 0, own.getOwnerId());
    }
}
