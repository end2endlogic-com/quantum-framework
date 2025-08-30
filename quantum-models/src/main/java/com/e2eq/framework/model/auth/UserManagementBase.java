package com.e2eq.framework.model.auth;

public interface UserManagementBase {
   void enableImpersonationWithUserId(String userId, String impersonationScript, String realmFilter, String realmToEnableIn);
   void enableImpersonationWithSubject(String subject, String impersonationScript, String realmFilter, String realmToEnableIn);

   void disableImpersonationWithSubject(String subject, String realmToDisableIn);
   void disableImpersonationWithUserId(String userId, String realmToDisableIn);

   void enableRealmOverrideWithUserId(String userId, String realmToOverrideIn, String regexForRealm);
   void enableRealmOverrideWithSubject(String subject, String realmToOverrideIn, String regexForRealm);

   void disableRealmOverrideWithUserId(String userId, String realmToDisableIn);
   void disableRealmOverrideWithSubject(String subject, String realmToDisableIn);

}
