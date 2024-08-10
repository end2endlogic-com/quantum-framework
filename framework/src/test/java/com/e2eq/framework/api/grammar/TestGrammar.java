package com.e2eq.framework.api.grammar;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.security.PrincipalContext;
import com.e2eq.framework.model.security.ResourceContext;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class TestGrammar {
      @Inject
      Validator validator;

   @Test
   public void testGrammarSyntax() throws IOException {
      FileInputStream fstream = new FileInputStream("src/test/resources/testQueryStrings.txt");
      BufferedReader reader = new BufferedReader(new InputStreamReader(fstream));
      String line;
      int lcount = 1;
      while ((line = reader.readLine()) != null) {
         // remove leading and trailing blanks
         line = line.trim();

         // ignore comments and empty lines
         if (line.startsWith("#") || line.isEmpty()) {
            lcount++;
            continue;
         }

         com.e2eq.framework.grammar.BIAPIQueryLexer lexer = new com.e2eq.framework.grammar.BIAPIQueryLexer(CharStreams.fromString(line));
         com.e2eq.framework.grammar.BIAPIQueryParser parser = new com.e2eq.framework.grammar.BIAPIQueryParser(new CommonTokenStream(lexer));
         final int fline = lcount;
          parser.addErrorListener(new BaseErrorListener() {
             @Override
             public void syntaxError (Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                      int charPositionInLine, String msg, RecognitionException e) {
                throw new IllegalStateException("failed to parse at line " + fline + " due to " + msg, e);
             }
          });
         ParseTree tree = parser.query();
         lcount++;
      }

      Log.info("Parsed:" + lcount + " queries successfully");

   }


   @Test
   public void testFilterGeneration() throws IOException {
      try (FileInputStream fstream = new FileInputStream("src/test/resources/testQueryStrings.txt")) {

         try (BufferedReader reader = new BufferedReader(new InputStreamReader(fstream))) {
            String line;
            int lcount = 1;
            while ((line = reader.readLine()) != null) {
               // remove leading and trailing blanks
               line = line.trim();

               // ignore comments and empty lines
               if (line.startsWith("#") || line.isEmpty()) {
                  continue;
               }

               Filter f = MorphiaUtils.convertToFilter(line);
               lcount++;
            }
         }
      }
   }


   @Test
   public void testFilterWVariableGeneration() throws IOException {
      String[] roles = {"user"};

      DataDomain dataDomain= TestUtils.dataDomain;


      PrincipalContext pcontext = new PrincipalContext.Builder()
                                     .withDataDomain(dataDomain)
                                     .withDefaultRealm("defaultRealm")
                                     .withRoles(roles)
                                     .withScope("systemGenerated")
                                     .withUserId("userId").build();
      ResourceContext rcontext = new ResourceContext.Builder()
                                    .withFunctionalDomain("functionalDomain")
                                    .withAction("action")
                                    .withResourceId("rId123232").build();

      Set<ConstraintViolation<PrincipalContext>> pviolations =  validator.validate(pcontext);
      assertTrue(pviolations.isEmpty());
      Set<ConstraintViolation<ResourceContext>> rviolations =  validator.validate(rcontext);
      assertTrue(rviolations.isEmpty());


      String queryString = "field1:${action}";
      Filter f = MorphiaUtils.convertToFilterWContext(queryString, pcontext, rcontext);
      Log.info("Value:" + f.getValue());
      assertEquals("action", f.getValue());

      queryString = "field1:^[testval1,${action}]";
      f = MorphiaUtils.convertToFilterWContext(queryString, pcontext, rcontext);
      Log.info("Value:" + f.getValue());
      assertEquals("[testval1, action]", f.getValue().toString());

   }
}