package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.model.persistent.morphia.RealmRecordSaved;
import com.e2eq.framework.model.security.Realm;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.bson.Document;

import java.lang.annotation.Annotation;

/**
 * Announces every saved {@link Realm} row ({@link RealmRecordSaved}), whichever site saved it, so
 * cached realm lookups never outlive a registration.
 */
@ApplicationScoped
public class RealmRecordSavedInterceptor implements EntityListener<Object> {

    @Inject
    Event<RealmRecordSaved> realmRecordSaved;

    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
        return false;
    }

    @Override
    public void postPersist(Object entity, Document document, Datastore datastore) {
        if (entity instanceof Realm realm && realm.getRefName() != null && realmRecordSaved != null) {
            try {
                realmRecordSaved.fire(new RealmRecordSaved(realm.getRefName()));
            } catch (RuntimeException observerFailure) {
                Log.warnf("Announcing saved realm %s failed: %s", realm.getRefName(), observerFailure.getMessage());
            }
        }
    }
}
