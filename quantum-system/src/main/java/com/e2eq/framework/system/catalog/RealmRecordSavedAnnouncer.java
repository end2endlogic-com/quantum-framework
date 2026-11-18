package com.e2eq.framework.system.catalog;

import com.e2eq.framework.model.persistent.morphia.RealmRecordSaved;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Every saved realm row is a registration: {@link RealmRecordSaved} (any save site: catalog,
 * control-plane PUT, bootstrap, registration approval, changesets) is announced as
 * {@link RealmRegistered}.
 */
@ApplicationScoped
public class RealmRecordSavedAnnouncer {

    @Inject
    Event<RealmRegistered> realmRegistered;

    void onRealmRecordSaved(@Observes RealmRecordSaved saved) {
        realmRegistered.fire(new RealmRegistered(saved.refName()));
    }
}
