package com.e2eq.ontology.policy.rest;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.BaseMorphiaRepo;
import com.e2eq.framework.rest.models.Collection;
import org.bson.Document;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class OntologyAwareResourceSecurityTest {

    @Test
    void aggregationExpandDelegatesToGovernedRepositoryContract() {
        AtomicReference<String> executedQuery = new AtomicReference<>();
        BaseMorphiaRepo<TestModel> repo = governedRepo(executedQuery);
        TestResource resource = new TestResource(repo);

        Collection<TestModel> result = resource.expand();

        Assertions.assertEquals("expand(customer)", executedQuery.get());
        Assertions.assertEquals(1, result.getRows().size());
        Object firstRow = ((List<?>) result.getRows()).get(0);
        Assertions.assertInstanceOf(Document.class, firstRow);
        Document row = (Document) firstRow;
        Assertions.assertEquals("ROOT-1", row.getString("refName"));
        Assertions.assertEquals("test-realm", result.getRealm());
    }

    @SuppressWarnings("unchecked")
    private BaseMorphiaRepo<TestModel> governedRepo(AtomicReference<String> executedQuery) {
        return (BaseMorphiaRepo<TestModel>) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{BaseMorphiaRepo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentClass" -> TestModel.class;
                    case "getDatabaseName" -> "test-realm";
                    case "getSecuredAggregationDocuments" -> {
                        executedQuery.set((String) args[3]);
                        yield List.of(new Document("refName", "ROOT-1")
                                .append("customer", new Document("refName", "CUSTOMER-1")));
                    }
                    case "toString" -> "GovernedTestRepo";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("Unexpected repository call: " + method);
                });
    }

    static class TestModel extends UnversionedBaseModel {
    }

    static class TestResource
            extends OntologyAwareResource<TestModel, BaseMorphiaRepo<TestModel>> {

        TestResource(BaseMorphiaRepo<TestModel> repo) {
            super(repo);
            ontologyListAggregationEnabled = true;
        }

        @Override
        protected boolean supportsExpandInOntologyList() {
            return true;
        }

        Collection<TestModel> expand() {
            return getOntologyList(null, 0, 10, null, null, null, "customer");
        }
    }
}
