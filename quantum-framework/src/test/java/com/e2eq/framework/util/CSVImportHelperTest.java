package com.e2eq.framework.util;

import com.e2eq.framework.model.persistent.InvalidStateTransitionException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import dev.morphia.Datastore;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.apache.commons.lang3.tuple.Pair;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CSVImportHelperTest {

    static class TestItem extends UnversionedBaseModel {
        public TestItem() { }
        public TestItem(String ref, String display) { this.refName = ref; this.displayName = display; }
        @Override
        public String bmFunctionalArea() { return "Test"; }
        @Override
        public String bmFunctionalDomain() { return "Test"; }
    }

    static class FakeRepo implements BaseMorphiaRepo<TestItem> {
        private final Map<String, TestItem> store = new HashMap<>();
        @Override public String getDatabaseName() { return "test"; }
        @Override public Class<TestItem> getPersistentClass() { return TestItem.class; }
        @Override public List<TestItem> getListFromRefNames(List<String> refNames) {
            List<TestItem> out = new ArrayList<>();
            for (String rn : refNames) {
                if (store.containsKey(rn)) out.add(store.get(rn));
            }
            return out;
        }
        @Override public List<TestItem> save(List<TestItem> entities) { for (TestItem e : entities) { if (e.getRefName()==null) throw new RuntimeException("refName null"); store.put(e.getRefName(), e);} return entities; }
        // Unused methods throw
        @Override public TestItem save(TestItem value) { throw new UnsupportedOperationException(); }
        @Override public TestItem save(String realm, TestItem value) { throw new UnsupportedOperationException(); }
        @Override public TestItem save(dev.morphia.Datastore datastore, TestItem value) { throw new UnsupportedOperationException(); }
        public TestItem save(dev.morphia.transactions.MorphiaSession session, TestItem value) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> save(dev.morphia.Datastore datastore, List<TestItem> entities) { throw new UnsupportedOperationException(); }
        public List<TestItem> save(dev.morphia.transactions.MorphiaSession datastore, List<TestItem> entities) { throw new UnsupportedOperationException(); }
        @Override public com.e2eq.framework.rest.models.Collection<TestItem> fillUIActions(com.e2eq.framework.rest.models.Collection<TestItem> collection) { throw new UnsupportedOperationException(); }
        @Override public TestItem fillUIActions(TestItem model) { throw new UnsupportedOperationException(); }
        @Override public void ensureIndexes(String realmId, String collection) { }
        @Override public java.util.Optional<TestItem> findById(String id) { throw new UnsupportedOperationException(); }

       @Override
       public Optional<TestItem> findById (@NotNull String id, boolean ignoreRules) {return Optional.empty();}

       @Override public java.util.Optional<TestItem> findById(String id, String realmId) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findById(ObjectId id) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findById(ObjectId id, String realmId) { throw new UnsupportedOperationException(); }
       @Override public  java.util.Optional<TestItem> findById (@NotNull ObjectId id, String realmId, boolean ignoreRules) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findById(dev.morphia.Datastore s, ObjectId id) { throw new UnsupportedOperationException(); }
       @Override public java.util.Optional<TestItem> findById(dev.morphia.Datastore s, ObjectId id, boolean ignoreRules) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findByRefName(String refId) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findByRefName(String refName, String realmId) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<TestItem> findByRefName(dev.morphia.Datastore datastore, String refName) { throw new UnsupportedOperationException(); }
       @Override public java.util.Optional<TestItem> findByRefName(dev.morphia.Datastore datastore, String refName, boolean ignoreRules) { throw new UnsupportedOperationException(); }
        @Override public com.fasterxml.jackson.module.jsonSchema.jakarta.JsonSchema getSchema() { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getAllList() { return new ArrayList<>(store.values()); }
        @Override public List<TestItem> getAllList(String realmId) { return getAllList(); }
        @Override public List<TestItem> getAllList(dev.morphia.Datastore datastore) { return getAllList(); }
        @Override public List<TestItem> getListByQuery(int skip, int limit, String filter) { return getAllList(); }
        @Override public List<TestItem> getListByQuery(int skip, int limit, String filter, List<com.e2eq.framework.model.persistent.base.SortField> sortFields, List<com.e2eq.framework.model.persistent.base.ProjectionField> projectedProperties) { return getAllList(); }
        @Override public List<TestItem> getListByQuery(String realmId, int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields, List<com.e2eq.framework.model.persistent.base.ProjectionField> projectionFields) { return getAllList(); }
        @Override public List<TestItem> getListByQuery(dev.morphia.Datastore datastore, int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields, List<com.e2eq.framework.model.persistent.base.ProjectionField> projectionFields) { return getAllList(); }
        @Override public List<TestItem> getList(int skip, int limit, List<dev.morphia.query.filters.Filter> filters, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { return getAllList(); }
        @Override public List<TestItem> getList(String realmId, int skip, int limit, List<dev.morphia.query.filters.Filter> filters, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { return getAllList(); }
        @Override public List<TestItem> getList(dev.morphia.Datastore datastore, int skip, int limit, List<dev.morphia.query.filters.Filter> filters, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { return getAllList(); }
        @Override public List<TestItem> getListFromIds(String realmId, List<ObjectId> ids) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromIds(List<ObjectId> ids) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromIds(dev.morphia.Datastore datastore, List<ObjectId> ids) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromRefNames(String realmId, List<String> refNames) { return getListFromRefNames(refNames); }
        @Override public List<TestItem> getListFromRefNames(dev.morphia.Datastore datastore, List<String> refNames) { return getListFromRefNames(refNames); }
        @Override public List<com.e2eq.framework.model.persistent.base.EntityReference> getEntityReferenceListByQuery(String realmId, int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { throw new UnsupportedOperationException(); }
        @Override public List<com.e2eq.framework.model.persistent.base.EntityReference> getEntityReferenceListByQuery(int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { throw new UnsupportedOperationException(); }
        @Override public List<com.e2eq.framework.model.persistent.base.EntityReference> getEntityReferenceListByQuery(dev.morphia.Datastore datastore, int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromReferences(String realmId, List<com.e2eq.framework.model.persistent.base.EntityReference> references) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromReferences(List<com.e2eq.framework.model.persistent.base.EntityReference> references) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> getListFromReferences(dev.morphia.Datastore datastore, List<com.e2eq.framework.model.persistent.base.EntityReference> references) { throw new UnsupportedOperationException(); }
        @Override public com.e2eq.framework.model.persistent.base.CloseableIterator<TestItem> getStreamByQuery(int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields, List<com.e2eq.framework.model.persistent.base.ProjectionField> projectionFields) { throw new UnsupportedOperationException(); }
        @Override public com.e2eq.framework.model.persistent.base.CloseableIterator<TestItem> getStreamByQuery(dev.morphia.Datastore datastore, int skip, int limit, String query, List<com.e2eq.framework.model.persistent.base.SortField> sortFields, List<com.e2eq.framework.model.persistent.base.ProjectionField> projectionFields) { throw new UnsupportedOperationException(); }
        @Override public long getCount(String realmId, String filter) { return store.size(); }
        @Override public long getCount(String filter) { return store.size(); }
        @Override public long getCount(dev.morphia.Datastore datastore, String filter) { return store.size(); }
        @Override public long delete(String realm, TestItem obj) { throw new UnsupportedOperationException(); }
        @Override public long delete(TestItem obj) { throw new UnsupportedOperationException(); }
        @Override public long delete(ObjectId id) { throw new UnsupportedOperationException(); }
        @Override public long delete(String realmId, ObjectId id) { throw new UnsupportedOperationException(); }
        @Override public long delete(dev.morphia.Datastore datastore, TestItem aobj) { throw new UnsupportedOperationException(); }
        @Override public long delete(dev.morphia.transactions.MorphiaSession s, TestItem obj) { throw new UnsupportedOperationException(); }
        @Override public long updateActiveStatus(ObjectId id, boolean active) { throw new UnsupportedOperationException(); }
        @Override public long updateActiveStatus(dev.morphia.Datastore datastore, ObjectId id, boolean active) { throw new UnsupportedOperationException(); }
        @Override public long update(String realmId, String id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(String id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(dev.morphia.Datastore datastore, String id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(String realmId, ObjectId id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(dev.morphia.transactions.MorphiaSession session, String id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(ObjectId id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(dev.morphia.Datastore datastore, ObjectId id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public long update(dev.morphia.transactions.MorphiaSession session, ObjectId id, org.apache.commons.lang3.tuple.Pair<String, Object>... pairs) { throw new UnsupportedOperationException(); }

       @Override
       public long updateManyByQuery (@Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByQuery(query, pairs);
       }

       @Override
       public long updateManyByQuery (@NotNull String realmId, @Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByQuery(realmId, query, pairs);
       }

       @Override
       public long updateManyByQuery (@NotNull Datastore datastore, @Nullable String query, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByQuery(datastore, query, pairs);
       }

       @Override
       public long updateManyByQuery (@NotNull Datastore datastore, @Nullable String query, boolean ignoreRules, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByQuery(datastore, query, ignoreRules, pairs);
       }

       @Override
       public long updateManyByIds (@NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByIds(ids, pairs);
       }

       @Override
       public long updateManyByIds (@NotNull String realmId, @NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByIds(realmId, ids, pairs);
       }

       @Override
       public long updateManyByIds (@NotNull Datastore datastore, @NotNull List<ObjectId> ids, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByIds(datastore, ids, pairs);
       }

       @Override
       public long updateManyByIds (@NotNull Datastore datastore, @NotNull List<ObjectId> ids, boolean ignoreRules, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByIds(datastore, ids, ignoreRules, pairs);
       }

       @Override
       public long updateManyByRefAndDomain (@NotNull List<Pair<String, DataDomain>> items, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByRefAndDomain(items, pairs);
       }

       @Override
       public long updateManyByRefAndDomain (@NotNull String realmId, @NotNull List<Pair<String, DataDomain>> items, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByRefAndDomain(realmId, items, pairs);
       }

       @Override
       public long updateManyByRefAndDomain (@NotNull Datastore datastore, @NotNull List<Pair<String, DataDomain>> items, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByRefAndDomain(datastore, items, pairs);
       }

       @Override
       public long updateManyByRefAndDomain (@NotNull Datastore datastore, @NotNull List<Pair<String, DataDomain>> items, boolean ignoreRules, @NotNull Pair<String, Object>... pairs) throws InvalidStateTransitionException {
          return BaseMorphiaRepo.super.updateManyByRefAndDomain(datastore, items, ignoreRules, pairs);
       }

       @Override public TestItem merge(TestItem entity) { throw new UnsupportedOperationException(); }
        @Override public TestItem merge(dev.morphia.Datastore datastore, TestItem entity) { throw new UnsupportedOperationException(); }
        @Override public TestItem merge(dev.morphia.transactions.MorphiaSession session, TestItem entity) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> merge(List<TestItem> entities) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> merge(dev.morphia.Datastore datastore, List<TestItem> entities) { throw new UnsupportedOperationException(); }
        @Override public List<TestItem> merge(dev.morphia.transactions.MorphiaSession session, List<TestItem> entities) { throw new UnsupportedOperationException(); }
    }

    private CSVImportHelper helper;

    @BeforeEach
    public void setup() {
        helper = new CSVImportHelper();
        // inject a real validator
        try {
            java.lang.reflect.Field f = CSVImportHelper.class.getDeclaredField("validator");
            f.setAccessible(true);
            Validator v = Validation.buildDefaultValidatorFactory().getValidator();
            f.set(helper, v);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    public void testPreProcessBatchCounts() {
        FakeRepo repo = new FakeRepo();
        // existing record
        repo.store.put("existing-1", new TestItem("existing-1", "Existing"));
        List<TestItem> batch = Arrays.asList(
                new TestItem("new-1", "New One"),
                new TestItem("existing-1", "Existing"),
                new TestItem("no", "TooShortRefName") // invalid: refName size < 3
        );
        CSVImportHelper.ImportResult<TestItem> res = new CSVImportHelper.ImportResult<>(0,0);
        helper.preProcessBatch(repo, batch, res);
        Assertions.assertEquals(1, res.getInsertCount());
        Assertions.assertEquals(1, res.getUpdateCount());
        Assertions.assertTrue(res.getFailedCount() >= 1);
    }

    @Test
    public void testAnalyzeCSVAndCommit() throws Exception {
        FakeRepo repo = new FakeRepo();
        repo.store.put("existing-1", new TestItem("existing-1", "Existing"));
        String csv = "refName,displayName\n" +
                     "existing-1,Existing Updated\n" +
                     "new-1,Brand New\n" +
                     "no,Invalid"; // invalid refName length
        InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<String> requested = Arrays.asList("refName","displayName");
        CSVImportHelper.ImportResult<TestItem> preview = helper.analyzeCSV(repo, in, ',', '"', true, requested, StandardCharsets.UTF_8, false, "QUOTE_WHERE_ESSENTIAL");
        Assertions.assertNotNull(preview.getSessionId());
        Assertions.assertEquals(3, preview.getTotalRows());
        Assertions.assertEquals(2, preview.getValidRows());
        Assertions.assertEquals(1, preview.getErrorRows());
        Assertions.assertEquals(1, preview.getInsertCount());
        Assertions.assertEquals(1, preview.getUpdateCount());
        // commit
        CSVImportHelper.CommitResult commit = helper.commitImport(preview.getSessionId(), repo);
        Assertions.assertEquals(2, commit.getImported());
        Assertions.assertEquals(0, commit.getFailed());
        Assertions.assertTrue(repo.store.containsKey("new-1"));
    }
}
