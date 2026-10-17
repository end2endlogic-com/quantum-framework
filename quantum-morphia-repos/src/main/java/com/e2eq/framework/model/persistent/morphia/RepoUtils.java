package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;


import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class RepoUtils {
    @Inject
    protected MorphiaDataStoreWrapper dataStore;

    @Inject
    RuleContext ruleContext;

    /**
     * Loads a row by class name and id under the caller's rule filters, as
     * {@code MorphiaRepo.findById} does. An explicit ignore-rules scope reads by id only; with no
     * security context the read is refused.
     */
    public BaseModel resolve(String className, ObjectId id) {
        try {
            Class<? extends BaseModel> clazz = (Class<? extends BaseModel>) Class.forName(className);
            List<Filter> filters = new ArrayList<>();
            filters.add(Filters.eq("_id", id));
            if (!SecurityContext.isIgnoringRules()) {
                if (SecurityContext.getPrincipalContext().isEmpty() || SecurityContext.getResourceContext().isEmpty()) {
                    throw new IllegalStateException("RepoUtils.resolve requires a security context or an explicit "
                            + "ignore-rules scope (SecurityCallScope.openIgnoringRules())");
                }
                filters = ruleContext.getFilters(filters, SecurityContext.getPrincipalContext().get(),
                        SecurityContext.getResourceContext().get(), clazz);
            }
            return dataStore.getDataStore(getSecurityContextRealmId()).find(clazz)
                    .filter(com.e2eq.framework.security.runtime.RuleFilterComposition.conjoin(filters)).first();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public String getSecurityContextRealmId() {
        String realmId = ruleContext.getDefaultRealm();

        if (SecurityContext.getPrincipalContext().isPresent() && SecurityContext.getResourceContext().isPresent()) {
            realmId = ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get());
        }

        if (realmId == null) {
            throw new RuntimeException("Logic error realmId should not be null");
        }

        return realmId;
    }

    /**
     * System-only maintenance: scans and rewrites every row of {@code clazz} in the realm, across
     * all data domains, so it runs only inside an explicit ignore-rules scope.
     */
    public void cleanUpReferences(Class clazz) {
        if (!SecurityContext.isIgnoringRules()) {
            throw new IllegalStateException("RepoUtils.cleanUpReferences is system-only; call it inside "
                    + "SecurityCallScope.openIgnoringRules()");
        }
        List<ReferenceEntry> removeEntries = new ArrayList<>();
        dataStore.getDataStore(getSecurityContextRealmId()).find(clazz).forEach(object -> {
                BaseModel baseModel = (BaseModel) object;
                baseModel.getReferences().forEach(reference -> {
                    Object o = dataStore.getDataStore(getSecurityContextRealmId()).find(object.getClass())
                            .filter(Filters.eq("_id", reference.getReferencedId())).first();
                    if (o == null ) {
                        removeEntries.add(reference);
                        if (Log.isEnabled(Logger.Level.WARN)) {
                            Log.warn("Removing stale reference: " + reference.getType() + " with id: " + reference.getReferencedId());
                        }
                    }

                });
            baseModel.getReferences().removeAll(removeEntries);
            dataStore.getDataStore(getSecurityContextRealmId()).save(baseModel);
            removeEntries.clear();
        });


    }
}
