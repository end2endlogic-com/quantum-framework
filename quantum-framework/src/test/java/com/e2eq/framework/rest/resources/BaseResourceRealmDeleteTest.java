package com.e2eq.framework.rest.resources;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import jakarta.ws.rs.core.HttpHeaders;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseResourceRealmDeleteTest {
    private static final String REALM = "century-logistics";
    private static final String REF_NAME = "WRKBK-SALES-DEMO";
    private static final String ID = "6a551ff9e72a57b17de69508";

    @Test
    void realmScopedDeleteByRefNameKeepsIdentifierAndRealmInRepositoryOrder() throws Exception {
        TrackingRepo repo = new TrackingRepo();
        TestResource resource = new TestResource(repo);

        assertEquals(200, resource.deleteByRefName(headers(), REF_NAME).getStatus());
        assertEquals(REF_NAME, repo.requestedRefName);
        assertEquals(REALM, repo.requestedRealm);
    }

    @Test
    void realmScopedDeleteByIdKeepsIdentifierAndRealmInRepositoryOrder() throws Exception {
        TrackingRepo repo = new TrackingRepo();
        TestResource resource = new TestResource(repo);

        assertEquals(200, resource.delete(headers(), ID).getStatus());
        assertEquals(ID, repo.requestedId);
        assertEquals(REALM, repo.requestedRealm);
    }

    private static HttpHeaders headers() {
        return (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(),
                new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> "getHeaderString".equals(method.getName())
                        && args != null && args.length == 1 && "X-Realm".equals(args[0])
                        ? REALM
                        : null);
    }

    private static final class TestResource extends BaseResource<TestEntity, TrackingRepo> {
        private TestResource(TrackingRepo repo) { super(repo); }
    }

    private static final class TestEntity extends UnversionedBaseModel { }

    private static final class TrackingRepo extends MorphiaRepo<TestEntity> {
        private String requestedRefName;
        private String requestedId;
        private String requestedRealm;

        @Override
        public Optional<TestEntity> findByRefName(String refName, String realmId) {
            requestedRefName = refName;
            requestedRealm = realmId;
            return Optional.of(entity());
        }

        @Override
        public Optional<TestEntity> findById(String id, String realmId) {
            requestedId = id;
            requestedRealm = realmId;
            return Optional.of(entity());
        }

        @Override
        public long delete(String realmId, TestEntity value) throws ReferentialIntegrityViolationException {
            requestedRealm = realmId;
            return 1;
        }

        private TestEntity entity() {
            TestEntity value = new TestEntity();
            value.setId(new ObjectId(ID));
            value.setRefName(REF_NAME);
            return value;
        }
    }
}
