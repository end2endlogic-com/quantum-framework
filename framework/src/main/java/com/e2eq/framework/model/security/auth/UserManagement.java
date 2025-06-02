package com.e2eq.framework.model.security.auth;

import com.e2eq.framework.exceptions.ReferentialIntegrityViolationException;
import com.e2eq.framework.model.persistent.security.DomainContext;

import java.util.Set;

public interface UserManagement {
    void createUser(String userId, String password, String username, Set<String> roles,  DomainContext domainContext) throws SecurityException;
    boolean removeUser(String username) throws ReferentialIntegrityViolationException;
    void assignRoles(String username, Set<String> roles) throws SecurityException;
    void removeRoles(String username, Set<String> roles) throws SecurityException;
    Set<String> getUserRoles(String username) throws SecurityException;
    boolean usernameExists (String username) throws SecurityException;
    boolean userIdExists (String userId) throws SecurityException;
}
