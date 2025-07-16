package com.e2eq.framework.model.security.auth;

public interface UserManagementBase {
   void enableImpersonationWithUserId(String userId, String impersonationScript, String realmFilter, String realmToEnableIn);
   void enableImpersonationWithUsername(String username, String impersonationScript, String realmFilter, String realmToEnableIn);

   void disableImpersonationWithUserName(String username, String realmToDisableIn);
   void disableImpersonationWithUserId(String userId, String realmToDisableIn);

   void enableRealmOverrideWithUserId(String userId, String realmToOverrideIn, String regexForRealm);
   void enableRealmOverrideWithUsername(String username, String realmToOverrideIn, String regexForRealm);

   void disableRealmOverrideWithUserId(String userId, String realmToDisableIn);
   void disableRealmOverrideWithUsername(String username, String realmToDisableIn);

}
