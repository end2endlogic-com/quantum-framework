package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.util.SecurityUtils;
import com.google.common.collect.Ordering;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;

@Startup
@ApplicationScoped
public class MigrationRunner {

   @ConfigProperty(name = "com.b2bi.database.version")
   protected String targetDatabaseVersion;

   @ConfigProperty(name = "com.b2bi.database.scope")
   protected String databaseScope;

   @ConfigProperty(name= "com.b2bi.database.migration.changeset.package")
   protected String changeSetPackage;

   @ConfigProperty(name= "com.b2bi.database.migration.enabled")
   protected boolean enabled;

   @Inject
   DatabaseVersionRepo databaseVersionRepo;

   @Inject
   ChangeSetRecordRepo changesetRecordRepo;

   @Inject
   BeanManager beanManager;

   @Inject
   RuleContext ruleContext;

   @PostConstruct
   public void init() throws Exception {

      if (enabled) {
         Log.warn(">> Checking Migration scripts <<");
      }

      //TODO: attempt to insert record into lock table
      // if successful continue else pause and try again

      Double currentDatabaseVersion;
      // Find the current version of the database.
      Optional<DatabaseVersion> odbv = databaseVersionRepo.findVersion();
      if (!odbv.isPresent()) {
         currentDatabaseVersion = 0.0d;
      } else {
         currentDatabaseVersion = Double.parseDouble(odbv.get().getCurrentVersion());
         if (currentDatabaseVersion.equals(Double.parseDouble(targetDatabaseVersion)) ) {
           Log.warn("Database is already at target level:" + targetDatabaseVersion);
            return;
         } else {
            Log.warn("!!! >> Database is not at target level:" + targetDatabaseVersion + " current database version is:" + odbv.get().getCurrentVersion());
         }
      }

      // scan the changesetPackage for annotated classes and collect them into a list
      // sort them by priority filtered by the scope and the fromVersion

      Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
      if (!changeSets.isEmpty()) {
         Log.info(" >> Number of ChangeSet Beans:" + changeSets.size());
         for (Bean bean : changeSets) {
            Log.info("     ChangeSet Bean:" + bean.getBeanClass().getName());
         }
      } else {
         Log.warn("!!! No changeset beans found");
      }

      Set<ChangeSetBean> changeSetBeans = new HashSet<>();
      for (Bean bean : changeSets) {
         CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
         ChangeSetBean chb = (ChangeSetBean)   beanManager.getReference(bean, bean.getBeanClass(), creationalContext);

         if (chb.getDbFromVersion() >= currentDatabaseVersion) {
            changeSetBeans.add(chb);
         } else {
            Log.warn(">> Ignoring Change Set:" + chb.getName() + " because it is not for the current database version:" + currentDatabaseVersion);
         }
      }

      List<ChangeSetBean> changeSetList = new LinkedList<>();
      changeSetList.addAll(changeSetBeans);
      Log.info(">> Number of ChangeSet Beans:" + changeSetBeans.size());
      Log.info(">> Number in ChangeSet List:" + changeSetList.size());

      Ordering<ChangeSetBean> byPriority= new Ordering<ChangeSetBean>() {
         @Override
         public int compare (ChangeSetBean left, ChangeSetBean right) {
            return right.getPriority() - left.getPriority();
         }
      };
      // sort by priority
      changeSetList.sort(byPriority);



      // Determine if the ruleContext is already populated or not
      ruleContext.ensureDefaultRules();

       //
      // determine if the changeset has been run in the past successfully
      // if not
      // execute, add results to a change set log.
      // after executing all the change sets
      // update the database version at the end


      // First check the version of the database
      // compare that to the application config property
      // and act accordingly

      //TODO get the security context sorted for migrations

      try (SecuritySession s = new SecuritySession(SecurityUtils.systemPrincipalContext, SecurityUtils.systemSecurityResourceContext)) {

         // Now Remove the ones that have already been run:
         Map<String, ChangeSetRecord> allReadyExecutedChangeSetRecords = changesetRecordRepo.getAllReadyExecutedChangeSetRecordMap(Float.parseFloat(targetDatabaseVersion));
         Log.warn(">> Number of ChangeSet Record Already Run:" + allReadyExecutedChangeSetRecords.size());
         Log.warn(">> ChangeSet Size:" + changeSetList.size());
         changeSetList.forEach(changeSetBean -> {
            if (allReadyExecutedChangeSetRecords.containsKey(changeSetBean.getName())) {
               Log.warn(" >> Ignoring Change Set:" + changeSetBean.getName() + " because it has already been executed." );
               changeSetList.remove(changeSetBean);
            } else {
               Log.info(">> Should execute:" + changeSetBean.getName());
            }
         });
         // increment the database number
         double version = currentDatabaseVersion;


         for (ChangeSetBean h : changeSetList) {
            Log.warn("--- Executing Change Set:" + h.getName() + " on realm:" + SecurityUtils.defaultRealm );
            h.execute(SecurityUtils.defaultRealm);
            ChangeSetRecord record = new ChangeSetRecord();
            record.setRealm(SecurityUtils.defaultRealm);
            record.setRefName(h.getName());
            record.setDataDomain(SecurityUtils.systemDataDomain);
            record.setAuthor(h.getAuthor());
            record.setChangeSetName(h.getId());
            record.setDescription(h.getDescription());
            record.setPriority(h.getPriority());
            record.setDbFromVersion(h.getDbFromVersion());
            record.setDbToVersion(h.getDbToVersion());
            record.setLastExecutedDate(new Date());
            record.setScope(h.getScope());
            record.setSuccessful(true);
            changesetRecordRepo.save(record);
            version = h.getDbToVersion();
         }



         DatabaseVersion dbVersion = new DatabaseVersion();
         dbVersion.setCurrentVersion((Double.toString(version)));
         dbVersion.setRefName(Double.toString(version));
         dbVersion.setDataDomain(SecurityUtils.systemDataDomain);
         dbVersion.setSince(new Date());
         databaseVersionRepo.save(dbVersion);
         Log.warn(">> Migration completed successfully to version:" + dbVersion.getCurrentVersion() + " <<");
      }
   }






}
