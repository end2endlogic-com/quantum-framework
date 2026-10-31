package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Explicit internal fixture authority for ontology lifecycle tests, not authorization tests. */
public abstract class PrivilegedOntologyFixture {
    private SecurityCallScope.Scope fixtureScope;

    @BeforeEach
    void openFixtureAuthority() {
        SecurityContext.clear();
        fixtureScope = SecurityCallScope.openIgnoringRules();
    }

    @AfterEach
    void closeFixtureAuthority() {
        if (fixtureScope != null) fixtureScope.close();
        SecurityContext.clear();
    }
}
