package com.e2eq.framework.api.grammar;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaUtils;
import com.e2eq.framework.model.persistent.security.UserProfile;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.util.TestUtils;
import dev.morphia.query.filters.Filter;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class TestGrammar {
      @Inject
      Validator validator;

      @ConfigProperty(name = "quantum.realm.testRealm", defaultValue = "test-quantum-com")
      String testRealm;

      @Inject
      TestUtils testUtils;

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
         if (Log.isDebugEnabled())
            Log.debug("Processing query: " + line);

         BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(line));
         BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
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

               Log.info("Processing query: " + line);

               Filter f = MorphiaUtils.convertToFilter(line, UserProfile.class);
               Log.info("Filter:" + f.toString());
               lcount++;
            }
         }
      }
   }
   @Test
   public void testIndividualString() {
      //String testString = "field:\"test:123\"*";
      String testString = "field:^[67340babd762702b5c6fd57f]";
      Log.info("Testing String:" + testString);
      Filter f = MorphiaUtils.convertToFilter(testString, UserProfile.class);
      Log.info("Filter:" + f.toString());
   }


   @Test
   public void testFilterWVariableGeneration()  {
      String[] roles = {"user"};

      DataDomain dataDomain= testUtils.getDataDomain();


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
      Filter f = MorphiaUtils.convertToFilterWContext(queryString, pcontext, rcontext, UserProfile.class);
      Log.debugf("Value:%s" , f.getValue());
      assertEquals("action", f.getValue());

      queryString = "field1:^[testval1,${action}]";
      f = MorphiaUtils.convertToFilterWContext(queryString, pcontext, rcontext, UserProfile.class);
      Log.debugf("Value:%s" + f.getValue());
      assertEquals("[testval1, action]", f.getValue().toString());

   }


   @Test
   public void testDateFilter() {
      String queryString = "field1:>=2022-01-01";
      Filter f = MorphiaUtils.convertToFilter(queryString, UserProfile.class);
      Log.infof("Value:%s", f.getValue());

      String dateTimeString = "field1:>2011-12-03T10:15:30Z";
      DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;

      f = MorphiaUtils.convertToFilter(dateTimeString, UserProfile.class);
      Log.infof("Value:%s", f.getValue());

      String dateTimeTsString = "field1:>2022-01-01T12:00:00-06:00";
      f = MorphiaUtils.convertToFilter(dateTimeTsString, UserProfile.class);
      Log.infof("Value:%s", f.getValue());

      dateTimeTsString = "field1:>2022-01-01T12:00:00+06:00";
      f = MorphiaUtils.convertToFilter(dateTimeTsString, UserProfile.class);
      Log.infof("Value:%s", f.getValue());

      dateTimeTsString = "field1:>2022-01-01T12:00:00Z";
      f = MorphiaUtils.convertToFilter(dateTimeTsString, UserProfile.class);
      Log.infof("Value:%s", f.getValue());
   }
}
