package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.rest.requests.CreateUserRequest;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserProfileCreationIdentityTest {
    @Test
    void suppliedLoginIdIsPreservedWhenEmailDiffers() {
        var repo = mock(UserProfileRepo.class);
        when(repo.getByUserId("journey-user")).thenReturn(Optional.empty());
        when(repo.save(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));
        var credentials = mock(CredentialRepo.class);
        var credential = new CredentialUserIdPassword();
        credential.setId(new ObjectId());
        credential.setRefName("journey-user");
        credential.setDisplayName("Journey User");
        credential.setUserId("journey-user");
        credential.setSubject("journey-subject");
        when(credentials.findByUserId("journey-user")).thenReturn(Optional.of(credential));
        var resource = new UserProfileResource(repo);
        resource.credentialRepo = credentials;
        resource.authProviderFactory = mock(AuthProviderFactory.class, RETURNS_DEEP_STUBS);
        var request = new CreateUserRequest();
        request.setUserId("journey-user");
        request.setEmail("contact@example.invalid");
        request.setDisplayName("Journey User");
        request.setPassword("disposable-test-password");
        request.setForceChangePassword(true);
        var response = resource.create(request);
        assertEquals(200, response.getStatus());
        var profile = (UserProfile) response.getEntity();
        assertEquals("journey-user", profile.getUserId());
        assertEquals("journey-user", profile.getRefName());
        assertEquals("contact@example.invalid", profile.getEmail());
        assertEquals(credential.getId(), profile.getCredentialUserIdPasswordRef().getEntityId());
    }
}
