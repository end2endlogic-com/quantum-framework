package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

import static dev.morphia.query.Sort.descending;

@ApplicationScoped
public class DatabaseVersionRepo extends MorphiaRepo<DatabaseVersion> {
   public Optional<DatabaseVersion> findVersion() {
      Log.info("Finding database version from realm:" + getDefaultRealmId());
      Datastore ds = dataStore.getDataStore(getDefaultRealmId());
      DatabaseVersion version =  ds.find(DatabaseVersion.class)
              .iterator(new FindOptions()
                      .sort(descending("currentVersion"))
                      .limit(1))
              .tryNext();

      return Optional.ofNullable(version);
   }
}
