package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.morphia.*;
import com.e2eq.framework.model.persistent.security.*;
import com.e2eq.framework.rest.models.Role;
import com.e2eq.framework.config.AWSConfig;
import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.migration.annotations.Execution;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;

import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;


@Startup
@ApplicationScoped
public class InitializeDatabase implements ChangeSetBean {

   @Inject
   MorphiaDataStore dataStore;

   @Inject
   OrganizationRepo orgRepo;

   @Inject
   AccountRepo accountRepo;

   @Inject
   PolicyRepo policyRepo;

   @Inject
   FunctionalDomainRepo fdRepo;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   CredentialRepo credRepo;

   @Inject
   CounterRepo counterRepo;

   @Inject
   AWSConfig config;


   @Execution
   public void execute(String realm) throws Exception{
      // get flag from app config

      if (config.checkMigration().isPresent() && config.checkMigration().get().booleanValue()) {
         try (MorphiaSession session = dataStore.getDefaultSystemDataStore().startSession()) {
            try {
               session.startTransaction();
               ensureCounter("accountNumber", 2000);
               Organization org = ensureOrganization(SecurityUtils.systemOrgRefName,
                       SecurityUtils.systemOrgRefName,
                       SecurityUtils.systemDataDomain);
               ensureAccountForOrg(org);
               createInitialRules();
               createInitialUserProfiles();
               createSecurityModel();
               session.commitTransaction();
            } catch (Throwable ex) {
               session.abortTransaction();
               throw ex;
            }
         }
         // start transaction
         // do action
         // end transaction
      } else {
         if (Log.isInfoEnabled()) {
            Log.infof("Skipping database migration due to configuration: checkMigration is Present %s.  Value: %s ",config.checkMigration().isPresent(), (config.checkMigration().isPresent()) ? config.checkMigration().get().booleanValue() : "Null");
         }
      }
   }

   public Counter ensureCounter (String counterName, long initialValue) {
      // check if counter exists; if not create it
      Optional<Counter> oCounter =  counterRepo.findByRefName(counterName);
      Counter counter;

      if (!oCounter.isPresent()) {
         counter = new Counter();
         counter.setDisplayName(counterName);
         counter.setCurrentValue(initialValue);
         counter.setRefName(counterName);
         counter.setDataDomain(SecurityUtils.systemDataDomain);
         counter =  counterRepo.save(counter);
      } else {
         counter = oCounter.get();
      }

      return counter;
   }

   public Organization ensureOrganization (String displayName, String refName, @NotNull @Valid DataDomain dataDomain ) {
      Optional<Organization> oOrg = orgRepo.findByRefName(SecurityUtils.systemOrgRefName);
      Organization org;
      if (!oOrg.isPresent()) {
       org =  orgRepo.createOrganization(SecurityUtils.systemOrgRefName, SecurityUtils.systemOrgRefName, SecurityUtils.systemDataDomain);
      }
      else {
         org = oOrg.get();
      }
      return org;
   }

   public Account ensureAccountForOrg (Organization org) {

      Optional<Account> oAccount = accountRepo.findByRefName(SecurityUtils.systemPrincipalContext.getDataDomain().getAccountNum());
      Account account;
      if (!oAccount.isPresent()) {
          account =   accountRepo.createAccount(SecurityUtils.systemPrincipalContext.getDataDomain().getAccountNum(), org);
      } else {
         account = oAccount.get();
      }

      return account;
   }

   public void createInitialRules() {
      // Will Match on the header values using wildcard matching.

      // So this will match any user that has the role "user"
      // for "any area, any domain, and any action i.e. all areas, domains, and actions
      SecurityURIHeader header = new SecurityURIHeader.Builder()
         .withIdentity("user")      // with the role "user"
         .withArea("*")             // any area
         .withFunctionalDomain("*") // any domain
         .withAction("*")           // any action
         .build();

      // This will match the resources
      // from "any" account, in the "b2bi" realm, any tenant, any owner, any datasegment
      SecurityURIBody body = new SecurityURIBody.Builder()
         .withAccountNumber("*") // any account
         .withRealm(SecurityUtils.systemRealm) // within just the b2bi realm
         .withTenantId("*") // any tenant
         .withOwnerId("*") // any owner
         .withDataSegment("*") // any datasegement
         .build();

      // Create the URI that represents this "rule" where by
      // for any one with the role "user", we want to consider this rule base for
      // all resources in the b2bi realm
      SecurityURI uri = new SecurityURI(header, body);

      // Create the first rule which will be a rule that
      // compares the userId of the principal, with the resource's ownerId
      // if they match then we allow the user to do what ever they are asking
      // we consider this final, in that we don't need to check anything else
      // in this case
      // In the case we are reading we have a filter that constrains the result set
      // to where the ownerId is the same as the principalId
      Rule.Builder b = new Rule.Builder()
         .withName("view your own resources")
         .withSecurityURI(uri)
         .withPostconditionScript("pcontext.getUserId() == rcontext.getResourceOwnerId()")
         .withAndFilterString("dataDomain.ownerId:${principalId}")
         .withEffect(RuleEffect.ALLOW)
         .withFinalRule(true);
      Rule r = b.build();

      // We now add this rule to a policy
      Policy defaultUserPolicy = new Policy();
      defaultUserPolicy.setPrincipalId("user");
      defaultUserPolicy.setDisplayName("default user policy");
      defaultUserPolicy.setDescription("users can do anything they want to their own data");
      defaultUserPolicy.getRules().add(r);

      // Now we are creating another URI, however this one is more specific than the last one
      // in this case we are creating something that is again for the role "user",
      // however this time it will only match any area, and FD, but only the "view" action
      //
      // The body says only for resources from the b2bi realm that are owned by "system@b2bintegrator.com"
      // ie. are system objects
      header = new SecurityURIHeader.Builder()
         .withIdentity("user")
         .withArea("*")                      // any area
         .withFunctionalDomain("*")          // any domain
         .withAction("view")                 // view action
         .build();
      body = new SecurityURIBody.Builder()
         .withAccountNumber("*")             // any account
         .withRealm(SecurityUtils.systemRealm)     // within just the b2bintegrator-com realm
         .withTenantId("*")                  // any tenant
         .withOwnerId(SecurityUtils.systemUserId)  // system owner
         .withDataSegment("*")               // any data segment
         .build();

       uri = new SecurityURI(header, body);

       // Now we are adding a rule that says that we will allow with this
       // matching criteria, however the filter string here is for "ownerId:system@b2bintegrator.com"
       // its or'ed in which means that if this were to be added we would or this filter compared to
       // others.
       b = new Rule.Builder()
         .withName("ViewSystemResources")
         .withSecurityURI(uri)
         .withEffect(RuleEffect.ALLOW)
         .withFinalRule(true)
         .withOrFilterString("dataDomain.ownerId:" + SecurityUtils.systemUserId);

      r = b.build();
      defaultUserPolicy.getRules().add(r);
      defaultUserPolicy.setRefName("defaultUserPolicy");
      defaultUserPolicy.setDataDomain(SecurityUtils.systemDataDomain);

      // Name the policy
      if (!(policyRepo.findByRefName("defaultUserPolicy").isPresent()))
         policyRepo.save(defaultUserPolicy);

      // So if the resource is "owned" by the principal then they see it
      // if the resource is owned by the system then they see it.
      // Ultimately what we want is a filter that says "ownerId:${userId} || ownerId:system@b2bintegrator.com"
   }


   public void createInitialUserProfiles() throws CloneNotSupportedException {

      if (!userProfileRepo.getByUserId(SecurityUtils.systemUserId).isPresent()) {
         DataDomain upDataDomain = SecurityUtils.systemDataDomain.clone();
         upDataDomain.setOwnerId(SecurityUtils.systemUserId);
         Set<Role> roles = new HashSet<>();
         roles.add(Role.admin);
         UserProfile up = new UserProfile();

         up.setDataDomain(upDataDomain);
         up.setEmail(SecurityUtils.systemUserId);
         up.setRefName(up.getEmail());
         up.setUserId(up.getEmail());
         up.setUserName("Generic Admin");
         up.setDisplayName("Generic Admin");
         up.setFname("Generic");
         up.setLname("Admin");

         roles.add(Role.user);
         int i = 0;
         String[] rolesArray = new String[roles.size()];

         for (Role r : roles) {
            rolesArray[i++] = r.name();
         }
         userProfileRepo.createUser(SecurityUtils.systemRealm, up, rolesArray, "test123456");
      }

   }

   public void createSecurityModel() throws IOException {
      ObjectMapper mapper = new ObjectMapper( new YAMLFactory());
      CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, FunctionalDomain.class);
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      InputStream inputStream = classLoader.getResourceAsStream("securityModel.yaml");
      List<FunctionalDomain> domains = mapper.readValue(inputStream, listType);

      domains.forEach((f) -> {
         f.setDataDomain(SecurityUtils.systemDataDomain);
         if (!fdRepo.findByRefName(f.getRefName()).isPresent())
            fdRepo.save(f);
      } );
   }

   @Override
   public String getId () {
      return "00001";
   }

   @Override
   public Double getDbFromVersion () {
      return Double.valueOf(0.00d);
   }

   @Override
   public Double getDbToVersion () {
      return Double.valueOf(0.10d);
   }

   @Override
   public int getPriority () {
      return 100;
   }

   @Override
   public String getAuthor () {
      return "Michael Ingardia";
   }

   @Override
   public String getName () {
      return "Initialization database";
   }

   @Override
   public String getDescription () {
      return "Create Initial data";
   }

   @Override
   public String getScope () {
      return "ALL";
   }
}
