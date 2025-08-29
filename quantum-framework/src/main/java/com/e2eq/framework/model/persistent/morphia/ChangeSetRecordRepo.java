package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.migration.base.ChangeSetRecord;
import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ChangeSetRecordRepo extends MorphiaRepo<ChangeSetRecord> {

   /**
    * Return the already executed set of changes
    * @return
    */
   public Map<String, ChangeSetRecord> getAllReadyExecutedChangeSetRecordMap() {
      Map<String, ChangeSetRecord>  allReadyExecuted = new HashMap<>();

      List<ChangeSetRecord> records = this.getAllList();
      records.forEach(record -> {
         allReadyExecuted.put(record.getChangeSetName(), record);
      });

      return allReadyExecuted;
   }
}
