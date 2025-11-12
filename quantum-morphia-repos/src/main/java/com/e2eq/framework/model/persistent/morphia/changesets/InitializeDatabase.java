package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBase;
import com.e2eq.framework.model.persistent.morphia.*;
import com.e2eq.framework.model.security.*;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.rest.models.Role;

import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.migration.annotations.Execution;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;

import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mongodb.client.MongoClient;
import dev.morphia.Datastore;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.subscription.MultiEmitter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;


@Startup
@ApplicationScoped
public class InitializeDatabase extends ChangeSetBase {

   @Inject
   OrganizationRepo orgRepo;

   @Inject
   AccountRepo accountRepo;

   @Inject
   PolicyRepo policyRepo;

   @Inject
   FunctionalDomainRepo fdRepo;

   @Inject
   CredentialRepo credRepo;


   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   AuthProviderFactory authProviderFactory;



   @Inject
   CounterRepo counterRepo;

   @Inject
   EnvConfigUtils envConfigUtils;

   @Inject
   SecurityUtils securityUtils;

   @ConfigProperty( name="quantum.defaultSystemPassword")
   String defaultSystemPassword;

   @Execution
   public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception{
      // get flag from app config
              Log.infof("===>>> EXECUTING Initializing Database: %s", session.getDatabase().getName());
              emitter.emit(String.format("Initializing Database: %s", session.getDatabase().getName()));

              // Clean up duplicate counters before re-creating indexes
              try {
                 var counterColl = mongoClient.getDatabase(session.getDatabase().getName()).getCollection("counter");
                 var seen = new java.util.HashSet<String>();
                 counterColl.find().forEach(doc -> {
                    String refName = doc.getString("refName");
                    if (refName != null && !seen.add(refName)) {
                       counterColl.deleteOne(new org.bson.Document("_id", doc.get("_id")));
                       Log.infof("Deleted duplicate counter with refName: %s", refName);
                    }
                 });
              } catch (Exception e) {
                 Log.warnf("Error cleaning up duplicate counters: %s", e.getMessage());
              }

               ensureCounter(session, "accountNumber", 2000);
               Organization org = ensureOrganization(session, envConfigUtils.getSystemOrgRefName(),
                       envConfigUtils.getSystemOrgRefName(),
                       securityUtils.getSystemDataDomain());
               ensureAccountForOrg(session, org);
               createInitialRules(session);
               createInitialUserProfiles(session);
               createSecurityModel(session);
   }

   public Counter ensureCounter (Datastore session,  String counterName, long initialValue) {
      // check if counter exists; if not create it
      Optional<Counter> oCounter =  counterRepo.findByRefName(session, counterName);
      Counter counter;

      if (!oCounter.isPresent()) {
         Log.infof("Creating counter %s in realm:", counterName, session.getDatabase().getName());
         counter = new Counter();
         counter.setDisplayName(counterName);
         counter.setCurrentValue(initialValue);
         counter.setRefName(counterName);
         counter.setDataDomain(securityUtils.getSystemDataDomain());
         counter =  counterRepo.save(session, counter);
      } else {
         Log.infof("Skipping counter %s already exists in realm:%s", counterName, session.getDatabase().getName());
         counter = oCounter.get();
      }

      return counter;
   }

   public Organization ensureOrganization (Datastore session, String displayName, String refName, @NotNull @Valid DataDomain dataDomain ) {
      Optional<Organization> oOrg = orgRepo.findByRefName(session, envConfigUtils.getSystemOrgRefName());
      Organization org;
      if (!oOrg.isPresent()) {
         Log.infof("Creating org:%s refName:%s, with dataDomain:%s in realm:%s", displayName, refName, dataDomain.toString(), session.getDatabase().getName());
       org =  orgRepo.createOrganization(session, displayName, refName, dataDomain);
      }
      else {
         org = oOrg.get();
      }
      return org;
   }


   public Account ensureAccountForOrg (Datastore ds, Organization org) {

      Optional<Account> oAccount = accountRepo.findByRefName(ds, securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum());
      Account account;
      if (!oAccount.isPresent()) {
         Log.infof("Creating account:%s for org:%s in realm:%s", securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum(), org.getDisplayName(), ds.getDatabase().getName());
          account =   accountRepo.createAccount(ds, securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum(), org);
      } else {
         Log.infof("Skipping account:%s for org:%s already exists in realm:%s", securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum(), org.getDisplayName(), ds.getDatabase().getName());
         account = oAccount.get();
      }

      return account;
   }

   public void createInitialRules(Datastore datastore) {
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
      // from "any" account, in the  realm, any tenant, any owner, any datasegment
      SecurityURIBody body = new SecurityURIBody.Builder()
         .withAccountNumber("*") // any account
         .withRealm(envConfigUtils.getSystemRealm()) // within just the  realm
         .withOrgRefName(envConfigUtils.getSystemOrgRefName())
         .withTenantId("*") // any tenant
         .withOwnerId("*") // any owner
         .withDataSegment("*") // any datasegement
         .build();

      // Create the URI that represents this "rule" whereby
      // for any one with the role "user", we want to consider this rule base for
      // all resources in the  realm
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
         .withPostconditionScript("pcontext.getUserId() == rcontext.getOwnerId()")
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
      // The body says only for resources from the system realm that are owned by eg "system@system.com"
      // ie. are system objects
      header = new SecurityURIHeader.Builder()
         .withIdentity("user")
         .withArea("*")                      // any area
         .withFunctionalDomain("*")          // any domain
         .withAction("view")                 // view action
         .build();
      body = new SecurityURIBody.Builder()
         .withAccountNumber("*")             // any account
         .withRealm(envConfigUtils.getSystemRealm())     // within just the realm
         .withOrgRefName(envConfigUtils.getSystemOrgRefName())
         .withTenantId("*")                  // any tenant
         .withOwnerId(envConfigUtils.getSystemUserId())  // system owner
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
         .withOrFilterString("dataDomain.ownerId:" + envConfigUtils.getSystemUserId());

      r = b.build();
      defaultUserPolicy.getRules().add(r);
      defaultUserPolicy.setRefName("defaultUserPolicy");
      defaultUserPolicy.setDataDomain(securityUtils.getSystemDataDomain());

      // Name the policy
      if (!(policyRepo.findByRefName(datastore,"defaultUserPolicy").isPresent())) {
         Log.infof("Creating policy:%s in realm:%s", defaultUserPolicy.getDisplayName(), datastore.getDatabase().getName());
         policyRepo.save(datastore, defaultUserPolicy);
      } else {
         Log.infof("Policy:%s already exists in realm:%s", defaultUserPolicy.getDisplayName(), datastore.getDatabase().getName());
      }
      // So if the resource is "owned" by the principal then they see it
      // if the resource is owned by the system then they see it.
      // Ultimately what we want is a filter that says "ownerId:${userId} || ownerId:system@b2bintegrator.com"
   }

   public void createInitialUserProfiles(Datastore datastore) throws CloneNotSupportedException {

      Log.infof("Checking to create initial user profiles in realm: %s", datastore.getDatabase().getName());
      UserManagement userManager = authProviderFactory.getUserManager();
      if (!userManager.userIdExists(envConfigUtils.getSystemUserId())) {
         Log.infof("Creating system user: %s in realm: %s", envConfigUtils.getSystemUserId(), datastore.getDatabase().getName());
         Set<String> roles = new HashSet<>();
         roles.add(Role.admin.toString());
         roles.add(Role.user.toString());
         roles.add(Role.sysadmin.toString());


         userManager.createUser(envConfigUtils.getSystemUserId(), defaultSystemPassword, roles, securityUtils.getDefaultDomainContext());

      } else {
         Log.infof("UserProfile:%s already exists in realm: %s checking credential relationship", envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
        Optional<UserProfile> oup = userProfileRepo.getByUserId(datastore.getDatabase().getName(), envConfigUtils.getSystemUserId());
        if (oup.isPresent()) {
           UserProfile up = oup.get();
           if (up.getCredentialUserIdPasswordRef() == null) {
              Optional<CredentialUserIdPassword> ocred = credRepo.findByUserId(envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true);
              if (ocred.isPresent()) {
                 Log.infof("Creating credential relationship for user: %s in realm: %s", envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
                 up.setCredentialUserIdPasswordRef(ocred.get().createEntityReference());
              } else {
                 String text = String.format("Could not find credential for userId %s in realm %s", envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
                 Log.error(text);
                 throw new IllegalStateException(text);
              }

              try {
                 userProfileRepo.save(datastore, up);
              } catch (Exception e) {
                 Log.errorf("Failed to save user profile: %s", e.getMessage());
              }
           } else {
              Log.infof("Credential relationship for user: %s in realm: %s already exists", envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
           }
        } else {
           Optional<CredentialUserIdPassword> ocred = credRepo.findByUserId(envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm(), true);
           if (ocred.isPresent()) {
              // we need to create the initial system user profile
              Log.infof("Creating initial user profile: %s in realm: %s", envConfigUtils.getSystemUserId(), datastore.getDatabase().getName());
              UserProfile up = new UserProfile();
              up.setUserId(envConfigUtils.getSystemUserId());
              up.setDisplayName(envConfigUtils.getSystemUserId());
              up.setEmail(envConfigUtils.getSystemUserId() + "@example.com");
              up.setCredentialUserIdPasswordRef(null);
              up.setDataDomain(securityUtils.getSystemDataDomain());
              up.setCredentialUserIdPasswordRef(ocred.get().createEntityReference());
              userProfileRepo.save(datastore, up);
           } else {
              String text = String.format("Could not find credential for userId %s in realm %s even though userManager found userId config / data corruption", envConfigUtils.getSystemUserId(), envConfigUtils.getSystemRealm());
              Log.error(text);
              throw new IllegalStateException(text);
           }


        }
      }
   }

   public void createSecurityModel(Datastore datastore) throws IOException {
      ObjectMapper mapper = new ObjectMapper( new YAMLFactory());
      CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, FunctionalDomain.class);
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      InputStream inputStream = classLoader.getResourceAsStream("securityModel.yaml");
      List<FunctionalDomain> domains = mapper.readValue(inputStream, listType);

      domains.forEach((f) -> {
         f.setDataDomain(securityUtils.getSystemDataDomain());
         if (!fdRepo.findByRefName(datastore, f.getRefName()).isPresent())
            fdRepo.save(datastore,f);
      } );
   }

   @Override
   public String getId () {
      return "00001";
   }

   @Override
   public String getDbFromVersion () {
      return "1.0.0";
   }

   @Override
   public int getDbFromVersionInt() {
      return 100;
   }

   @Override
   public String getDbToVersion () {
      return "1.0.1";
   }

   @Override
   public int getDbToVersionInt() {
      return 101;
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

   @Override
   public String getChecksum() {
      return "v1.0.1-cleanup-duplicates";
   }
}
