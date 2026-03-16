package com.e2eq.framework.security.runtime;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleScriptExecutorTest {

    @Test
    void hardenedModeExposesSafeContextViews() {
        System.setProperty("quantum.security.scripting.enabled", "true");
        System.setProperty("quantum.security.scripting.allowAllAccess", "false");

        PrincipalContext principalContext = new PrincipalContext.Builder()
                .withDefaultRealm("system-com")
                .withDataDomain(new DataDomain("org", "acct", "tenant", 0, "alice@example.com"))
                .withUserId("alice@example.com")
                .withRoles(new String[]{"admin"})
                .withScope("access")
                .build();
        ResourceContext resourceContext = new ResourceContext.Builder()
                .withRealm("system-com")
                .withArea("ops")
                .withFunctionalDomain("orders")
                .withAction("view")
                .withResourceId("order-1")
                .withOwnerId("alice@example.com")
                .build();

        boolean result = new RuleScriptExecutor(null).runScript(1500L, principalContext, resourceContext,
                "typeof pcontext.getClass === 'undefined'"
                        + " && typeof rcontext.getClass === 'undefined'"
                        + " && pcontext.userId === 'alice@example.com'"
                        + " && pctx.userId === 'alice@example.com'"
                        + " && rcontext.area === 'ops'"
                        + " && rctx.action === 'view'"
                        + " && identityInfo.userId === 'alice@example.com'");

        assertTrue(result);
    }
}
