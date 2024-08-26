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
    * @param targetVersion
    * @return
    */
   public Map<String, ChangeSetRecord> getAllReadyExecutedChangeSetRecordMap(float targetVersion) {
      Map<String, ChangeSetRecord>  allReadyExecuted = new HashMap<>();

      String query = "dbToVersion:<=##" + Float.toString(targetVersion);
      List<ChangeSetRecord> records = this.getListByQuery(0,0, query);
      records.forEach(record -> {
         allReadyExecuted.put(record.getChangeSetName(), record);
      });

      return allReadyExecuted;
   }
}
