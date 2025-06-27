package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.migration.base.DatabaseVersion;
import dev.morphia.Datastore;
import dev.morphia.query.FindOptions;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.constraints.NotNull;

import java.util.Optional;

import static dev.morphia.query.Sort.descending;

@ApplicationScoped
public class DatabaseVersionRepo extends MorphiaRepo<DatabaseVersion> {

   public Optional<DatabaseVersion> findCurrentVersion(String realm) {
      return findCurrentVersion(morphiaDataStore.getDataStore(realm));
   }



   public Optional<DatabaseVersion> findCurrentVersion(Datastore datastore) {
      Log.infof("Finding database version for datastore: %s", datastore.getDatabase().getName() );


      DatabaseVersion version =  datastore.find(DatabaseVersion.class)
              .iterator(new FindOptions()
                      .sort(descending("currentVersionInt"))
                      .limit(1))
              .tryNext();

      if (version == null) {
         Log.infof("No database version found for datastore: %s", datastore.getDatabase().getName());
      } else {
         Log.infof("Found database version %s for datastore: %s", version.getCurrentVersionString(), datastore.getDatabase().getName());
      }
      return Optional.ofNullable(version);
   }
}
