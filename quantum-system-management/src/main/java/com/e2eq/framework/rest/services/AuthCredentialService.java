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
        UserManagement userManager = resolvePasswordManager(request.getUserId(), requestedProvider);
        userManager.resetPassword(
                request.getUserId(),
                request.getNewPassword(),
                Boolean.TRUE.equals(request.getForceChangePassword())
        );
    }

    /**
     * The user manager for a password operation (reset, change, resend temporary password).
     * The credential type is checked before any provider override, so a caller naming a
     * provider cannot give a service-token, email or federated credential a password.
     */
    public UserManagement resolvePasswordManager(String userId, String requestedProvider) {
        Optional<CredentialUserIdPassword> credentialOptional = credentialRepo.findByUserId(userId);
        credentialOptional.ifPresent(AuthCredentialService::requirePasswordManaged);
        return resolve(credentialOptional, requestedProvider);
    }

    /**
     * The user manager for a non-password operation (e.g. change email). Service-token and
     * federated credentials are not managed here; PASSWORD and EMAIL credentials are.
     */
    public UserManagement resolveUserManager(String userId, String requestedProvider) {
        Optional<CredentialUserIdPassword> credentialOptional = credentialRepo.findByUserId(userId);
        credentialOptional.ifPresent(credential -> {
            CredentialType type = credential.getCredentialType();
            if (type != null && type != CredentialType.PASSWORD && type != CredentialType.EMAIL) {
                throw new IllegalArgumentException(String.format(
                        "Credential type %s does not support user management", type));
            }
        });
        return resolve(credentialOptional, requestedProvider);
    }

    private UserManagement resolve(Optional<CredentialUserIdPassword> credentialOptional, String requestedProvider) {
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            return authProviderFactory.getUserManager(requestedProvider);
        }

        if (credentialOptional.isPresent()) {
            CredentialUserIdPassword credential = credentialOptional.get();
            if (credential.getIssuer() != null && !credential.getIssuer().isBlank()) {
                AuthProvider issuerProvider = authProviderFactory.getProviderForIssuer(credential.getIssuer());
                if (issuerProvider instanceof UserManagement userManagement) {
                    return userManagement;
                }
                throw new IllegalArgumentException(String.format(
                        "Authentication provider for issuer '%s' does not support user management",
                        credential.getIssuer()));
            }
        }

        return authProviderFactory.getUserManager();
    }

    /**
     * Only PASSWORD credentials have passwords. (Password-based SERVICE accounts are still
     * admitted at login, so they keep password management until that is retired.)
     */
    static void requirePasswordManaged(CredentialUserIdPassword credential) {
        if (credential.getCredentialType() != null && credential.getCredentialType() != CredentialType.PASSWORD) {
            throw new IllegalArgumentException(String.format(
                    "Credential type %s does not support password management",
                    credential.getCredentialType()));
        }
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
