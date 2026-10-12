package com.e2eq.ontology.runtime;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.system.catalog.RealmRegistered;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.Optional;

/**
 * Connects {@link RealmOntologyKeys} to the realm registry ({@link SystemDirectory}: local repos
 * when embedded, the control plane in remote mode) before anything reads an ontology, and moves a
 * realm's TBoxes from the legacy realm-name key to its registered key the first time that key is
 * used. A {@link RealmRegistered} event drops the realm's cached key, so a realm looked up before it
 * was provisioned picks up its registered key at once.
 */
@Startup(1)
@ApplicationScoped
public class RealmOntologyKeyResolver {

    static final String TBOX_COLLECTION = "tenant_ontology_tbox";

    @Inject
    Instance<SystemDirectory> systemDirectory;

    @Inject
    MorphiaDataStoreWrapper dataStores;

    @PostConstruct
    void install() {
        if (!systemDirectory.isResolvable()) {
            Log.info("RealmOntologyKeyResolver: no realm registry; ontologies stay keyed by realm name");
            return;
        }
        RealmOntologyKeys.install(this::registeredDomain, this::moveLegacyTBoxes);
    }

    @PreDestroy
    void uninstall() {
        RealmOntologyKeys.uninstall();
    }

    /**
     * A realm was registered (or re-registered): drop its cached key, including a "not registered"
     * answer from before provisioning, so the next lookup reads the registry again.
     */
    void onRealmRegistered(@Observes RealmRegistered event) {
        RealmOntologyKeys.refresh(event.refName());
    }

    /** The realm's registered domain: the domain context its users act in. */
    Optional<DataDomain> registeredDomain(String realm) {
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            return systemDirectory.get().findRealmByRefName(realm)
                    .map(r -> r.getDomainContext())
                    .map(RealmOntologyKeyResolver::toDataDomain);
        }
    }

    static DataDomain toDataDomain(DomainContext dc) {
        return new DataDomain(dc.getOrgRefName(), dc.getAccountId(), dc.getTenantId(), dc.getDataSegment(), dc.getDefaultRealm());
    }

    /**
     * Re-key TBoxes saved under the legacy key (every field the realm name) to the registered key, so
     * a realm that published before keeps its active model. A system migration of the realm's own
     * ontology records: done directly on the collection and logged. A failure is rethrown so
     * {@link RealmOntologyKeys} keeps the move pending and runs it again on the realm's next lookup;
     * the failed lookup exposes a typed error instead of an empty ontology.
     */
    void moveLegacyTBoxes(String realm, DataDomain key) {
        DataDomain legacy = RealmOntologyKeys.legacy(realm);
        try {
            var collection = dataStores.getDataStore(realm).getDatabase().getCollection(TBOX_COLLECTION, Document.class);
            var moved = collection.updateMany(
                    Filters.and(
                            Filters.eq("dataDomain.orgRefName", legacy.getOrgRefName()),
                            Filters.eq("dataDomain.accountNum", legacy.getAccountNum()),
                            Filters.eq("dataDomain.tenantId", legacy.getTenantId())),
                    Updates.combine(
                            Updates.set("dataDomain.orgRefName", key.getOrgRefName()),
                            Updates.set("dataDomain.accountNum", key.getAccountNum()),
                            Updates.set("dataDomain.tenantId", key.getTenantId()),
                            Updates.set("dataDomain.dataSegment", key.getDataSegment()),
                            Updates.set("dataDomain.ownerId", key.getOwnerId())));
            if (moved.getModifiedCount() > 0) {
                Log.infof("RealmOntologyKeyResolver: moved %d TBox(es) of realm %s from the realm-name key to tenant %s",
                        moved.getModifiedCount(), realm, key.getTenantId());
            }
        } catch (RuntimeException e) {
            Log.warnf(e, "RealmOntologyKeyResolver: could not move legacy TBoxes of realm %s to tenant %s; retrying on next lookup",
                    realm, key.getTenantId());
            throw e;
        }
    }
}
