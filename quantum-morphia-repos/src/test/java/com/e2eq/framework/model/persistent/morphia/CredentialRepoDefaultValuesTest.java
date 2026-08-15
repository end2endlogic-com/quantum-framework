package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CredentialRepoDefaultValuesTest {

    @Test
    void defaultsMissingCredentialActiveStatusToActive() {
        TestCredentialRepo repo = new TestCredentialRepo();
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setUserId("default-status-user");
        credential.setSubject("default-status-subject");

        repo.applyDefaults(credential);

        assertNotNull(credential.getId());
        assertEquals(ActiveStatus.ACTIVE, credential.getActiveStatus());
    }

    @Test
    void preservesExplicitCredentialActiveStatus() {
        TestCredentialRepo repo = new TestCredentialRepo();
        CredentialUserIdPassword credential = new CredentialUserIdPassword();
        credential.setUserId("disabled-user");
        credential.setSubject("disabled-subject");
        credential.setActiveStatus(ActiveStatus.INACTIVE);

        repo.applyDefaults(credential);

        assertEquals(ActiveStatus.INACTIVE, credential.getActiveStatus());
    }

    static class TestCredentialRepo extends CredentialRepo {
        void applyDefaults(CredentialUserIdPassword credential) {
            setDefaultValues(credential);
        }
    }
}
