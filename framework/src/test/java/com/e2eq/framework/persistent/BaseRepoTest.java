package com.e2eq.framework.persistent;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTest
public class BaseRepoTest {

    @Inject
    protected TestUtils testUtils;

    @Inject
    protected RuleContext ruleContext;

    protected String[] roles = {"admin", "user"};
    protected PrincipalContext pContext;
    protected ResourceContext rContext;

    @PostConstruct
    void init() {
        ruleContext.ensureDefaultRules();
        pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
        testUtils.initDefaultRules(ruleContext, "security","userProfile", testUtils.getTestUserId());
    }


    /* @BeforeEach
    protected void setUp() {


    }

    @AfterEach
    void tearDown() {

    } */
}
