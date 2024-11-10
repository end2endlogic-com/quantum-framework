package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.ReferenceEntry;
import com.e2eq.framework.model.securityrules.RuleContext;
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
    protected MorphiaDataStore dataStore;

    @Inject
    RuleContext ruleContext;

    public BaseModel resolve(String className, ObjectId id) {
        try {
            Class<? extends BaseModel> clazz = (Class<? extends BaseModel>) Class.forName(className);
            List<Filter> filters = new ArrayList<>();
            filters.add(Filters.eq("_id", id));
            Filter[] qfilters = filters.toArray(new Filter[0]);
            return dataStore.getDataStore(getSecurityContextRealmId()).find(clazz).filter(qfilters).first();
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public String getSecurityContextRealmId() {
        String realmId = RuleContext.DefaultRealm;

        if (SecurityContext.getPrincipalContext().isPresent() && SecurityContext.getResourceContext().isPresent()) {
            realmId = ruleContext.getRealmId(SecurityContext.getPrincipalContext().get(),
                    SecurityContext.getResourceContext().get());
        }

        if (realmId == null) {
            throw new RuntimeException("Logic error realmId should not be null");
        }

        return realmId;
    }

    public void cleanUpReferences(Class clazz) {
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
