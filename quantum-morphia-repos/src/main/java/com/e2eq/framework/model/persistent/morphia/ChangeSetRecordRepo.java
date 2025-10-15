package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetRecord;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ChangeSetRecordRepo extends MorphiaRepo<ChangeSetRecord> {

   /**
    * Return the already executed set of changes mapping to the latest version for each changeSetName
    * @return
    */
   public Map<String, ChangeSetRecord> getAllReadyExecutedChangeSetRecordMap() {
      Map<String, ChangeSetRecord>  allReadyExecuted = new HashMap<>();

      List<ChangeSetRecord> records = this.getAllList();
      records.forEach(record -> {
         ChangeSetRecord existing = allReadyExecuted.get(record.getChangeSetName());
         if (existing == null || existing.getChangeSetVersion() < record.getChangeSetVersion()) {
            allReadyExecuted.put(record.getChangeSetName(), record);
         }
      });

      return allReadyExecuted;
   }

   /**
    * Find the latest ChangeSetRecord by changeSetName (highest changeSetVersion).
    */
   public java.util.Optional<ChangeSetRecord> findLatestByChangeSetName(dev.morphia.Datastore datastore, String changeSetName) {
      java.util.List<ChangeSetRecord> list = datastore.find(ChangeSetRecord.class)
              .filter(dev.morphia.query.filters.Filters.eq("changeSetName", changeSetName))
              .iterator(new dev.morphia.query.FindOptions().sort(dev.morphia.query.Sort.descending("changeSetVersion")))
              .toList();
      if (list == null || list.isEmpty()) return java.util.Optional.empty();
      return java.util.Optional.of(list.get(0));
   }
}
