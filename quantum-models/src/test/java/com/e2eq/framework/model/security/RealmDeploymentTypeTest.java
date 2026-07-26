package com.e2eq.framework.model.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealmDeploymentTypeTest {

    @Test
    void newAndNullRealmTypesResolveToDedicated() {
        Realm realm = new Realm();
        assertEquals(RealmDeploymentType.DEDICATED, realm.getDeploymentType());

        realm.setDeploymentType(null);
        assertEquals(RealmDeploymentType.DEDICATED, realm.getDeploymentType());
    }

    @Test
    void sharedMustBeExplicit() {
        Realm realm = new Realm();
        realm.setDeploymentType(RealmDeploymentType.SHARED);

        assertEquals(RealmDeploymentType.SHARED, realm.getDeploymentType());
    }
}
