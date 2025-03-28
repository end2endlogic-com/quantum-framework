package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

import static dev.morphia.query.Sort.descending;

@ApplicationScoped
public class DatabaseVersionRepo extends MorphiaRepo<DatabaseVersion> {

   public Optional<DatabaseVersion> findCurrentVersion(String realm) {
      return findCurrentVersion(morphiaDataStore.getDataStore(getSecurityContextRealmId()), realm);
   }


   public Optional<DatabaseVersion> findCurrentVersion(Datastore datastore, String realm) {
      Log.infof("Finding database version from realm: %s using datastore: %s", realm, datastore.getDatabase().getName() );

      DatabaseVersion version =  datastore.find(DatabaseVersion.class)
              .iterator(new FindOptions()
                      .sort(descending("currentVersionInt"))
                      .limit(1))
              .tryNext();

      return Optional.ofNullable(version);
   }
}
