package com.e2eq.framework.model.persistent.morphia.changesets;

import com.e2eq.framework.model.persistent.morphia.*;
import com.e2eq.framework.model.persistent.security.*;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.rest.models.Role;

import com.e2eq.framework.model.persistent.base.Counter;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.migration.annotations.Execution;
import com.e2eq.framework.model.persistent.migration.base.ChangeSetBean;

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
public class InitializeDatabase implements ChangeSetBean {

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
   CounterRepo counterRepo;

   @Inject
   SecurityUtils securityUtils;

   @ConfigProperty( name="quantum.defaultSystemPassword")
   String defaultSystemPassword;

   @Execution
   public void execute(MorphiaSession session, MongoClient mongoClient, MultiEmitter<? super String> emitter) throws Exception{
      // get flag from app config
              Log.infof("Initializing Database: %s", session.getDatabase().getName());
              emitter.emit(String.format("Initializing Database: %s", session.getDatabase().getName()));

               ensureCounter(session, "accountNumber", 2000);
               Organization org = ensureOrganization(session, securityUtils.getSystemOrgRefName(),
                       securityUtils.getSystemOrgRefName(),
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
         counter = new Counter();
         counter.setDisplayName(counterName);
         counter.setCurrentValue(initialValue);
         counter.setRefName(counterName);
         counter.setDataDomain(securityUtils.getSystemDataDomain());
         counter =  counterRepo.save(session, counter);
      } else {
         counter = oCounter.get();
      }

      return counter;
   }

   public Organization ensureOrganization (Datastore session,  String displayName, String refName, @NotNull @Valid DataDomain dataDomain ) {
      Optional<Organization> oOrg = orgRepo.findByRefName(session, securityUtils.getSystemOrgRefName());
      Organization org;
      if (!oOrg.isPresent()) {
       org =  orgRepo.createOrganization(session, securityUtils.getSystemOrgRefName(), securityUtils.getSystemOrgRefName(), securityUtils.getSystemDataDomain());
      }
      else {
         org = oOrg.get();
      }
      return org;
   }


   public Account ensureAccountForOrg (Datastore ds, Organization org) {

      Optional<Account> oAccount = accountRepo.findByRefName(ds,securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum());
      Account account;
      if (!oAccount.isPresent()) {
          account =   accountRepo.createAccount(ds,securityUtils.getSystemPrincipalContext().getDataDomain().getAccountNum(), org);
      } else {
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
         .withRealm(securityUtils.getSystemRealm()) // within just the  realm
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
         .withRealm(securityUtils.getSystemRealm())     // within just the realm
         .withTenantId("*")                  // any tenant
         .withOwnerId(securityUtils.getSystemUserId())  // system owner
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
         .withOrFilterString("dataDomain.ownerId:" + securityUtils.getSystemUserId());

      r = b.build();
      defaultUserPolicy.getRules().add(r);
      defaultUserPolicy.setRefName("defaultUserPolicy");
      defaultUserPolicy.setDataDomain(securityUtils.getSystemDataDomain());

      // Name the policy
      if (!(policyRepo.findByRefName(datastore,"defaultUserPolicy").isPresent()))
         policyRepo.save(datastore, defaultUserPolicy);

      // So if the resource is "owned" by the principal then they see it
      // if the resource is owned by the system then they see it.
      // Ultimately what we want is a filter that says "ownerId:${userId} || ownerId:system@b2bintegrator.com"
   }


   public void createInitialUserProfiles(Datastore datastore) throws CloneNotSupportedException {

      Log.infof("Checking to create initial user profiles in realm: %s", datastore.getDatabase().getName());
      if (!userProfileRepo.getByUserId(datastore,securityUtils.getSystemUserId()).isPresent()) {
         Log.infof("UserProfile:%s not found creating ", securityUtils.getSystemUserId());
         DataDomain upDataDomain = securityUtils.getSystemDataDomain().clone();
         upDataDomain.setOwnerId(securityUtils.getSystemUserId());
         Set<Role> roles = new HashSet<>();
         roles.add(Role.admin);
         UserProfile up = new UserProfile();

         up.setDataDomain(upDataDomain);
         up.setEmail(securityUtils.getSystemUserId());
         up.setRefName(securityUtils.getSystemUserId());
         up.setUserId(securityUtils.getSystemUserId());
         up.setUsername(UUID.randomUUID().toString());
         up.setDisplayName("Generic Admin");
         up.setFname("Generic");
         up.setLname("Admin");

         roles.add(Role.user);
         int i = 0;
         String[] rolesArray = new String[roles.size()];

         for (Role r : roles) {
            rolesArray[i++] = r.name();
         }
         userProfileRepo.createUser(datastore, up, null, rolesArray, defaultSystemPassword);
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
}
