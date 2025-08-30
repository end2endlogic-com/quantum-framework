package com.e2eq.framework.security;

import com.e2eq.framework.model.security.Rule;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.framework.model.securityrules.*;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.util.EnvConfigUtils;
import com.e2eq.framework.util.IOCase;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.WildCardMatcher;

import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@QuarkusTest
public class TestRuleContext extends BaseRepoTest {

    @Inject
    SecurityUtils securityUtils;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Test
    public void testWildCardMatcher() {
        // FunctionalDomain:Action:b2bi- urn ({realm}.{accountNumber}.{tenantId}.{functionalDomain}.{datasegment}.{id})
        boolean matches = WildCardMatcher.wildcardMatch("SALESORDER:UPDATE:b2bi.0000000001.end2endlogic.salesOrder.0.34534534", "salesorder:update:b2bi.0000000001.end2endlogic.salesorder.0*", IOCase.INSENSITIVE);
        if (matches) {
            Log.debug("Matches");
        } else {
            Log.error("Did not match");
        }
        assertTrue(matches);
    }

    @Test
    void testWildCardMatcherExactMatch() {
        boolean matches = WildCardMatcher.wildcardMatch("user:security:userProfile:view:b2bi:0000000001:end2endlogic.com:0", "user:security:userProfile:view:b2bi:0000000001:end2endlogic.com:0", IOCase.INSENSITIVE);
        if (matches) {
            Log.debug("Matches");
        } else {
            Log.error("Did not match");
        }
        assertTrue(matches);
    }

    @Test
    void testJavaScript() {
        String[] roles = new String[]{"user"};

        PrincipalContext systemUserIdPC = new PrincipalContext.Builder()
                .withDataDomain(testUtils.getTestDataDomain())
                .withDefaultRealm(testUtils.getTestRealm())
                .withUserId(testUtils.getTestUserId())
                .withRoles(roles)
                .build();

        ResourceContext userProfileViewRC = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .build();

        Context c = Context.newBuilder().allowAllAccess(true).build();
        c.getBindings("js").putMember("pcontext", systemUserIdPC);
        c.getBindings("js").putMember("rcontext", userProfileViewRC);

        boolean allow = c.eval("js", "rcontext.getFunctionalDomain() == 'userProfile' && rcontext.getAction() == 'view'").asBoolean();

        assertTrue(allow);

    }

    @Test
    void testPython() {

        Context.Builder builder = Context.newBuilder();
        builder.allowAllAccess(true);
        Context context = builder.build();
        String source = "import polyglot\n" +
                "@polyglot.export_value\n" +
                "def foo(externalInput):\n" +
                "    print('Called with: ' + externalInput)\n" +
                "    return 'Got output'\n\n";

        Source script = Source.create("python", source);
        context.eval(script);
        Value main = context.getPolyglotBindings().getMember("foo");
        assertTrue(main.canExecute());

        Value something = main.execute("myInput");
        assertEquals("Got output", something.asString());

    }


    @Test
    void testRuleContext() {

        // Create a rule context
        RuleContext ruleContext = new RuleContext();

        // The header is used for matching criteria.  So in this case we are matching the identity of "admin" with the
        // area security, and functional domain userProfile and action view
        SecurityURIHeader header = new SecurityURIHeader.Builder()
                .withIdentity("admin")
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .build();

        // The body then scopes this to a "then" clause so if we matched the header, then we provide the following scope
        // which in this case is anything due to all the *s
        SecurityURIBody body = new SecurityURIBody.Builder()
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withRealm("*")
                .withTenantId("*")
                .withOwnerId("*")
                .withDataSegment("*")
                .build();
        SecurityURI adminSecurityUserProfileViewURI = new SecurityURI(header, body);

        // we now add a rule that says if it matches we basically say allow access, and we provide no conditions or filters
        // os in effect it will be anything
        Rule.Builder b = new Rule.Builder()
                .withName("admin can view  any userprofile")
                .withSecurityURI(adminSecurityUserProfileViewURI)
                .withEffect(RuleEffect.ALLOW)
                .withPriority(Rule.DEFAULT_PRIORITY)
                .withFinalRule(true);
        Rule rule = b.build();

        // Now we add the rule to the context.
        ruleContext.addRule(header, rule);

        // now lets create a user rule
        header = new SecurityURIHeader.Builder()
                .withIdentity("user")
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("*")
                .build();

        // The body effects the scope  and is used in the matching to determine if a rule applies or not
        // SO here we are saying apply to any org, any account, any realm, and tenant, and any owner on any data segment
        body = new SecurityURIBody.Builder()
                .withOrgRefName("*")
                .withAccountNumber("*")
                .withRealm("*")
                .withTenantId("*")
                .withOwnerId("*")
                .withDataSegment("*")
                .build();
        SecurityURI userSecurityUserProfileViewURI = new SecurityURI(header, body);

        b.withName("only able to act on your own userProfile")
                .withSecurityURI(userSecurityUserProfileViewURI)
                .withPriority(Rule.DEFAULT_PRIORITY + 11)
                // this would imply that the rule is only applied under this condition
                // reality is we want to apply the rule in every case where the header matches ie its a user rule
                // so the filter is then appropriately applied
                .withPostconditionScript("pcontext.getUserId() == rcontext.getOwnerId()")
                .withAndFilterString("dataDomain.ownerId:${principalId}");
        rule = b.build();
        ruleContext.addRule(userSecurityUserProfileViewURI.getHeader(), rule);


        // Create two rules one for an admin one for a user
        String[] allRoles = new String[]{"user", "admin"};
        String[] adminRole = new String[]{"admin"};
        String[] userRole = new String[]{"user"};

        // Create a principal context with admin and user roles
        PrincipalContext systemUserIdPC = new PrincipalContext.Builder()
                .withDefaultRealm(testUtils.getTestRealm())
                .withUserId(testUtils.getSystemUserId())
                .withRoles(adminRole)
                .withDataDomain(testUtils.getSystemDataDomain())
                .build();

        PrincipalContext mingardiaUserIdPC = new PrincipalContext.Builder()
                .withDefaultRealm(testUtils.getTestRealm())
                .withUserId(testUtils.getTestUserId())
                .withRoles(userRole)
                .withDataDomain(testUtils.getSystemDataDomain())
                .build();

        // Create a resource context in the area of security and fd of userProfile and an action of view of the systemUserId
        ResourceContext sysAdminUserProfileRC = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .withResourceId("234232")
                .withOwnerId("sysAdmin@system-com")
                .build();

        ResourceContext mingardiaUserProfileRC = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .withResourceId("234233")
                .withOwnerId(testUtils.getTestUserId())
                .build();

        List<Filter> filters = new ArrayList<>();

        // So lets first check that an admin can view their own userProfile
        SecurityCheckResponse checkRulesResponse = ruleContext.checkRules(systemUserIdPC, sysAdminUserProfileRC);
        List<Filter> filters1 = ruleContext.getFilters(filters, systemUserIdPC, sysAdminUserProfileRC, UserProfile.class);
        logRuleResults("Testing system admin can view the system admin user profile with a default deny context",
                systemUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters1);
        assertTrue(checkRulesResponse.getMatchedRuleResults().size() == 1);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));
        assertTrue(filters1.isEmpty());

        // Now lets see if the user is denied trying to view the system's profile
        Log.info("Test if mingardia is denied trying to view the system's profile with a default deny context'");
        checkRulesResponse = ruleContext.checkRules(mingardiaUserIdPC, sysAdminUserProfileRC);
        filters.clear();
        filters1 = ruleContext.getFilters(filters, mingardiaUserIdPC, sysAdminUserProfileRC, UserProfile.class);
        logRuleResults("Test if mingardia is denied trying to view the system's profile with a default deny context'",
                mingardiaUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters1);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.DENY));
        assertTrue(filters1.isEmpty()); // will be empty because the post condition will cause the rule to be ignored / NA and the default is deny

        // show that its really about the default not an explicit rule
        checkRulesResponse = ruleContext.checkRules(mingardiaUserIdPC, sysAdminUserProfileRC, RuleEffect.ALLOW);
        filters.clear();
        filters1 = ruleContext.getFilters(filters, systemUserIdPC, sysAdminUserProfileRC, UserProfile.class);
        logRuleResults("show that its really about the default not an explicit rule",
                mingardiaUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters1);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));

        // now lets see if they can see our own
        // since we are assuming the default deny is applied only if there is a rule that allows it
        // should it be allowed.  The only rule that will has a post condition that has to evaluate to true
        checkRulesResponse = ruleContext.checkRules(mingardiaUserIdPC, mingardiaUserProfileRC); // default deny
        filters.clear();
        filters1 = ruleContext.getFilters(filters, mingardiaUserIdPC, mingardiaUserProfileRC, UserProfile.class);
        logRuleResults("show that its really about the default not an explicit rule",
                mingardiaUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters1);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));


    }

    @Test
    public void testDefaultRuleContext() {
        // Create two rules one for an admin one for a user
        String[] allRoles = new String[]{"user", "admin"};
        String[] adminRole = new String[]{"admin"};
        String[] userRole = new String[]{"user"};

        // Create a principal context with admin and user roles
        PrincipalContext systemUserIdPC = new PrincipalContext.Builder()
                .withDefaultRealm(testUtils.getTestRealm())
                .withUserId(testUtils.getSystemUserId())
                .withRoles(adminRole)
                .withDataDomain(testUtils.getSystemDataDomain())
                .build();

        PrincipalContext mingardiaUserIdPC = new PrincipalContext.Builder()
                .withDefaultRealm(testUtils.getTestRealm())
                .withUserId(testUtils.getTestUserId())
                .withRoles(userRole)
                .withDataDomain(testUtils.getTestDataDomain())
                .build();

        // Create a resource context in the area of security and fd of userProfile and an action of view of the systemUserId
        ResourceContext sysAdminUserProfileRC = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .withResourceId("234232")
                .withOwnerId(testUtils.getSystemUserId())
                .build();

        ResourceContext mingardiaUserProfileRC = new ResourceContext.Builder()
                .withArea("security")
                .withFunctionalDomain("userProfile")
                .withAction("view")
                .withResourceId("234233")
                .withOwnerId(testUtils.getTestTenantId())
                .build();

        List<Filter> filters = new ArrayList<>();

        RuleContext ruleContext = new RuleContext(securityUtils, envConfigUtils);



        // initialize with the default rules in place
        ruleContext.ensureDefaultRules();

        // test that an admin can view system admins profile.
        SecurityCheckResponse checkRulesResponse = ruleContext.checkRules(systemUserIdPC, sysAdminUserProfileRC);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));
        filters = ruleContext.getFilters(filters, systemUserIdPC, sysAdminUserProfileRC, UserProfile.class);
        logRuleResults("Testing system admin can view the system admin user profile with a default deny context",
                systemUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters);
        assertTrue(!filters.isEmpty());

        // test that the admin can view the mingardia user profile.
        filters.clear();
        checkRulesResponse = ruleContext.checkRules(systemUserIdPC, mingardiaUserProfileRC);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));
        filters = ruleContext.getFilters(filters, systemUserIdPC, sysAdminUserProfileRC, UserProfile.class);
        logRuleResults("Testing system admin can view the system admin user profile with a default deny context",
                systemUserIdPC,
                sysAdminUserProfileRC,
                checkRulesResponse,
                filters);
        assertTrue(!filters.isEmpty());

        //test that use user can view their own profile
        filters.clear();
        checkRulesResponse = ruleContext.checkRules(mingardiaUserIdPC, mingardiaUserProfileRC);
        assertTrue(checkRulesResponse.getFinalEffect().equals(RuleEffect.ALLOW));
        filters = ruleContext.getFilters(filters, mingardiaUserIdPC, mingardiaUserProfileRC, UserProfile.class);
        logRuleResults("Testing system admin can view the system admin user profile with a default deny context",
                mingardiaUserIdPC,
                mingardiaUserProfileRC,
                checkRulesResponse,
                filters);
        assertTrue(!filters.isEmpty());
    }

    public void logRuleResults(String statement, PrincipalContext principalContext, ResourceContext resourceContext, SecurityCheckResponse securityCheckResponse, List<Filter> filters) {
        Log.info("---------------------------------------------------------");
        Log.info(statement);
        Log.info("Principal:" + principalContext.toString());
        Log.info("Resource:" + resourceContext.toString());
        Log.info("Matched rules:" + securityCheckResponse.getMatchedRuleResults().size());
        Log.info("Final Effect:" + securityCheckResponse.getFinalEffect());
        Log.info("Match Results:");
        securityCheckResponse.getMatchEvents().forEach(m -> Log.info(m.toString()));
        if (filters.isEmpty()) Log.info("NoFilters");
        else {
            Log.info("**** Filters:");
            filters.forEach(f -> Log.info(f.toString()));
        }
    }


}
