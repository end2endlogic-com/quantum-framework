package com.e2eq.framework.model.security.auth.provider.jwtToken;


import com.e2eq.framework.model.security.auth.AuthProvider;
import com.e2eq.framework.model.security.auth.UserManagement;
import io.quarkus.logging.Log;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.jwt.auth.principal.JWTParser;
import io.smallrye.jwt.auth.principal.ParseException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class CustomTokenAuthProvider implements AuthProvider, UserManagement {

    @ConfigProperty(name = "auth.jwt.secret")
    String secretKey;

    @ConfigProperty(name = "auth.jwt.expiration")
    Long expirationInMinutes;

    @Inject
    JWTParser jwtParser;

    private final Map<String, String> refreshTokenStore = new ConcurrentHashMap<>();

    // Add new fields for user management
    private final Map<String, UserData> userStore = new ConcurrentHashMap<>();

    private static class UserData {
        private final Set<String> roles;

        UserData(String password, Set<String> roles) {
            // Password is not used, so it's removed
            this.roles = new HashSet<>(roles);
        }
    }

    @Override
    public void createUser(String username, String password, Set<String> roles) throws SecurityException {
        if (userStore.containsKey(username)) {
            throw new SecurityException("User already exists: " + username);
        }
        userStore.put(username, new UserData(password, roles));
    }

    @Override
    public void assignRoles(String username, Set<String> roles) throws SecurityException {
        UserData userData = userStore.get(username);
        if (userData == null) {
            throw new SecurityException("User not found: " + username);
        }
        userData.roles.addAll(roles);
    }

    @Override
    public void removeRoles(String username, Set<String> roles) throws SecurityException {
        UserData userData = userStore.get(username);
        if (userData == null) {
            throw new SecurityException("User not found: " + username);
        }
        userData.roles.removeAll(roles);
    }

    @Override
    public Set<String> getUserRoles(String username) throws SecurityException {
        UserData userData = userStore.get(username);
        if (userData == null) {
            throw new SecurityException("User not found: " + username);
        }
        return new HashSet<>(userData.roles);
    }

    @Override
    public boolean userExists(String username) throws SecurityException {
        return userStore.containsKey(username);
    }

    @Override
    public LoginResponse login(String userId, String password) {
        if (!isValidCredentials(userId, password)) {
            throw new RuntimeException("Invalid credentials");
        }

        String authToken = generateAuthToken(userId);
        String refreshToken = generateRefreshToken(userId);

        SecurityIdentity identity = validateToken(authToken);

        return new LoginResponse(authToken, refreshToken, identity, identity.getRoles());
    }

    @Override
    public LoginResponse refreshTokens(String refreshToken) {
        String userId = refreshTokenStore.get(refreshToken);
        if (userId == null) {
            throw new RuntimeException("Invalid refresh token");
        }

        String newAuthToken = generateAuthToken(userId);
        String newRefreshToken = generateRefreshToken(userId);

        // Remove old refresh token and store new one
        refreshTokenStore.remove(refreshToken);

        SecurityIdentity identity = validateToken(newAuthToken);

        return new LoginResponse(newAuthToken, newRefreshToken, identity, identity.getRoles());
    }

    @Override
    public SecurityIdentity validateToken(String token) {
        try {
            var jwt = jwtParser.parse(token);
            String userId = jwt.getSubject();
            Set<String> roles = new HashSet<>(jwt.getGroups());

            SecurityIdentity identity = buildIdentity(userId, roles);
            return identity;
        } catch (ParseException e) {
            Log.error("Token validation failed", e);
            throw new SecurityException("Invalid token");
        }
    }

    private String generateAuthToken(String userId) {
        try {
            Date now = new Date();
            Date expiration = new Date(now.getTime() + (expirationInMinutes * 60 * 1000));

            String header = Base64.getUrlEncoder().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
            String payload = Base64.getUrlEncoder().encodeToString(("{\"sub\":\"" + userId + "\",\"iat\":" + now.getTime() / 1000 + ",\"exp\":" + expiration.getTime() / 1000 + "}").getBytes());

            String signature = sign(header + "." + payload, secretKey);

            return header + "." + payload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate auth token", e);
        }
    }

    private String sign(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        mac.init(secretKeySpec);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes()));
    }

    private String generateRefreshToken(String userId) {
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenStore.put(refreshToken, userId);
        return refreshToken;
    }

    private boolean isValidCredentials(String userId, String password) {
        // Implement your credential validation logic here
        return true; // Replace with actual validation
    }

    private SecurityIdentity buildIdentity(String userId, Set<String> roles) {
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder();
        builder.setPrincipal(() -> userId);
        roles.forEach(builder::addRole);

        builder.addAttribute("token_type", "custom");
        builder.addAttribute("auth_time", System.currentTimeMillis());

        return builder.build();
    }
}