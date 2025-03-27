package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.security.Account;
import com.e2eq.framework.model.persistent.security.Organization;

import dev.morphia.Datastore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@ApplicationScoped
public class AccountRepo extends MorphiaRepo<Account> {

   public Account createAccount(String accountNumber, @Valid @NotNull Organization org) {
      return createAccount(morphiaDataStore.getDataStore(getSecurityContextRealmId()), accountNumber, org);
   }

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
