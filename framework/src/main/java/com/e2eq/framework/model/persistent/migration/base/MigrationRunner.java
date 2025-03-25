package com.e2eq.framework.model.persistent.migration.base;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecuritySession;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.morphia.ChangeSetRecordRepo;
import com.e2eq.framework.model.persistent.morphia.DatabaseVersionRepo;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
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

   @ConfigProperty(name = "quantum.database.version")
   protected String targetDatabaseVersion;

   @ConfigProperty(name = "quantum.database.scope")
   protected String databaseScope;

   @ConfigProperty(name= "quantum.database.migration.changeset.package")
   protected String changeSetPackage;

   @ConfigProperty(name= "quantum.database.migration.enabled")
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

      // IF we are enabled to run migration scripts
      if (enabled) {
         Log.warn(">> Checking Migration scripts <<");

         Double currentDatabaseVersion;

         // Find the current version of the database.
         Optional<DatabaseVersion> odbv = databaseVersionRepo.findVersion();

         // If no version is found, then assume the database is at version 0.0
         if (!odbv.isPresent()) {
            currentDatabaseVersion = 0.0d;
         } else {
            // get the current version from the database version record.
            currentDatabaseVersion = Double.parseDouble(odbv.get().getCurrentVersion());
            // if the current version is the same as the target version, then we are done.
            if (currentDatabaseVersion.equals(Double.parseDouble(targetDatabaseVersion))) {
               Log.warn("Database is already at target level:" + targetDatabaseVersion);
               return;
            } else {
               Log.warn("!!! >> Database is not at target level:" + targetDatabaseVersion + " current database version is:" + odbv.get().getCurrentVersion());
            }
         }

         // use the beanManager to find all ChangeSetBeans
         Log.info("Searching for any change set beans");
         Set<Bean<?>> changeSets = beanManager.getBeans(ChangeSetBean.class);
         if (!changeSets.isEmpty()) {
            Log.info(" >> Found" + changeSets.size() + " ChangeSet Beans from beanManager");
            for (Bean<?> bean : changeSets) {
               Log.info("     ChangeSet Bean:" + bean.getBeanClass().getName());
            }
         } else {
            Log.warn("!!! No changeset beans found");
         }

         Set<ChangeSetBean> changeSetBeans = new HashSet<>();

         // loop over the change set beans
         for (Bean bean : changeSets) {
            CreationalContext<?> creationalContext = beanManager.createCreationalContext(bean);
            ChangeSetBean chb = (ChangeSetBean) beanManager.getReference(bean, bean.getBeanClass(), creationalContext);
            // if the dbFromVersion is greater than or equal to the current database version, then add it to the list of change sets to consider.
            if (chb.getDbToVersion() >= currentDatabaseVersion) {
               changeSetBeans.add(chb);
            } else {
               Log.warn(">> Ignoring Change Set:" + chb.getName() + " because it is not for the current database version:" + currentDatabaseVersion);
            }
         }

         List<ChangeSetBean> changeSetList = new LinkedList<>();
         changeSetList.addAll(changeSetBeans);
         Log.info(">> Number of ChangeSet Found Beans:" + changeSetBeans.size());
         Log.info(">> Number in ChangeSet Beans added to List fo consider:" + changeSetList.size());
         Log.info(">> Sorting ChangeSet Beans by Priority");
         Ordering<ChangeSetBean> byPriority = new Ordering<ChangeSetBean>() {
            @Override
            public int compare(ChangeSetBean left, ChangeSetBean right) {
               return right.getPriority() - left.getPriority();
            }
         };
         // sort by DbToVersion and then by priority
         changeSetList.sort(Comparator.comparing(ChangeSetBean::getDbToVersion)
                 .thenComparing(ChangeSetBean::getPriority));

         // Determine if the ruleContext is already populated or not
         ruleContext.ensureDefaultRules();

         // determine if the changeset has been run in the past successfully
         // if not
         // execute, add results to a change set log.
         // after executing all the change sets
         // update the database version at the end


         // First check the version of the database
         // compare that to the application config property
         // and act accordingly

         Log.info("--- Running ChangeSet Beans");
         String[] roles = {"admin"};
         PrincipalContext pContext = TestUtils.getPrincipalContext(TestUtils.systemUserId, roles);
         ResourceContext rContext = TestUtils.getResourceContext("migration", "changebean", "save");
         TestUtils.initRules(ruleContext, "security", "userProfile", TestUtils.systemUserId);


         try (SecuritySession s = new SecuritySession(pContext, rContext)) {

            // Now Remove the ones that have already been run:
            Map<String, ChangeSetRecord> allReadyExecutedChangeSetRecords = changesetRecordRepo.getAllReadyExecutedChangeSetRecordMap(Float.parseFloat(targetDatabaseVersion));
            Log.warn(">> Number of ChangeSet Record Already Run:" + allReadyExecutedChangeSetRecords.size());
            Log.warn(">> ChangeSet Size:" + changeSetList.size());
            changeSetList.forEach(changeSetBean -> {
               if (allReadyExecutedChangeSetRecords.containsKey(changeSetBean.getName())) {
                  Log.warn(" >> Ignoring Change Set:" + changeSetBean.getName() + " because it has already been executed.");
                  changeSetList.remove(changeSetBean);
               } else {
                  Log.info(">> Should execute:" + changeSetBean.getName());
               }
            });
            // increment the database number
            double version = currentDatabaseVersion;


            for (ChangeSetBean h : changeSetList) {
               if (!changesetRecordRepo.findByRefName(h.getName()).isPresent()) {
                  Log.warn("--- Executing Change Set:" + h.getName() + " on realm:" + SecurityUtils.defaultRealm);
                  try {
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
                     DatabaseVersion dbVersion = new DatabaseVersion();
                     dbVersion.setCurrentVersion((Double.toString(version)));
                     dbVersion.setRefName(Double.toString(version));
                     dbVersion.setDataDomain(SecurityUtils.systemDataDomain);
                     dbVersion.setSince(new Date());
                     databaseVersionRepo.save(dbVersion);
                     Log.info("--- Database Version Updated to:" + version);
                  } catch (Throwable throwable) {
                    Log.error("Error executing Change Set:" + h.getName(), throwable);
                    throw throwable;
                  }

               } else {
                  Log.warn(">> Ignoring Change Set:" + h.getName() + " because it has already been executed.");
               }
            }


         }
      } else {
         Log.info("---- Database Migration is disabled");
      }
   }
}
