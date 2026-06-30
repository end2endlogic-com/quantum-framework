package com.e2eq.framework.rest.services;

import com.e2eq.framework.model.auth.AuthProvider;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.auth.UserManagement;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.security.CredentialType;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.rest.models.ResetPasswordRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

@ApplicationScoped
public class AuthCredentialService {

    @Inject
    AuthProviderFactory authProviderFactory;

    @Inject
    CredentialRepo credentialRepo;

    public void resetPassword(ResetPasswordRequest request, String providerOverride) {
        validateResetPasswordRequest(request);

        String requestedProvider = providerOverride == null || providerOverride.isBlank()
                ? request.getAuthProvider()
                : providerOverride;
        UserManagement userManager = resolveUserManager(request.getUserId(), requestedProvider);
        userManager.resetPassword(
                request.getUserId(),
                request.getNewPassword(),
                Boolean.TRUE.equals(request.getForceChangePassword())
        );
    }

    public UserManagement resolveUserManager(String userId, String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            return authProviderFactory.getUserManager(requestedProvider);
        }

        Optional<CredentialUserIdPassword> credentialOptional = credentialRepo.findByUserId(userId);
        if (credentialOptional.isPresent()) {
            CredentialUserIdPassword credential = credentialOptional.get();
            if (credential.getCredentialType() != null && credential.getCredentialType() != CredentialType.PASSWORD) {
                return authProviderFactory.getUserManager();
            }

            if (credential.getIssuer() != null && !credential.getIssuer().isBlank()) {
                AuthProvider issuerProvider = authProviderFactory.getProviderForIssuer(credential.getIssuer());
                if (issuerProvider instanceof UserManagement userManagement) {
                    return userManagement;
                }
            }

            if (credential.getAuthProviderName() != null && !credential.getAuthProviderName().isBlank()) {
                return authProviderFactory.getUserManager(credential.getAuthProviderName());
            }
        }

        return authProviderFactory.getUserManager();
    }

    private void validateResetPasswordRequest(ResetPasswordRequest request) {
        if (request == null
                || request.getUserId() == null || request.getUserId().isBlank()
                || request.getNewPassword() == null || request.getNewPassword().isBlank()
                || request.getConfirmPassword() == null || request.getConfirmPassword().isBlank()) {
            throw new IllegalArgumentException("userId, newPassword, and confirmPassword are required");
        }
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match");
        }
    }
}
