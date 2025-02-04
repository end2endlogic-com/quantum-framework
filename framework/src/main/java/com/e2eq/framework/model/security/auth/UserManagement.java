package com.e2eq.framework.model.security.auth;

import java.util.Set;

public interface UserManagement {
    void createUser(String username, String password, Set<String> roles) throws SecurityException;
    void assignRoles(String username, Set<String> roles) throws SecurityException;
    void removeRoles(String username, Set<String> roles) throws SecurityException;
    Set<String> getUserRoles(String username) throws SecurityException;
    boolean userExists(String username) throws SecurityException;
}
