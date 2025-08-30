package com.e2eq.framework.api.security;

import com.e2eq.framework.model.security.FunctionalDomain;
import com.e2eq.framework.model.security.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@QuarkusTest
public class TestRuleYaml {
   @Test
   public void testLoadRulesFromYaml() throws IOException {
      ObjectMapper mapper = new ObjectMapper( new YAMLFactory());
      CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, Rule.class);
      List<Rule> rules = mapper.readValue(new File("src/test/resources/securityRules.yaml"), listType);

      rules.forEach((r) -> {
         System.out.println("rule:" + r.getName());
      } );
   }

   @Test
   public void testLoadSecurityModelFromYaml() throws IOException {
      ObjectMapper mapper = new ObjectMapper( new YAMLFactory());
      CollectionType listType = mapper.getTypeFactory().constructCollectionType(ArrayList.class, FunctionalDomain.class);
      List<FunctionalDomain> rules = mapper.readValue(new File("src/test/resources/securityModel.yaml"), listType);

      rules.forEach((f) -> {
         if (Log.isDebugEnabled()) {
            Log.debugf("FunctionalDomain:%s" , f.getDisplayName());
            f.getFunctionalActions().forEach((fa) -> {
               Log.debugf("    Fa:%s", fa.getDisplayName());
            });
         }
      } );
   }
}
