package auth;

import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;

import com.e2eq.framework.security.runtime.RuleContext;
import com.e2eq.framework.util.TestUtils;
import io.quarkus.logging.Log;
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
        Log.infof("[Test Bootstrap] AWS region  variable set to %s; AWS profile variable set to %s",
                System.getProperty("aws.region", System.getProperty("AWS_REGION")),
                System.getProperty("aws.profile", System.getProperty("AWS_PROFILE"))
        );

        ruleContext.ensureDefaultRules();
        pContext = testUtils.getTestPrincipalContext(testUtils.getSystemUserId(), roles);
        rContext = testUtils.getResourceContext(testUtils.getArea(), "userProfile", "update");
        ruleContext.initDefaultRules( "security","userProfile", testUtils.getTestUserId());
    }

    private void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
            System.setProperty(key, value);
        }
    }

    /* @BeforeEach
    protected void setUp() {


    }

    @AfterEach
    void tearDown() {

    } */
}
