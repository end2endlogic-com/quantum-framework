package com.e2eq.framework.security;

import com.e2eq.framework.api.query.QueryGatewayResource;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
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
import com.e2eq.framework.persistent.TestBookRepo;
import com.e2eq.framework.test.BookModel;
import com.e2eq.framework.model.persistent.base.CloseableIterator;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security regression (replica-set Mongo): a caller- or user-supplied filter on the same field as a
 * security rule filter must never replace or weaken that rule filter.
 *
 * <p>Morphia merges the top-level filters of {@code Query.filter(f1, f2, ...)} into one query
 * document keyed by field; for the same field the last one wins. Rule filters and caller filters
 * must therefore be combined as separate {@code $and} clauses. Each test seeds one row in the
 * caller's own data domain and one row that differs from it only in the partition field under
 * test, installs a rule that partitions on that field, and then drives every repository and
 * QueryGateway path with a caller filter asking for the foreign value. Any path that returns,
 * counts, updates or deletes the foreign row is a leak; the assertion message lists them all.</p>
 */
@QuarkusTest
public class RuleFilterSameFieldOverrideTest extends BaseRepoTest {

    private static final String AREA = "TEST";
    private static final String DOMAIN = "TEST";
    private static final String PREFIX = "rfso-";

    /** A partition field, the rule that scopes it to the caller, and a foreign value. */
    enum Partition {
        TENANT("dataDomain.tenantId", "dataDomain.tenantId:${pTenantId}", "rfso-other-tenant"),
        OWNER("dataDomain.ownerId", "dataDomain.ownerId:${ownerId}", "rfso-other-owner"),
        ORG("dataDomain.orgRefName", "dataDomain.orgRefName:${orgRefName}", "rfso-other-org"),
        SEGMENT("dataDomain.dataSegment", "dataDomain.dataSegment:^[${rfsoSegments}]", 7);

        final String field;
        final String ruleFilter;
        final Object foreignValue;

        Partition(String field, String ruleFilter, Object foreignValue) {
            this.field = field;
            this.ruleFilter = ruleFilter;
            this.foreignValue = foreignValue;
        }

        String userQuery() {
            return foreignValue instanceof Integer
                    ? field + ":#" + foreignValue
                    : field + ":" + foreignValue;
        }

        DataDomain foreign(DataDomain own) {
            DataDomain dd = new DataDomain(own.getOrgRefName(), own.getAccountNum(), own.getTenantId(),
                    own.getDataSegment(), own.getOwnerId());
            switch (this) {
                case TENANT -> dd.setTenantId((String) foreignValue);
                case OWNER -> dd.setOwnerId((String) foreignValue);
                case ORG -> dd.setOrgRefName((String) foreignValue);
                case SEGMENT -> dd.setDataSegment((Integer) foreignValue);
            }
            return dd;
        }
    }

    /**
     * Publishes ${rfsoSegments} as the caller's own (integer) dataSegment. A literal numeric rule
     * filter such as {@code dataSegment:#0} lowers to a String value on the rule path and so
     * cannot scope an int field; an access-list resolver carries the typed value instead.
     */
    @jakarta.enterprise.context.ApplicationScoped
    public static class SegmentResolver implements com.e2eq.framework.security.runtime.AccessListResolver {
        @Override
        public String key() { return "rfsoSegments"; }

        @Override
        public boolean supports(PrincipalContext pctx, ResourceContext rctx,
                                Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> modelClass) {
            return pctx != null && pctx.getRoles() != null
                    && java.util.Arrays.stream(pctx.getRoles()).anyMatch(r -> r != null && r.startsWith(PREFIX));
        }

        @Override
        public java.util.Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx,
                                               Class<? extends com.e2eq.framework.model.persistent.base.UnversionedBaseModel> modelClass) {
            return List.of(pctx.getDataDomain().getDataSegment());
        }
    }

    @Inject
    TestBookRepo bookRepo;

    @Inject
    QueryGatewayResource gateway;

    @Inject
    MorphiaDataStoreWrapper dataStoreWrapper;

    private String realm;

    @BeforeEach
    void setUp() {
        realm = testUtils.getTestRealm();
        SecurityContext.clear();
        deleteTestBooks();
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
        deleteTestBooks();
        ruleContext.clear();
        ruleContext.ensureDefaultRules();
        SecurityContext.clear();
    }

    // ------------------------------------------------------------------ reads

    @ParameterizedTest
    @EnumSource(Partition.class)
    void callerFilterOnRuleFieldCannotReadForeignRows(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter);
        BookModel own = seed("own", testUtils.getTestDataDomain());
        BookModel foreign = seed("foreign", p.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        // Precondition: the rule alone scopes the caller to its own row.
        assertEquals(Set.of(own.getRefName()), asRefs(as(caller, "list",
                () -> bookRepo.getListByQuery(realm, 0, 100, "refName:^[" + own.getRefName() + "," + foreign.getRefName() + "]", null, null))),
                "precondition: rule " + p.ruleFilter + " must scope the caller to its own row");

        List<String> leaks = new ArrayList<>();
        Filter callerEq = Filters.eq(p.field, p.foreignValue);
        Filter callerIn = Filters.in(p.field, List.of(p.foreignValue));
        String query = p.userQuery();

        check(leaks, "getList(filters=[eq])", foreign, as(caller, "list",
                () -> bookRepo.getList(realm, 0, 100, new ArrayList<>(List.of(callerEq)), null)));
        check(leaks, "getList(filters=[in])", foreign, as(caller, "list",
                () -> bookRepo.getList(realm, 0, 100, new ArrayList<>(List.of(callerIn)), null)));
        check(leaks, "getListByQuery", foreign, as(caller, "list",
                () -> bookRepo.getListByQuery(realm, 0, 100, query, null, null)));
        check(leaks, "getStreamByQuery", foreign, as(caller, "list", () -> {
            List<BookModel> out = new ArrayList<>();
            try (CloseableIterator<BookModel> it = bookRepo.getStreamByQuery(
                    dataStoreWrapper.getDataStore(realm), 0, 100, query, null, null)) {
                while (it.hasNext()) {
                    BookModel b = it.next();
                    if (b != null) out.add(b);
                }
            }
            return out;
        }));
        List<EntityReference> refs = as(caller, "list",
                () -> bookRepo.getEntityReferenceListByQuery(realm, 0, 100, query, null));
        if (refs.stream().anyMatch(r -> foreign.getRefName().equals(r.getEntityRefName()))) {
            leaks.add("getEntityReferenceListByQuery");
        }
        long count = as(caller, "list", () -> bookRepo.getCount(realm, query));
        if (count > 0) {
            leaks.add("getCount=" + count);
        }

        // QueryGateway (BIAPI) find + count
        Response find = as(caller, "find", () -> gateway.find(findRequest(query)));
        check(leaks, "QueryGateway.find", foreign, gatewayRows(find));
        Response countResp = as(caller, "count", () -> gateway.count(countRequest(query)));
        long gwCount = ((QueryGatewayResource.CountResponse) countResp.getEntity()).count;
        if (gwCount > 0) {
            leaks.add("QueryGateway.count=" + gwCount);
        }

        assertEquals(List.of(), leaks, "caller filter " + query + " overrode rule filter " + p.ruleFilter
                + " on these read paths");
    }

    // ------------------------------------------------------------------ writes

    @ParameterizedTest
    @EnumSource(Partition.class)
    void callerFilterOnRuleFieldCannotUpdateForeignRows(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter);
        seed("own", testUtils.getTestDataDomain());
        BookModel foreign = seed("foreign", p.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        long modified = as(caller, "update", () -> {
            try {
                return bookRepo.updateManyByQuery(realm, p.userQuery(), Pair.of("title", "rfso-pwned"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        String stored = storedTitle(foreign.getId());
        assertEquals(0L, modified, "updateManyByQuery(" + p.userQuery() + ") modified rows outside rule " + p.ruleFilter);
        assertEquals(foreign.getTitle(), stored, "foreign row must be untouched");
    }

    @ParameterizedTest
    @EnumSource(Partition.class)
    void callerFilterOnRuleFieldCannotDeleteForeignRowsThroughGateway(Partition p) {
        String role = PREFIX + p.name().toLowerCase();
        addRule(role, p.ruleFilter);
        seed("own", testUtils.getTestDataDomain());
        BookModel foreign = seed("foreign", p.foreign(testUtils.getTestDataDomain()));
        PrincipalContext caller = principal(role);

        QueryGatewayResource.DeleteManyRequest req = new QueryGatewayResource.DeleteManyRequest();
        req.rootType = BookModel.class.getName();
        req.realm = realm;
        req.query = p.userQuery();
        as(caller, "deleteMany", () -> gateway.deleteMany(req));

        assertTrue(storedTitle(foreign.getId()) != null,
                "QueryGateway.deleteMany(" + p.userQuery() + ") deleted a row outside rule " + p.ruleFilter);
    }

    // ------------------------------------------------------- fail-closed _id $in []

    @Test
    void failClosedMatchNothingSurvivesIdFilters() {
        String role = PREFIX + "failclosed";
        // A rule whose filter cannot be resolved contributes the match-nothing filter _id $in [].
        addRule(role, "title:^[${rfsoNoSuchVariable}]");
        BookModel own = seed("own", testUtils.getTestDataDomain());
        PrincipalContext caller = principal(role);
        ObjectId id = own.getId();

        List<String> leaks = new ArrayList<>();
        probe(leaks, caller, "view", "findById", () -> bookRepo.findById(id, realm).isPresent());
        probe(leaks, caller, "view", "findByRefName", () -> bookRepo.findByRefName(own.getRefName(), realm).isPresent());
        probe(leaks, caller, "list", "getListFromIds", () -> !bookRepo.getListFromIds(realm, List.of(id)).isEmpty());
        probe(leaks, caller, "list", "getListFromRefNames",
                () -> !bookRepo.getListFromRefNames(realm, List.of(own.getRefName())).isEmpty());
        probe(leaks, caller, "list", "getList(filters=[_id $in])",
                () -> !bookRepo.getList(realm, 0, 10, new ArrayList<>(List.of(Filters.in("_id", List.of(id)))), null).isEmpty());
        probe(leaks, caller, "list", "getList(filters=[_id eq])",
                () -> !bookRepo.getList(realm, 0, 10, new ArrayList<>(List.of(Filters.eq("_id", id))), null).isEmpty());
        probe(leaks, caller, "list", "getCount", () -> bookRepo.getCount(realm, "refName:" + own.getRefName()) > 0);
        probe(leaks, caller, "find", "QueryGateway.find(id)",
                () -> !gatewayRows(gateway.find(findRequest("id:" + id.toHexString()))).isEmpty());
        probe(leaks, caller, "update", "updateManyByIds",
                () -> bookRepo.updateManyByIds(dataStoreWrapper.getDataStore(realm), List.of(id), Pair.of("title", "rfso-pwned")) > 0);
        probe(leaks, caller, "update", "update(id)", () -> bookRepo.update(realm, id, Pair.of("title", "rfso-pwned2")) > 0);
        probe(leaks, caller, "delete", "QueryGateway.delete(id)", () -> {
            QueryGatewayResource.DeleteRequest del = new QueryGatewayResource.DeleteRequest();
            del.rootType = BookModel.class.getName();
            del.realm = realm;
            del.id = id.toHexString();
            Response r = gateway.delete(del);
            if (r.getStatus() != 200 && r.getStatus() != 404) {
                throw new IllegalStateException("status " + r.getStatus() + " " + r.getEntity());
            }
            return r.getStatus() == 200;
        });
        probe(leaks, caller, "delete", "delete(id)", () -> bookRepo.delete(realm, id) > 0);
        // The query document Morphia builds depends on the order of the merged filters, and that
        // order follows the hash of each filter's toString, which includes the id. Probe many ids.
        int byIdLeaks = 0;
        int byIdErrors = 0;
        for (int i = 0; i < 40; i++) {
            ObjectId probeId = seed("probe-" + i, testUtils.getTestDataDomain()).getId();
            try {
                if (as(caller, "view", () -> bookRepo.findById(probeId, realm)).isPresent()) byIdLeaks++;
            } catch (RuntimeException e) {
                byIdErrors++;
            }
        }
        if (byIdLeaks > 0 || byIdErrors > 0) {
            leaks.add("findById over 40 ids: " + byIdLeaks + " returned the row, " + byIdErrors + " errored");
        }
        if (!"rfso-own".equals(storedTitle(id))) {
            leaks.add("stored row was modified or deleted (title=" + storedTitle(id) + ")");
        }

        assertEquals(List.of(), leaks, "fail-closed _id $in [] was merged away on these paths");
    }

    // ------------------------------------------------------------------ helpers

    /** Runs one path; records it when it leaks, and records (rather than aborts on) an error. */
    private void probe(List<String> leaks, PrincipalContext caller, String action, String path,
                       java.util.concurrent.Callable<Boolean> leaked) {
        try {
            Boolean result = as(caller, action, () -> {
                try {
                    return leaked.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            if (Boolean.TRUE.equals(result)) {
                leaks.add(path);
            }
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            String msg = String.valueOf(root.getMessage());
            leaks.add(path + " ERROR " + root.getClass().getSimpleName() + ": "
                    + msg.substring(0, Math.min(120, msg.length())));
        }
    }

    private void check(List<String> leaks, String path, BookModel foreign, List<BookModel> rows) {
        if (rows.stream().anyMatch(b -> foreign.getRefName().equals(b.getRefName()))) {
            leaks.add(path);
        }
    }

    private static Set<String> asRefs(List<BookModel> rows) {
        return rows.stream().map(BookModel::getRefName).collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private List<BookModel> gatewayRows(Response r) {
        assertEquals(200, r.getStatus(), "gateway call failed: " + r.getEntity());
        Object entity = r.getEntity();
        try {
            List<?> rows = (List<?>) entity.getClass().getMethod("getRows").invoke(entity);
            List<BookModel> out = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof BookModel b) out.add(b);
            }
            return out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private QueryGatewayResource.FindRequest findRequest(String query) {
        QueryGatewayResource.FindRequest req = new QueryGatewayResource.FindRequest();
        req.rootType = BookModel.class.getName();
        req.realm = realm;
        req.query = query;
        QueryGatewayResource.Page page = new QueryGatewayResource.Page();
        page.limit = 100;
        page.skip = 0;
        req.page = page;
        return req;
    }

    private QueryGatewayResource.CountRequest countRequest(String query) {
        QueryGatewayResource.CountRequest req = new QueryGatewayResource.CountRequest();
        req.rootType = BookModel.class.getName();
        req.realm = realm;
        req.query = query;
        return req;
    }

    private <R> R as(PrincipalContext caller, String action, java.util.function.Supplier<R> body) {
        ruleContext.clearCacheForRealm(realm);
        com.e2eq.framework.security.runtime.RuleContext.clearRequestCache();
        try (SecurityCallScope.Scope ignored = SecurityCallScope.open(caller, resource(action))) {
            return body.get();
        }
    }

    private BookModel seed(String suffix, DataDomain dd) {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("save"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            BookModel b = new BookModel();
            b.setTitle(PREFIX + suffix);
            b.setRefName(PREFIX + suffix + "-" + new ObjectId().toHexString());
            b.setDataDomain(dd);
            return bookRepo.save(realm, b);
        }
    }

    private String storedTitle(ObjectId id) {
        PrincipalContext system = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        try (SecurityCallScope.Scope scope = SecurityCallScope.open(system, resource("view"));
             SecurityCallScope.Scope ignore = SecurityCallScope.openIgnoringRules()) {
            return bookRepo.findById(id, realm, true).map(BookModel::getTitle).orElse(null);
        }
    }

    private void deleteTestBooks() {
        dataStoreWrapper.getDataStore(testUtils.getTestRealm()).getDatabase()
                .getCollection("bookModel")
                .deleteMany(new org.bson.Document("refName", new org.bson.Document("$regex", "^" + PREFIX)));
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
}
