package com.e2eq.framework.model.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.persistent.base.DataDomain;

import java.util.Optional;
import java.util.Set;

public interface UserManagement extends UserManagementBase{


    boolean removeUserWithSubject(String subject) throws ReferentialIntegrityViolationException;
    boolean removeUserWithUserId( String userId) throws ReferentialIntegrityViolationException;
    void assignRolesForUserId(String userId, Set<String> roles) throws SecurityException;
    void assignRolesForSubject(String subject, Set<String> roles) throws SecurityException;



    Set<String> getUserRolesForSubject(String subject) throws SecurityException;
    Set<String> getUserRolesForUserId(String userId) throws SecurityException;


    boolean userIdExists (String userId) throws SecurityException;


    boolean subjectExists (String subject) throws SecurityException;


    String createUser(String userId, String password,  Set<String> roles,  DomainContext domainContext) throws SecurityException;
    String createUser(String userId, String password, Boolean forceChangePassword,  Set<String> roles,  DomainContext domainContext) throws SecurityException;

    // New overloads to support explicit DataDomain specification
    String createUser(String userId, String password,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;
    String createUser(String userId, String password, Boolean forceChangePassword,
                    Set<String> roles, DomainContext domainContext, DataDomain dataDomain) throws SecurityException;

    Optional<String> getSubjectForUserId(String userId) throws SecurityException;
    Optional<String> getUserIdForSubject(String subject) throws SecurityException;


    void changePassword(String userId, String oldPassword, String newPassword, Boolean forceChangePassword);


    void removeRolesForSubject(String subject, Set<String> roles) throws SecurityException;
    void removeRolesForUserId(String userId, Set<String> roles) throws SecurityException;





}
