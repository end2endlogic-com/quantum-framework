package com.e2eq.framework.model.auth;

public interface UserManagementBase {
   void enableImpersonationWithUserId(String userId, String impersonationScript, String realmFilter, String realmToEnableIn);
   void enableImpersonationWithSubject(String subject, String impersonationScript, String realmFilter, String realmToEnableIn);

   void disableImpersonationWithSubject(String subject);
   void disableImpersonationWithUserId(String userId);

   void enableRealmOverrideWithUserId(String userId,  String regexForRealm);
   void enableRealmOverrideWithSubject(String subject,  String regexForRealm);

   void disableRealmOverrideWithUserId(String userId);
   void disableRealmOverrideWithSubject(String subject);

}
