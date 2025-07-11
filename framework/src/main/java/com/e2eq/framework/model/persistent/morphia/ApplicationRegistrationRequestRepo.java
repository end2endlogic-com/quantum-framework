package com.e2eq.framework.model.persistent.morphia;


import com.e2eq.framework.exceptions.E2eqValidationException;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.security.*;

import com.e2eq.framework.util.EncryptionUtils;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.ValidateUtils;

import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import dev.morphia.transactions.MorphiaSession;
import io.quarkus.logging.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.ValidationException;
import java.util.*;

@ApplicationScoped
public class ApplicationRegistrationRequestRepo extends MorphiaRepo<ApplicationRegistration> {

   @Inject
   OrganizationRepo orgRepo;

   @Inject
   CounterRepo countRepo;

   @Inject
   AccountRepo accountRepo;

   @Inject
   UserProfileRepo userProfileRepo;

   @Inject
   CredentialRepo credRepo;

   @Inject
   RealmRepo realmRepo;

   @ConfigProperty(name="auth.provider")
   private String authProvider;

   @Inject
   SecurityUtils securityUtils;


   @Override
   public ApplicationRegistration save(ApplicationRegistration value) {
      // validate the email address of the registration request:
      if (!ValidateUtils.isValidEmailAddress(value.getUserEmail())) {
         throw new ValidationException("email address is not valid");
      }

      return super.save(value);
   }

   public Optional<ApplicationRegistration> findByCompanyIdentifier(String identifier) {
      List<Filter> filters = new ArrayList<>();
      filters.add(Filters.eq("companyIdentifier", identifier));
      Query<ApplicationRegistration> query = morphiaDataStore.getDataStore(getSecurityContextRealmId()).find(getPersistentClass()).filter(getFilterArray(filters, getPersistentClass()));
      ApplicationRegistration obj = query.first();
      return Optional.ofNullable(obj);
   }

   String createTenantId(String initialValue) {
      return initialValue.replace(".", "-");
   }

   public Optional<ApplicationRegistration> approveRequest(String id) throws E2eqValidationException {
      Optional<ApplicationRegistration> orequest = this.findById(id);

      if (orequest.isPresent()) {
         ApplicationRegistration applicationRegistration = orequest.get();
         if (Log.isInfoEnabled()) {
            Log.info("Approving request: " + applicationRegistration.toString());
         }

         // create the user and give them account privileges
         // parse out the email address to determine the
         // tenant id ( the part after the @
         StringTokenizer tokenizer = new StringTokenizer(applicationRegistration.getUserEmail(), "@");
         String user = tokenizer.nextToken();
         String emailDomain = tokenizer.nextToken();
         String tenantId = createTenantId(emailDomain);

         // Next create an account
         // get next available account number
         long accountNumber;

         // create data Domain for new objects
         DataDomain dataDomain;


         if (Log.isInfoEnabled()) {
            Log.info("Looking for Organization with tenantId:" + tenantId);
         }

         Optional<Organization> org;
         Organization newOrg;
         try (MorphiaSession session = this.startSession(tenantId)) {

            session.startTransaction();
            // Check if the org already exists:
            org = orgRepo.findByRefName(tenantId);

            if (org.isEmpty()) {
               if (Log.isInfoEnabled()) {
                  Log.info("Organization does not exist, creating new one");
               }

               // first lets create the organization
               newOrg = new Organization();
               newOrg.setDisplayName(applicationRegistration.getCompanyName());
               newOrg.setOwnerEmail(applicationRegistration.getUserEmail());
               newOrg.setRefName(tenantId);

               dataDomain = new DataDomain();
               dataDomain.setTenantId(tenantId);
               dataDomain.setOwnerId(applicationRegistration.getUserEmail());

               accountNumber = countRepo.getAndIncrement("accountNumber", securityUtils.getSystemDataDomain(), 200);

               String newAccountId = String.format("%05d", accountNumber);

               if (Log.isInfoEnabled()) {
                  Log.info("Account Number:" + newAccountId);
               }

               dataDomain.setAccountNum(newAccountId);
               dataDomain.setOrgRefName(tenantId);
               dataDomain.setDataSegment(securityUtils.getDefaultDataSegment());
               dataDomain.setOwnerId(securityUtils.getSystemUserId());

               newOrg.setDataDomain(dataDomain);
               newOrg = session.save(newOrg);
            } else {
               if (Log.isEnabled(Logger.Level.WARN)) {
                  Log.warn("Org:" + org.get().getRefName() + " already exists skipping creation");
                  Log.warn(" Account Number:" + org.get().getDataDomain().getAccountNum());
               }

               newOrg = org.get();
               dataDomain = org.get().getDataDomain();
            }

            Optional<Account> oaccount = accountRepo.findByRefName(dataDomain.getAccountNum());
            Account account;
            if (oaccount.isEmpty()) {
               if (Log.isInfoEnabled()) {
                  Log.info("Account does not exist, creating new one");
               }

               // first lets create the account
               account = new Account();
               account.setAccountNumber(dataDomain.getAccountNum());
               account.setRefName(dataDomain.getAccountNum());
               account.setDisplayName(newOrg.getDisplayName());
               account.setDataDomain(dataDomain);
               account.setOwningOrg(newOrg);
               account = accountRepo.save(session, account);
            } else {
               Log.warn("Account:" + org.get().getRefName() + " already exists skipping creation");
               account = oaccount.get();
            }

            Optional<UserProfile> ouserProfile = userProfileRepo.findByRefName(applicationRegistration.getUserId());
            UserProfile up;
            if (ouserProfile.isEmpty()) {
               DataDomain userdd = dataDomain.clone();
               userdd.setOwnerId(applicationRegistration.getUserId());

               // create new user profile
               up = new UserProfile();
               up.setUsername(applicationRegistration.getUserId());
               up.setEmail(applicationRegistration.getUserEmail());

               up.setUserId(applicationRegistration.getUserId());
               up.setRefName(applicationRegistration.getUserId());
               up.setDataDomain(userdd);
               userProfileRepo.save(session, up);

               Map<String, String> overrides = new HashMap<>();
               overrides.put("accountManagement", securityUtils.getSystemRealm());
               overrides.put("security", securityUtils.getSystemRealm());

               CredentialUserIdPassword cred = new CredentialUserIdPassword();
               cred.setUserId(applicationRegistration.getUserId());
               cred.setRefName(applicationRegistration.getUserId());
               cred.setDomainContext(new DomainContext(dataDomain, securityUtils.getSystemRealm() ));
               cred.setDataDomain(dataDomain);
               cred.setHashingAlgorithm("BCrypt.default");
               cred.setPasswordHash(EncryptionUtils.hashPassword(applicationRegistration.getPassword()));
               cred.setLastUpdate(new Date());
               String[] roles = {"admin"};
               cred.setRoles(roles);
               credRepo.save(session, cred);
            } else {
               Log.warn("UserProfile:" + ouserProfile.get().getRefName() + " already exists skipping creation");
               up = ouserProfile.get();
            }

            // Register the realm
            Realm realm = new Realm();
            realm.setDataDomain(securityUtils.getSystemDataDomain());
            realm.setEmailDomain(emailDomain);
            realm.setDatabaseName(tenantId);
            realm.setRefName(tenantId);
            DomainContext domainContext = DomainContext.builder()
                            .tenantId(tenantId)
                            .orgRefName(newOrg.getRefName())
                            .defaultRealm(tenantId)
                            .accountId(account.getAccountNumber())
                            .build();
            realm.setDomainContext(domainContext);
            realm.setDefaultAdminUserId(up.getUserId());

            realmRepo.save(session, realm);

            applicationRegistration.setStatus(ApplicationRegistration.Status.APPROVED);
            applicationRegistration = save(applicationRegistration);
            orequest = Optional.ofNullable(applicationRegistration);

            session.commitTransaction();
         } catch (CloneNotSupportedException e) {
             throw new RuntimeException(e);
         }
      }

      return orequest;
   }
}
