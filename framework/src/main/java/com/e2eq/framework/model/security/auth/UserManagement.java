package com.e2eq.framework.model.security.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.security.DomainContext;
import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.Set;

public interface UserManagement extends UserManagementBase{


    boolean removeUserWithUsername(String username) throws ReferentialIntegrityViolationException;
    boolean removeUserWithUsername(String realm, String username) throws ReferentialIntegrityViolationException;
    boolean removeUserWithUserId(String userId) throws ReferentialIntegrityViolationException;
    boolean removeUserWithUserId(String realm, String userId) throws ReferentialIntegrityViolationException;
    void assignRoles(String username, Set<String> roles) throws SecurityException;
    void removeRoles(String username, Set<String> roles) throws SecurityException;
    Set<String> getUserRoles(String username) throws SecurityException;

    boolean userIdExists (String userId) throws SecurityException;

    boolean userIdExists (String realm, String userId) throws SecurityException;

    boolean usernameExists (String username) throws SecurityException;

    boolean usernameExists(String realm, String username) throws SecurityException;

    void createUser(String userId, String password, String username, Set<String> roles,  DomainContext domainContext) throws SecurityException;
    void createUser(String userId, String password, Boolean forceChangePassword, String username, Set<String> roles,  DomainContext domainContext) throws SecurityException;
    void createUser(String realm, String userId, String password, String username, Set<String> roles,  DomainContext domainContext) throws SecurityException;
    void createUser(String realm, String userId, String password, Boolean forceChangePassword, String username, Set<String> roles,  DomainContext domainContext) throws SecurityException;

    // New overloads to support explicit DataDomain specification
    void createUser(String userId, String password, String username,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;

    void createUser(String userId, String password, Boolean forceChangePassword, String username,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;

    void createUser(String realm, String userId, String password, String username,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;

    void createUser(String realm, String userId, String password, Boolean forceChangePassword, String username,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;

    public void changePassword(String userId, String oldPassword, String newPassword, Boolean forceChangePassword);
    void changePassword(String realm, String userId, String oldPassword, String newPassword, Boolean forceChangePassword);

    void assignRoles(String realm,String username, Set<String> roles) throws SecurityException;
    void removeRoles(String realm, String username, Set<String> roles) throws SecurityException;
    Set<String> getUserRoles(String realm, String username) throws SecurityException;



}
