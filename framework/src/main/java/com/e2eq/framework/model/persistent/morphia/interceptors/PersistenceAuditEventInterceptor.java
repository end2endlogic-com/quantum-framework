package com.e2eq.framework.model.persistent.morphia.interceptors;

import com.e2eq.framework.annotations.AuditPersistence;

import com.e2eq.framework.model.persistent.base.PersistentEvent;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.Datastore;
import dev.morphia.EntityListener;
import dev.morphia.annotations.PrePersist;
import io.quarkus.logging.Log;

import org.bson.Document;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

public class PersistenceAuditEventInterceptor implements EntityListener<Object> {
    @Override
    public boolean hasAnnotation(Class<? extends Annotation> type) {
      if (type.equals(AuditPersistence.class)){
            return true;
        } else {
            return false;
        }

    }

    @Override
    public void postLoad(Object entity, Document document, Datastore datastore) {

    }

    @Override
    public void postPersist(Object entity, Document document, Datastore datastore) {

    }

    @Override
    public void preLoad(Object entity, Document document, Datastore datastore) {

    }

    @PrePersist
    @Override
    public void prePersist(Object entity, Document document, Datastore datastore) {
        if (UnversionedBaseModel.class.isAssignableFrom(entity.getClass())) {
            UnversionedBaseModel baseModel = (UnversionedBaseModel) entity;

            if (!entity.getClass().isAnnotationPresent(AuditPersistence.class)) {
                return;
            }

            Log.debug("AuditPersistenceInterceptor prePersist: " + baseModel.getId());
            PersistentEvent event = new PersistentEvent();
            event.setEventType("PERSIST");
            event.setEventDate(new java.util.Date());
            event.setUserId(SecurityContext.getPrincipalContext().isPresent() ? SecurityContext.getPrincipalContext().get().getUserId() : "ANONMYMOUS ");

            List<PersistentEvent> events = baseModel.getPersistentEvents();
            if (events == null) {
                events = List.of(event);
            } else {
                events.add(event);
            }
            document.put("persistentEvents", events);
            baseModel.setPersistentEvents(events);
        }

    }
}
