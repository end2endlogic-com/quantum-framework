package com.e2eq.framework.security;

import com.e2eq.framework.api.query.QueryGatewayResource;
import com.e2eq.framework.model.general.MenuHierarchyModel;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.CompletionTaskRepo;
import com.e2eq.framework.model.persistent.morphia.MenuHierarchyRepo;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.morphia.RepoUtils;
import com.e2eq.framework.model.persistent.tasks.CompletionTask;
import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.persistent.SgfExpandRootRepo;
import com.e2eq.framework.persistent.TestBookRepo;
import com.e2eq.framework.test.BookModel;
import com.e2eq.framework.test.SgfExpandRoot;
import com.e2eq.framework.test.SgfExpandTarget;
import com.e2eq.framework.test.SgfSealedTarget;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security regression (replica-set Mongo) for the row-level gates that the independent review of
 * the rule-filter {@code $and} composition fix found missing.
 *
 * <p>Each test seeds one row in the caller's own data domain and one that differs from it only in
 * the partition field a rule scopes on, then drives a path with the foreign row as its target.
 * A path that reads, counts, exports, hydrates, updates or deletes the foreign row is a leak; the
 * assertion message lists every leaking path.</p>
 */
@QuarkusTest
public class SecurityGatesFollowupTest extends BaseRepoTest {

    private static final String AREA = "TEST";
    private static final String DOMAIN = "TEST";
    private static final String PREFIX = "sgf-";

    enum Partition {
        TENANT("dataDomain.tenantId", "dataDomain.tenantId:${pTenantId}", "sgf-other-tenant"),
        OWNER("dataDomain.ownerId", "dataDomain.ownerId:${ownerId}", "sgf-other-owner"),
        ORG("dataDomain.orgRefName", "dataDomain.orgRefName:${orgRefName}", "sgf-other-org");

        final String field;
        final String ruleFilter;
        final String foreignValue;

        Partition(String field, String ruleFilter, String foreignValue) {
            this.field = field;
            this.ruleFilter = ruleFilter;
            this.foreignValue = foreignValue;
        }

        String userQuery() {
            return field + ":" + foreignValue;
        }

        DataDomain foreign(DataDomain own) {
            DataDomain dd = copy(own);
            switch (this) {
                case TENANT -> dd.setTenantId(foreignValue);
                case OWNER -> dd.setOwnerId(foreignValue);
                case ORG -> dd.setOrgRefName(foreignValue);
            }
            return dd;
        }
    }

    @Inject
    TestBookRepo bookRepo;

    @Inject
    SgfExpandRootRepo expandRootRepo;

    @Inject
    CompletionTaskRepo completionTaskRepo;

    @Inject
    MenuHierarchyRepo menuHierarchyRepo;

    @Inject
    RepoUtils repoUtils;

    @Inject
    com.e2eq.framework.model.persistent.morphia.EmailTemplateRepo emailTemplateRepo;

    @Inject
    com.e2eq.framework.model.persistent.morphia.UserProfileRepo userProfileRepo;

    @Inject
    QueryGatewayResource gateway;

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    private String realm;

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        SecurityContext.clear();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
        cleanUp();
        ruleContext.clear();
        ruleContext.ensureDefaultRules();
        SecurityContext.clear();
    }

    // ------------------------------------------------------- 1. update/delete by id

    @ParameterizedTest
    @EnumSource(Partition.class)
    void byIdWritesCannotReachForeignRows(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter, null, true);
        BookModel own = seedBook("own", testUtils.getTestDataDomain());
        BookModel foreign = seedBook("foreign", p.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);
        ObjectId fid = foreign.getId();

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "update", "updateActiveStatus(realm, id)",
                () -> bookRepo.updateActiveStatus(realm, fid.toHexString(), ActiveStatus.INACTIVE) > 0);
        probe(leaks, caller, "update", "updateActiveStatus(id)",
                () -> bookRepo.updateActiveStatus(fid, ActiveStatus.INACTIVE) > 0);
        probe(leaks, caller, "update", "updateActiveStatus(datastore, id)",
                () -> bookRepo.updateActiveStatus(dataStoreWrapper.getDataStore(realm), fid, ActiveStatus.INACTIVE) > 0);
        probe(leaks, caller, "update", "update(session, String id)",
                () -> inSession(s -> bookRepo.update(s, fid.toHexString(), Pair.of("title", "sgf-pwned-1"))) > 0);
        probe(leaks, caller, "update", "update(session, ObjectId id)",
                () -> inSession(s -> bookRepo.update(s, fid, Pair.of("title", "sgf-pwned-2"))) > 0);
        probe(leaks, caller, "update", "update(realm, id)",
                () -> bookRepo.update(realm, fid, Pair.of("title", "sgf-pwned-3")) > 0);
        probe(leaks, caller, "update", "updateManyByRefAndDomain(foreign ref+domain)",
                () -> bookRepo.updateManyByRefAndDomain(realm,
                        List.of(Pair.of(foreign.getRefName(), foreign.getDataDomain())),
                        Pair.of("title", "sgf-pwned-4")) > 0);
        probe(leaks, caller, "delete", "delete(session, entity)",
                () -> inSession(s -> {
                    try {
                        return bookRepo.delete(s, foreign);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }) > 0);

        BookModel stored = storedBook(fid);
        if (stored == null) {
            leaks.add("foreign row was deleted");
        } else {
            if (!foreign.getTitle().equals(stored.getTitle())) leaks.add("foreign title changed to " + stored.getTitle());
            if (stored.getActiveStatus() == ActiveStatus.INACTIVE) leaks.add("foreign activeStatus changed to INACTIVE");
        }
        assertEquals(List.of(), leaks, "rule " + p.ruleFilter + " did not gate these by-id writes");

        // Positive control: the caller's own row is still writable on every gated path.
        assertEquals(1L, (long) as(caller, "update",
                () -> bookRepo.updateActiveStatus(realm, own.getId().toHexString(), ActiveStatus.INACTIVE)));
        assertEquals(1L, (long) as(caller, "update",
                () -> inSession(s -> bookRepo.update(s, own.getId(), Pair.of("title", "sgf-own-2")))));
        assertEquals(1L, (long) as(caller, "update",
                () -> inSession(s -> bookRepo.update(s, own.getId().toHexString(), Pair.of("title", "sgf-own-2b")))));
        assertEquals(1L, (long) as(caller, "update", () -> {
            try {
                return bookRepo.updateManyByRefAndDomain(realm,
                        List.of(Pair.of(own.getRefName(), own.getDataDomain())), Pair.of("title", "sgf-own-3"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        assertEquals("sgf-own-3", storedBook(own.getId()).getTitle());
    }

    // ------------------------------------------------------- 3. export path

    @ParameterizedTest
    @EnumSource(Partition.class)
    void exportCannotIncludeForeignRows(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter, null, true);
        BookModel own = seedBook("own", testUtils.getTestDataDomain());
        BookModel foreign = seedBook("foreign", p.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        for (String query : List.of(p.userQuery(), "refName:^[" + own.getRefName() + "," + foreign.getRefName() + "]",
                p.userQuery() + "||refName:" + foreign.getRefName())) {
            QueryGatewayResource.ExportRequest req = new QueryGatewayResource.ExportRequest();
            req.rootType = BookModel.class.getName();
            req.realm = realm;
            req.query = query;
            req.columns = List.of("refName", "title");
            Response r = as(caller, "export", () -> gateway.export(req));
            assertEquals(200, r.getStatus(), "export failed: " + r.getEntity());
            QueryGatewayResource.ExportResponse body = (QueryGatewayResource.ExportResponse) r.getEntity();
            if (body.csvContent != null && body.csvContent.contains(foreign.getRefName())) {
                leaks.add("export(" + query + ") csv");
            }
            long expected = query.startsWith("refName:^[") ? 1 : 0;
            if (body.rowCount != expected) {
                leaks.add("export(" + query + ") rowCount=" + body.rowCount);
            }
        }
        assertEquals(List.of(), leaks, "rule " + p.ruleFilter + " did not scope these export paths");
    }

    // ------------------------------------------------------- 2/3. expand + aggregation

    @ParameterizedTest
    @EnumSource(Partition.class)
    void expandCannotHydrateOrReturnForeignRows(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter, null, true);
        DataDomain ownDd = testUtils.getTestDataDomain();
        DataDomain foreignDd = p.foreign(ownDd);
        SgfExpandTarget ownTarget = seedTarget("own-target", ownDd);
        SgfExpandTarget foreignTarget = seedTarget("foreign-target", foreignDd);
        SgfExpandRoot ownToOwn = seedRoot("own-to-own", ownDd, ownTarget);
        SgfExpandRoot ownToForeign = seedRoot("own-to-foreign", ownDd, foreignTarget);
        SgfExpandRoot foreignRoot = seedRoot("foreign-root", foreignDd, foreignTarget);
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        for (String query : List.of("expand(targetRef)", "expand(targetRef) && " + p.userQuery(),
                "expand(targetRef) && (" + p.userQuery() + "||refName:" + foreignRoot.getRefName() + ")")) {
            Response r = as(caller, "find", () -> gateway.find(expandRequest(query)));
            assertEquals(200, r.getStatus(), "gateway expand failed for " + query + ": " + r.getEntity());
            inspectExpandRows(leaks, "QueryGateway.find(" + query + ")", documentRows(r), foreignRoot, foreignTarget);

            List<Document> repoRows = as(caller, "list",
                    () -> expandRootRepo.getSecuredAggregationDocuments(realm, 0, 100, query, null, null));
            inspectExpandRows(leaks, "repo.getSecuredAggregationDocuments(" + query + ")", repoRows, foreignRoot, foreignTarget);
        }
        assertEquals(List.of(), leaks, "rule " + p.ruleFilter + " did not scope these expand paths");

        // Positive control: the own root still hydrates its own target.
        List<Document> rows = documentRows(as(caller, "find", () -> gateway.find(expandRequest("expand(targetRef)"))));
        Document ownHydrated = rows.stream().filter(d -> ownToOwn.getRefName().equals(d.getString("refName")))
                .findFirst().orElse(null);
        assertNotNull(ownHydrated, "own root missing from expand: " + rows);
        assertEquals("secret-own-target", ((Document) ownHydrated.get("targetRef")).getString("secret"),
                "own target must hydrate: " + ownHydrated);
        assertTrue(rows.stream().anyMatch(d -> ownToForeign.getRefName().equals(d.getString("refName"))),
                "own root pointing at a foreign target is still the caller's row");
    }

    /**
     * The gateway resolves a target's field policy by instantiating the target to learn its
     * functional area. When that fails (here: a private no-arg constructor) the exception was
     * swallowed and the target's field exclusions were dropped, so expand returned a field the
     * policy hides. It must refuse the query instead.
     */
    @Test
    void expandRefusesWhenTargetFieldPolicyCannotBeResolved() {
        String role = PREFIX + "sealed";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        addRuleIn(role, "SGFSEAL", "SEALED", null, List.of("secret"));
        DataDomain ownDd = testUtils.getTestDataDomain();
        SgfSealedTarget sealed = system(() -> {
            SgfSealedTarget t = SgfSealedTarget.create();
            t.setRefName(PREFIX + "sealed-" + new ObjectId().toHexString());
            t.setSecret("secret-sealed");
            t.setDataDomain(copy(ownDd));
            return dataStoreWrapper.getDataStore(realm).save(t);
        });
        SgfExpandRoot root = system(() -> {
            SgfExpandRoot r = new SgfExpandRoot();
            r.setRefName(PREFIX + "sealed-root-" + new ObjectId().toHexString());
            r.setDataDomain(copy(ownDd));
            EntityReference ref = new EntityReference();
            ref.setEntityId(sealed.getId());
            ref.setEntityRefName(sealed.getRefName());
            ref.setEntityDisplayName(sealed.getRefName());
            r.setSealedRef(ref);
            return dataStoreWrapper.getDataStore(realm).save(r);
        });
        PrincipalContext caller = principal(role);

        // The repo path resolves the target's policy from @FunctionalMapping and hides the field.
        List<Document> repoRows = as(caller, "list", () -> expandRootRepo.getSecuredAggregationDocuments(
                realm, 0, 100, "expand(sealedRef)", null, null));
        assertTrue(repoRows.stream().noneMatch(d -> String.valueOf(d.get("sealedRef")).contains("secret-sealed")),
                "repo expand returned a field the target policy excludes: " + repoRows);

        Response r = as(caller, "find", () -> gateway.find(expandRequest("expand(sealedRef)")));
        String outcome = r.getStatus() + " " + r.getEntity();
        if (r.getStatus() == 200) {
            boolean leaked = documentRows(r).stream()
                    .filter(d -> root.getRefName().equals(d.getString("refName")))
                    .anyMatch(d -> String.valueOf(d.get("sealedRef")).contains("secret-sealed"));
            assertTrue(!leaked, "gateway expand returned a field the target policy excludes: " + outcome);
            return;
        }
        assertEquals(403, r.getStatus(), "an unresolvable target policy must refuse with 403: " + outcome);
        assertTrue(outcome.contains("ExpandPolicyUnresolved"), outcome);
    }

    /**
     * A target row policy that cannot be lowered for the target model must refuse the query with a
     * typed 403 before any aggregation runs. The rule names a managed reference ({@code author})
     * that only the root has, so it lowers for the root and throws for the target. Before the fix
     * the gateway swallowed that exception and compiled the $lookup without the target's row policy
     * (the request then failed later for an unrelated encoding reason).
     */
    @Test
    void expandFailsClosedWhenTargetRowPolicyCannotBeLowered() {
        String role = PREFIX + "unresolvable-target";
        addRule(role, "dataDomain.ownerId:${ownerId} && author:!@@000000000000000000000000", null, true);
        DataDomain ownDd = testUtils.getTestDataDomain();
        SgfExpandTarget foreignTarget = seedTarget("foreign-target", Partition.OWNER.foreign(ownDd));
        seedRoot("own-to-foreign", ownDd, foreignTarget);
        PrincipalContext caller = principal(role);

        Response r;
        try {
            r = as(caller, "find", () -> gateway.find(expandRequest("expand(targetRef)")));
        } catch (RuntimeException e) {
            throw new AssertionError("expected a typed 403 refusal, got " + e, e);
        }
        String outcome = r.getStatus() + " " + r.getEntity();
        if (r.getStatus() == 200) {
            List<String> leaks = new ArrayList<>();
            inspectExpandRows(leaks, "QueryGateway.find(expand)", documentRows(r), null, foreignTarget);
            assertEquals(List.of(), leaks, "expand ran without the target's row policy: " + outcome);
        }
        assertEquals(403, r.getStatus(), "an unresolvable target policy must refuse the query: " + outcome);
        assertTrue(outcome.contains("ExpandPolicyUnresolved"), outcome);
    }

    // ------------------------------------------------------- 4. getFilters across rules

    /**
     * Two matched rules for one principal: an and+or rule and a later or-only rule. The or-only
     * rule's constraint (ownerId) must still apply. Before the fix the and/or lists were not cleared
     * after a rule with both, so the later rule's clause was OR-ed into the earlier one and dropped.
     */
    @Test
    void laterRuleConstraintIsNotDroppedAfterAnAndOrRule() {
        String role = PREFIX + "two-rules";
        addRule(role, "dataDomain.orgRefName:${orgRefName}", "dataDomain.tenantId:${pTenantId}", false, "a", 10);
        addRule(role, null, "dataDomain.ownerId:${ownerId}", false, "b", 20);
        BookModel own = seedBook("own", testUtils.getTestDataDomain());
        BookModel otherOwner = seedBook("other-owner", Partition.OWNER.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        List<String> refs = as(caller, "list", () -> bookRepo.getListByQuery(realm, 0, 100,
                "refName:^[" + own.getRefName() + "," + otherOwner.getRefName() + "]", null, null))
                .stream().map(BookModel::getRefName).toList();
        assertEquals(List.of(own.getRefName()), refs,
                "the or-only ownerId rule was dropped after an and+or rule: " + refs);
    }

    // ------------------------------------------------------- 4. projectGovernedFilter

    /**
     * {@code projectGovernedFilter} composes strings. A caller query that closes the parenthesis it
     * is wrapped in must not escape the conjunction with the governed clauses.
     */
    @Test
    void governedFilterProjectionCannotBeEscapedByTheCallerQuery() {
        String role = PREFIX + "governed";
        addRule(role, "dataDomain.tenantId:${pTenantId}", null, true);
        seedBook("own", testUtils.getTestDataDomain());
        BookModel foreign = seedBook("foreign", Partition.TENANT.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);
        String f = foreign.getRefName();

        List<String> leaks = new ArrayList<>();
        for (String injected : List.of(
                "refName:sgf-none) || (refName:" + f,
                "refName:" + f + ") || (refName:sgf-none",
                "refName:sgf-none) || refName:" + f + " && (refName:sgf-none",
                "refName:sgf-none) && (refName:sgf-none) || (refName:" + f,
                "refName:sgf-none || refName:" + f,
                // A stray ')' ends the lenient parse early, so the governed clause after it is
                // never read: these must be refused, not composed.
                "refName:" + f + ")",
                "refName:" + f + ") sgf-junk")) {
            governedLeak(leaks, caller, injected, foreign.getId());
        }
        assertEquals(List.of(), leaks, "caller query escaped the governed conjunction");
    }

    /**
     * Variables are substituted into the rule string as text, so a principal attribute that
     * carries query syntax must not rewrite the governed clause.
     */
    @Test
    void governedFilterProjectionCannotBeEscapedByAVariableValue() {
        String role = PREFIX + "governed-var";
        addRule(role, "dataDomain.ownerId:${ownerId}", null, true);
        BookModel foreign = seedBook("foreign", Partition.OWNER.foreign(testUtils.getTestDataDomain()));
        DataDomain crafted = copy(testUtils.getTestDataDomain());
        crafted.setOwnerId("sgf-x) || (refName:" + foreign.getRefName());
        PrincipalContext caller = new PrincipalContext.Builder()
                .withDataDomain(crafted)
                .withDefaultRealm(realm)
                .withUserId(role + "@test.local")
                .withScope("Authentication")
                .withRoles(new String[]{role}).build();

        List<String> leaks = new ArrayList<>();
        governedLeak(leaks, caller, null, foreign.getId());
        governedLeak(leaks, caller, "refName:" + foreign.getRefName(), foreign.getId());

        // A value that is a query keyword changes the clause's meaning: ownerId:null matches
        // rows with no owner.
        BookModel ownerless = seedBook("ownerless", Partition.OWNER.foreign(testUtils.getTestDataDomain()));
        dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("bookModel").updateOne(
                new Document("_id", ownerless.getId()), new Document("$unset", new Document("dataDomain.ownerId", "")));
        DataDomain nullWord = copy(testUtils.getTestDataDomain());
        nullWord.setOwnerId("null");
        PrincipalContext nullCaller = new PrincipalContext.Builder()
                .withDataDomain(nullWord)
                .withDefaultRealm(realm)
                .withUserId(role + "-null@test.local")
                .withScope("Authentication")
                .withRoles(new String[]{role}).build();
        governedLeak(leaks, nullCaller, null, ownerless.getId());
        assertEquals(List.of(), leaks, "a variable value escaped the governed clause");
    }

    /** A scalar value with a comma must not widen a rule's {@code ^[${v}]} list. */
    @Test
    void governedFilterProjectionCannotWidenAListThroughAScalarValue() {
        String role = PREFIX + "governed-list";
        addRule(role, "dataDomain.ownerId:^[${ownerId}]", null, true);
        BookModel foreign = seedBook("foreign", Partition.OWNER.foreign(testUtils.getTestDataDomain()));
        DataDomain crafted = copy(testUtils.getTestDataDomain());
        crafted.setOwnerId(testUtils.getTestDataDomain().getOwnerId() + "," + Partition.OWNER.foreignValue);
        PrincipalContext caller = new PrincipalContext.Builder()
                .withDataDomain(crafted)
                .withDefaultRealm(realm)
                .withUserId(role + "@test.local")
                .withScope("Authentication")
                .withRoles(new String[]{role}).build();
        List<String> leaks = new ArrayList<>();
        governedLeak(leaks, caller, null, foreign.getId());
        assertEquals(List.of(), leaks, "a comma in a scalar value widened the governed list");
    }

    private void governedLeak(List<String> leaks, PrincipalContext caller, String userQuery, ObjectId foreignId) {
        String governed;
        try {
            governed = as(caller, "list", () -> ruleContext.projectGovernedFilter(userQuery, caller, resource("list"))
                    .filter().orElse(null));
        } catch (SecurityException | IllegalArgumentException refused) {
            System.out.println("SGF governed refused " + userQuery + ": " + refused);
            return;
        }
        System.out.println("SGF governed " + userQuery + " -> " + governed);
        if (governed != null && matchCount(governed, foreignId) > 0) {
            leaks.add(userQuery + " -> " + governed);
        }
    }

    // ------------------------------------------------------- 5. system-only / LOW paths

    @Test
    void repoUtilsResolveAndHierarchyReadsHonourRules() {
        String role = PREFIX + "low";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        DataDomain ownDd = testUtils.getTestDataDomain();
        DataDomain foreignDd = Partition.TENANT.foreign(ownDd);
        BookModel foreign = seedBook("foreign", foreignDd);
        MenuHierarchyModel ownRoot = seedMenu("own-root", ownDd, null);
        MenuHierarchyModel foreignChild = seedMenu("foreign-child", foreignDd, ownRoot);
        MenuHierarchyModel foreignRoot = seedMenu("foreign-root", foreignDd, null);
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "view", "RepoUtils.resolve",
                () -> repoUtils.resolve(BookModel.class.getName(), foreign.getId()) != null);
        probe(leaks, caller, "list", "HierarchicalRepo.getTrees",
                () -> {
                    String json = String.valueOf(menuHierarchyRepo.getTrees().stream()
                            .map(t -> t.key + ":" + t.children.stream().map(c -> c.key).toList()).toList());
                    return json.contains(foreignRoot.getId().toHexString())
                            || json.contains(foreignChild.getId().toHexString());
                });
        probe(leaks, caller, "list", "HierarchicalRepo.getAllChildren",
                () -> menuHierarchyRepo.getAllChildren(ownRoot.getId()).stream()
                        .anyMatch(c -> foreignChild.getId().equals(c.getId())));
        assertEquals(List.of(), leaks, "these system/tree paths ignored the caller's rules");
    }

    @Test
    void emailTemplateDeleteCannotReachForeignTemplate() {
        String role = PREFIX + "email";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        com.e2eq.framework.model.persistent.email.EmailTemplate foreign = system(() -> {
            com.e2eq.framework.model.persistent.email.EmailTemplate t =
                    new com.e2eq.framework.model.persistent.email.EmailTemplate();
            t.setRefName(PREFIX + "email-" + new ObjectId().toHexString());
            t.setTemplateKey(t.getRefName());
            t.setSubjectTemplate("s");
            t.setDataDomain(copy(Partition.TENANT.foreign(testUtils.getTestDataDomain())));
            return dataStoreWrapper.getDataStore(realm).save(t);
        });
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "delete", "EmailTemplateRepo.deleteByRefName(foreign)",
                () -> emailTemplateRepo.deleteByRefName(realm, foreign.getRefName()));
        long remaining = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("email_templates")
                .countDocuments(new Document("_id", foreign.getId()));
        if (remaining == 0) {
            leaks.add("foreign template deleted");
        }
        assertEquals(List.of(), leaks, "email template delete ignored the caller's rules");
    }

    /**
     * The $lookup's row filter must include the rules of the target's own functional area, not
     * only the gateway's: here the target area scopes by owner and the root area only by tenant.
     */
    @Test
    void expandAppliesTheTargetAreasRowRules() {
        String role = PREFIX + "target-area";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        addRuleIn(role, "SGFSEAL", "SEALED", Partition.OWNER.ruleFilter, null);
        DataDomain ownDd = testUtils.getTestDataDomain();
        SgfSealedTarget otherOwners = system(() -> {
            SgfSealedTarget t = SgfSealedTarget.create();
            t.setRefName(PREFIX + "sealed-" + new ObjectId().toHexString());
            t.setSecret("secret-other-owner");
            t.setDataDomain(copy(Partition.OWNER.foreign(ownDd)));
            return dataStoreWrapper.getDataStore(realm).save(t);
        });
        SgfExpandRoot root = system(() -> {
            SgfExpandRoot r = new SgfExpandRoot();
            r.setRefName(PREFIX + "area-root-" + new ObjectId().toHexString());
            r.setDataDomain(copy(ownDd));
            EntityReference ref = new EntityReference();
            ref.setEntityId(otherOwners.getId());
            ref.setEntityRefName(otherOwners.getRefName());
            ref.setEntityDisplayName(otherOwners.getRefName());
            r.setSealedRef(ref);
            return dataStoreWrapper.getDataStore(realm).save(r);
        });
        PrincipalContext caller = principal(role);

        List<Document> repoRows = as(caller, "list", () -> expandRootRepo.getSecuredAggregationDocuments(
                realm, 0, 100, "expand(sealedRef)", null, null));
        assertTrue(repoRows.stream().noneMatch(d -> String.valueOf(d.get("sealedRef")).contains("secret-other-owner")),
                "repo expand hydrated a target outside the target area's rules: " + repoRows);

        Response r = as(caller, "find", () -> gateway.find(expandRequest("expand(sealedRef)")));
        String outcome = r.getStatus() + " " + r.getEntity();
        assertEquals(200, r.getStatus(), outcome);
        boolean leaked = documentRows(r).stream()
                .filter(d -> root.getRefName().equals(d.getString("refName")))
                .anyMatch(d -> String.valueOf(d.get("sealedRef")).contains("secret-other-owner"));
        assertTrue(!leaked, "gateway expand hydrated a target outside the target area's rules: " + outcome);
    }

    @Test
    void legacyCompleteTaskCannotCompleteForeignTask() {
        String role = PREFIX + "task";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        CompletionTask foreign = seedTask("foreign", Partition.TENANT.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "update", "CompletionTaskRepo.completeTask(foreign)",
                () -> completionTaskRepo.completeTask(realm, foreign.getId().toHexString(),
                        CompletionTask.Status.COMPLETED, "sgf-pwned").isPresent());
        CompletionTask stored = system(() -> completionTaskRepo.findById(foreign.getId(), realm, true).orElse(null));
        if (stored != null && stored.getStatus() == CompletionTask.Status.COMPLETED) {
            leaks.add("foreign task status is COMPLETED");
        }
        assertEquals(List.of(), leaks, "legacy completion ignored the caller's rules");
    }

    @Test
    void createTaskCannotAttachToAForeignGroup() {
        String role = PREFIX + "group";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        DataDomain foreignDd = Partition.TENANT.foreign(testUtils.getTestDataDomain());
        com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup group = system(() -> {
            com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup g =
                    new com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup();
            g.setRefName(PREFIX + "foreign-group-" + new ObjectId().toHexString());
            g.setDisplayName(PREFIX + "foreign-group");
            g.setDataDomain(copy(foreignDd));
            return dataStoreWrapper.getDataStore(realm).save(g);
        });
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "save", "CompletionTaskRepo.createTask(foreign group)", () -> {
            CompletionTask t = new CompletionTask();
            t.setRefName(PREFIX + "attach-" + new ObjectId().toHexString());
            t.setDisplayName(PREFIX + "attach");
            CompletionTask saved = completionTaskRepo.createTask(realm, t, group.getId().toHexString());
            return saved.getGroup() != null;
        });
        long attached = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("completionTask")
                .countDocuments(new Document("refName", new Document("$regex", "^" + PREFIX + "attach-"))
                        .append("group", new Document("$exists", true)));
        if (attached > 0) {
            leaks.add("a task referencing the foreign group was persisted");
        }
        var stored = system(() -> dataStoreWrapper.getDataStore(realm).find(
                com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup.class)
                .filter(dev.morphia.query.filters.Filters.eq("_id", group.getId())).first());
        if (stored != null && stored.getStatus() == com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup.Status.RUNNING) {
            leaks.add("foreign group status set to RUNNING");
        }
        assertEquals(List.of(), leaks, "a task was attached to, or changed, a group outside the caller's scope");
    }

    /**
     * Task creation is broadly allowed but group reads are tenant-scoped: the group lookup must be
     * evaluated under the group's own rules, not the task-creation context.
     */
    @Test
    void createTaskChecksTheGroupUnderTheGroupsOwnRules() {
        String role = PREFIX + "group-area";
        addRuleIn(role, "TASK", "COMPLETION_TASK", null, null);
        addRuleIn(role, "TASK", "COMPLETION_TASK_GROUP", Partition.TENANT.ruleFilter, null);
        DataDomain foreignDd = Partition.TENANT.foreign(testUtils.getTestDataDomain());
        com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup group = system(() -> {
            com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup g =
                    new com.e2eq.framework.model.persistent.tasks.CompletionTaskGroup();
            g.setRefName(PREFIX + "foreign-group-" + new ObjectId().toHexString());
            g.setDisplayName(PREFIX + "foreign-group");
            g.setDataDomain(copy(foreignDd));
            return dataStoreWrapper.getDataStore(realm).save(g);
        });
        PrincipalContext caller = principal(role);
        ResourceContext taskCreate = new ResourceContext.Builder()
                .withArea("TASK").withFunctionalDomain("COMPLETION_TASK").withAction("save").build();

        boolean attached;
        ruleContext.clearCacheForRealm(realm);
        com.e2eq.framework.security.runtime.RuleContext.clearRequestCache();
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(caller, taskCreate)) {
            CompletionTask t = new CompletionTask();
            t.setRefName(PREFIX + "attach-area-" + new ObjectId().toHexString());
            t.setDisplayName(PREFIX + "attach");
            attached = completionTaskRepo.createTask(realm, t, group.getId().toHexString()).getGroup() != null;
        } catch (RuntimeException refused) {
            attached = false;
        }
        assertTrue(!attached, "createTask attached a task to a group the caller cannot read");
    }

    @Test
    void userProfileStatusUpdateCannotReachForeignProfile() {
        String role = PREFIX + "profile";
        addRule(role, Partition.TENANT.ruleFilter, null, true);
        DataDomain foreignDd = Partition.TENANT.foreign(testUtils.getTestDataDomain());
        String userId = PREFIX + "foreign-user-" + new ObjectId().toHexString() + "@test.local";
        system(() -> {
            Document doc = new Document("_t", "UserProfile")
                    .append("refName", PREFIX + "profile-" + new ObjectId().toHexString())
                    .append("userId", userId).append("email", userId).append("status", "ACTIVE")
                    .append("dataDomain", new Document("orgRefName", foreignDd.getOrgRefName())
                            .append("accountNum", foreignDd.getAccountNum())
                            .append("tenantId", foreignDd.getTenantId())
                            .append("dataSegment", foreignDd.getDataSegment())
                            .append("ownerId", foreignDd.getOwnerId()));
            dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("userProfile").insertOne(doc);
            return null;
        });
        PrincipalContext caller = principal(role);

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "update", "UserProfileRepo.updateStatus(foreign)",
                () -> userProfileRepo.updateStatus(realm, userId,
                        com.e2eq.framework.model.security.UserProfile.Status.DEACTIVATED).isPresent());
        Document stored = dataStoreWrapper.getDataStore(realm).getDatabase().getCollection("userProfile")
                .find(new Document("userId", userId)).first();
        if (stored != null && !"ACTIVE".equals(stored.getString("status"))) {
            leaks.add("foreign profile status changed to " + stored.getString("status"));
        }
        assertEquals(List.of(), leaks, "userProfile status update ignored the caller's rules");
    }

    // ------------------------------------------------------------------ helpers

    private void inspectExpandRows(List<String> leaks, String path, List<Document> rows,
                                   SgfExpandRoot foreignRoot, SgfExpandTarget foreignTarget) {
        for (Document row : rows) {
            if (foreignRoot != null && foreignRoot.getRefName().equals(row.getString("refName"))) {
                leaks.add(path + " returned foreign root");
            }
            Object hydrated = row.get("targetRef");
            if (hydrated instanceof Document d && foreignTarget.getSecret().equals(d.getString("secret"))) {
                leaks.add(path + " hydrated foreign target into " + row.getString("refName"));
            }
        }
    }

    private long matchCount(String governed, ObjectId id) {
        return system(() -> {
            var filter = MorphiaUtils.convertToFilter(governed, BookModel.class);
            return dataStoreWrapper.getDataStore(realm).find(BookModel.class)
                    .filter(dev.morphia.query.filters.Filters.and(filter, dev.morphia.query.filters.Filters.eq("_id", id)))
                    .count();
        });
    }

    private long inSession(java.util.function.Function<MorphiaSession, Long> body) {
        try (MorphiaSession session = dataStoreWrapper.getDataStore(realm).startSession()) {
            session.startTransaction();
            long r = body.apply(session);
            session.commitTransaction();
            return r;
        }
    }

    private void probe(List<String> leaks, PrincipalContext caller, String action, String path, Callable<Boolean> leaked) {
        try {
            Boolean result = as(caller, action, () -> {
                try {
                    return leaked.call();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            if (Boolean.TRUE.equals(result)) {
                leaks.add(path);
            }
        } catch (RuntimeException e) {
            // Refusing (an exception) is not a leak; the stored-row checks catch partial writes.
            System.out.println("SGF probe " + path + " refused: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> documentRows(Response r) {
        assertEquals(200, r.getStatus(), "gateway call failed: " + r.getEntity());
        Object entity = r.getEntity();
        try {
            List<?> rows = (List<?>) entity.getClass().getMethod("getRows").invoke(entity);
            List<Document> out = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Document d) out.add(d);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private QueryGatewayResource.FindRequest expandRequest(String query) {
        QueryGatewayResource.FindRequest req = new QueryGatewayResource.FindRequest();
        req.rootType = SgfExpandRoot.class.getName();
        req.realm = realm;
        req.query = query;
        QueryGatewayResource.Page page = new QueryGatewayResource.Page();
        page.limit = 100;
        page.skip = 0;
        req.page = page;
        return req;
    }

    private <R> R as(PrincipalContext caller, String action, java.util.function.Supplier<R> body) {
        ruleContext.clearCacheForRealm(realm);
        com.e2eq.framework.security.runtime.RuleContext.clearRequestCache();
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(caller, resource(action))) {
            return body.get();
        }
    }

    private <R> R system(java.util.function.Supplier<R> body) {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("save"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            return body.get();
        }
    }

    private BookModel seedBook(String suffix, DataDomain dd) {
        return system(() -> {
            BookModel b = new BookModel();
            b.setTitle(PREFIX + suffix);
            b.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            b.setDataDomain(copy(dd));
            b.setActiveStatus(ActiveStatus.ACTIVE);
            return bookRepo.save(realm, b);
        });
    }

    private SgfExpandTarget seedTarget(String suffix, DataDomain dd) {
        return system(() -> {
            SgfExpandTarget t = new SgfExpandTarget();
            t.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            t.setSecret("secret-" + suffix);
            t.setDataDomain(copy(dd));
            return dataStoreWrapper.getDataStore(realm).save(t);
        });
    }

    private SgfExpandRoot seedRoot(String suffix, DataDomain dd, SgfExpandTarget target) {
        return system(() -> {
            SgfExpandRoot r = new SgfExpandRoot();
            r.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            r.setLabel(suffix);
            r.setDataDomain(copy(dd));
            EntityReference ref = new EntityReference();
            ref.setEntityId(target.getId());
            ref.setEntityRefName(target.getRefName());
            ref.setEntityDisplayName(target.getRefName());
            ref.setEntityType(SgfExpandTarget.class.getSimpleName());
            r.setTargetRef(ref);
            return dataStoreWrapper.getDataStore(realm).save(r);
        });
    }

    private MenuHierarchyModel seedMenu(String suffix, DataDomain dd, MenuHierarchyModel parent) {
        return system(() -> {
            var ds = dataStoreWrapper.getDataStore(realm);
            MenuHierarchyModel m = new MenuHierarchyModel();
            m.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            m.setDisplayName(PREFIX + suffix);
            m.setDataDomain(copy(dd));
            if (parent != null) {
                EntityReference ref = new EntityReference();
                ref.setEntityId(parent.getId());
                ref.setEntityRefName(parent.getRefName());
                ref.setEntityDisplayName(parent.getRefName());
                m.setParent(ref);
            }
            MenuHierarchyModel saved = ds.save(m);
            if (parent != null) {
                MenuHierarchyModel p = ds.find(MenuHierarchyModel.class)
                        .filter(dev.morphia.query.filters.Filters.eq("_id", parent.getId())).first();
                List<ObjectId> d = p.getDescendants() == null ? new ArrayList<>() : new ArrayList<>(p.getDescendants());
                d.add(saved.getId());
                p.setDescendants(d);
                ds.save(p);
            }
            return saved;
        });
    }

    private CompletionTask seedTask(String suffix, DataDomain dd) {
        return system(() -> {
            CompletionTask t = new CompletionTask();
            t.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            t.setDisplayName(PREFIX + suffix);
            t.setStatus(CompletionTask.Status.PENDING);
            t.setDataDomain(copy(dd));
            return dataStoreWrapper.getDataStore(realm).save(t);
        });
    }

    private BookModel storedBook(ObjectId id) {
        return system(() -> bookRepo.findById(id, realm, true).orElse(null));
    }

    private void cleanUp() {
        var db = dataStoreWrapper.getDataStore(testUtils.getTestRealm()).getDatabase();
        Document byPrefix = new Document("refName", new Document("$regex", "^" + PREFIX));
        for (String c : List.of("bookModel", "sgfExpandRoot", "sgfExpandTarget", "sgfSealedTarget", "menuHierarchyModel",
                "completionTask", "completionTaskGroup", "userProfile", "email_templates")) {
            db.getCollection(c).deleteMany(byPrefix);
        }
    }

    private void addRule(String role, String andFilter, String orFilter, boolean finalRule) {
        addRule(role, andFilter, orFilter, finalRule, "rule", 10);
    }

    private void addRule(String role, String andFilter, String orFilter, boolean finalRule, String suffix, int priority) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(role).withArea(AREA).withFunctionalDomain(DOMAIN).withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        Rule.Builder rule = new Rule.Builder()
                .withName(role + "-" + suffix)
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withPriority(priority)
                .withFinalRule(finalRule);
        if (andFilter != null) rule.withAndFilterString(andFilter);
        if (orFilter != null) rule.withOrFilterString(orFilter);
        ruleContext.addRule(header, rule.build());
    }

    private void addRuleIn(String role, String area, String domain, String andFilter, List<String> excludedFields) {
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity(role).withArea(area).withFunctionalDomain(domain).withAction("*").build();
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withRealm("*").withOrgRefName("*").withAccountNumber("*").withTenantId("*")
                .withOwnerId("*").withDataSegment("*").withResourceId("*").build();
        Rule.Builder rule = new Rule.Builder()
                .withName(role + "-" + area.toLowerCase())
                .withSecurityURI(new SecurityURI(header, body))
                .withEffect(RuleEffect.ALLOW)
                .withPriority(10)
                .withFinalRule(true);
        if (andFilter != null) rule.withAndFilterString(andFilter);
        if (excludedFields != null) rule.withExcludedFields(excludedFields);
        ruleContext.addRule(header, rule.build());
    }

    private PrincipalContext principal(String role) {
        return testUtils.getTestPrincipalContext(role + "@test.local", new String[]{role});
    }

    private ResourceContext resource(String action) {
        return new ResourceContext.Builder().withArea(AREA).withFunctionalDomain(DOMAIN).withAction(action).build();
    }

    private static DataDomain copy(DataDomain own) {
        return new DataDomain(own.getOrgRefName(), own.getAccountNum(), own.getTenantId(),
                own.getDataSegment(), own.getOwnerId());
    }
}
