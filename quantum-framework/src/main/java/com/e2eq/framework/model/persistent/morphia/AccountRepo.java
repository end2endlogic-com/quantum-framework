package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.Account;
import com.e2eq.framework.model.persistent.security.Organization;

import dev.morphia.Datastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Repository for creating and retrieving {@link Account} entities.
 */
@ApplicationScoped
public class AccountRepo extends MorphiaRepo<Account> {

   /**
    * Convenience method that uses the current security context datastore to create an account.
    */
   public Account createAccount(String accountNumber, @Valid @NotNull Organization org) {
      return createAccount(morphiaDataStore.getDataStore(getSecurityContextRealmId()), accountNumber, org);
   }

   /**
    * Creates a new account in the specified datastore.
    *
    * @param datastore     the datastore to persist the account in
    * @param accountNumber account number for the new account
    * @param org           owning organisation of the account
    * @return the persisted account
    */
   public Account createAccount(Datastore datastore, String accountNumber, @Valid @NotNull Organization org) {
      Account account = new Account();
      account.setDataDomain(org.getDataDomain());
      account.setAccountNumber(accountNumber);
      account.setRefName(account.getAccountNumber());
      account.setDisplayName(org.getDisplayName());
      account.setOwningOrg(org);
      return datastore.save(account);
   }
}
