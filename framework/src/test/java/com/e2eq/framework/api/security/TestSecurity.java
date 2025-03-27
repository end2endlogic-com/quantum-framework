package com.e2eq.framework.api.security;

//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import com.e2eq.framework.model.securityrules.RuleContext;
import com.e2eq.framework.model.persistent.security.Rule;
import com.e2eq.framework.util.IOCase;
import com.e2eq.framework.util.SecurityUtils;
import com.e2eq.framework.util.TestUtils;
import com.e2eq.framework.util.WildCardMatcher;
import com.e2eq.framework.model.securityrules.*;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.tools.Script;
import org.projectnessie.cel.tools.ScriptException;
import org.projectnessie.cel.tools.ScriptHost;
import org.projectnessie.cel.types.jackson.JacksonRegistry;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
*/




@QuarkusTest
public class TestSecurity {


   @Inject
   SecurityUtils securityUtils;

   @Inject
   TestUtils testUtils;
   @Test
   public void testWildCardMatcher () {
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
   void testWildCardMatcherExactMatch () {
      boolean matches = WildCardMatcher.wildcardMatch("user:secuirty:userProfile:view:b2bi:0000000001:end2endlogic.com:0", "user:secuirty:userProfile:view:b2bi:0000000001:end2endlogic.com:0", IOCase.INSENSITIVE);
      if (matches) {
         Log.debug("Matches");
      } else {
         Log.error("Did not match");
      }
      assertTrue(matches);
   }

   @Test
   void testJavaScript () {
      String[] roles = new String[]{"user"};

      PrincipalContext pcontext = new PrincipalContext.Builder()
         .withDataDomain(testUtils.getDataDomain())
         .withDefaultRealm(securityUtils.getSystemRealm())
         .withUserId(testUtils.getSystemUserId())
         .withRoles(roles)
         .build();

      ResourceContext rcontext = new ResourceContext.Builder()
         .withArea("security")
         .withFunctionalDomain("userProfile")
         .withAction("view")
         .build();

      Context c = Context.newBuilder().allowAllAccess(true).build();
      c.getBindings("js").putMember("pcontext", pcontext);
      c.getBindings("js").putMember("rcontext", rcontext);

      boolean allow = c.eval("js", "rcontext.getFunctionalDomain() == 'userProfile' && rcontext.getAction() == 'view'").asBoolean();

      assertTrue(allow);

   }

  // @Test
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
   void testRuleContext () {
      String[] roles = new String[]{"user", "admin"};

      PrincipalContext pcontext = new PrincipalContext.Builder()
         .withDefaultRealm(securityUtils.getTestRealm())
         .withUserId(testUtils.getSystemUserId())
         .withRoles(roles)
         .withDataDomain(testUtils.getDataDomain())
         .build();
      ResourceContext rcontext = new ResourceContext.Builder()
         .withArea("security")
         .withFunctionalDomain("userProfile")
         .withAction("view")
         .build();

      RuleContext ruleContext = new RuleContext();

      SecurityURIHeader header = new SecurityURIHeader.Builder()
         .withIdentity("user")
         .withArea("security")
         .withFunctionalDomain("userProfile")
         .withAction("view")
         .build();
      SecurityURIBody body = new SecurityURIBody.Builder()
         .withOrgRefName(securityUtils.getSystemOrgRefName())
         .withAccountNumber(securityUtils.getSystemAccountNumber())
         .withRealm(securityUtils.getSystemRealm())
         .withTenantId(securityUtils.getSystemTenantId())
         .withOwnerId("*")
         .withDataSegment("*")
         .build();
      SecurityURI uri = new SecurityURI(header, body);

      // SecurityURI = principal:area:fd:action:account:realm:tenantId:ownerId:dataSegment:resource

      // the rule will be: -> user:security:userProfile:view:000000001:b2bi.end2endlogic.com:*.0.*
      // view userProfile on account 00000001 in realm b2bi tenantId end2endlogic.com for any owner in data segment 0 on any resourceId


      Rule.Builder b = new Rule.Builder()
         .withName("view your own userprofile")
         .withSecurityURI(uri)
         .withPostconditionScript("pcontext.getUserId() == rcontext.getResourceOwnerId()")
         .withEffect(RuleEffect.ALLOW)
         .withFinalRule(true);
      Rule r = b.build();
      SecurityURIHeader key = header.clone();

      ruleContext.addRule(key, r);

      uri = uri.clone();
      uri.getHeader().setFunctionalDomain("credential");
      uri.getBody().setOwnerId("mingardia@end2endlogic.com");
      uri.getHeader().setAction("update");
      b.withName("change your own credential")
         .withSecurityURI(uri)
         .withPriority(5);

      r = b.build();
      ruleContext.addRule(uri.getHeader(), r);


      uri = uri.clone();
      uri.getHeader().setFunctionalDomain("userProfile");
      uri.getHeader().setIdentity("admin");
      uri.getBody().setOwnerId("*");
      uri.getHeader().setAction("view");

      b.withName("admins can see userProfiles")
         .withSecurityURI(uri)
         .withPostconditionScript(null)
         .withPriority(5);
      r = b.build();
      ruleContext.addRule(uri.getHeader(), r);

      uri = uri.clone();

      uri.getHeader().setIdentity("admin");
      uri.getBody().setOwnerId("*");
      uri.getHeader().setAction("update");
      b.withName("admins can change credentials")
         .withSecurityURI(uri)
         .withPriority(5);
      r = b.build();
      ruleContext.addRule(uri.getHeader(), r);

      SecurityCheckResponse rc = ruleContext.checkRules(pcontext, rcontext);

      if (rc.getFinalEffect() == RuleEffect.ALLOW) {
         Log.debug("Allowed");
      } else {
         Log.debug("Denied");
      }

      assertTrue(rc.getFinalEffect() == RuleEffect.ALLOW);
   }


}
