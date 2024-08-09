package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar .BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.security.PrincipalContext;
import com.e2eq.framework.model.security.ResourceContext;
import dev.morphia.query.filters.Filter;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.commons.text.StringSubstitutor;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class MorphiaUtils {

   public static Filter convertToFilter(String queryString) {
      return MorphiaUtils.convertToFilterWContext(queryString, null, null);
   }

   public static Map<String, String> createVariableMapFrom(PrincipalContext pcontext, ResourceContext rcontext) {
      Map<String, String> variableMap = new HashMap<>();
      variableMap.put("principalId", pcontext.getUserId());
      variableMap.put("pAccountId", pcontext.getDataDomain().getAccountNum());
      variableMap.put("pTenantId", pcontext.getDataDomain().getTenantId());
      variableMap.put("ownerId", pcontext.getDataDomain().getOwnerId());
      variableMap.put("orgRefName", pcontext.getDataDomain().getOrgRefName());

      if (rcontext.getResourceId().isPresent()) {
         variableMap.put("resourceId", rcontext.getResourceId().get());
      }
      variableMap.put("action", rcontext.getAction());
      variableMap.put("functionalDomain", rcontext.getFunctionalDomain());
      variableMap.put("area", rcontext.getArea());
      return variableMap;
   }


   public static Filter convertToFilter(String queryString, @NotNull Map<String, String> variableMap, StringSubstitutor sub) {
      if (queryString != null && !queryString.isEmpty()) {
         BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
         BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
         parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError (Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                     int charPositionInLine, String msg, RecognitionException e) {
               throw new IllegalArgumentException("Failed to parse " + queryString + " at position "
                                                     + charPositionInLine + " due to " + msg, e);
            }
         });
         ParseTree tree = parser.query();
         ParseTreeWalker walker = new ParseTreeWalker();
         QueryToFilterListener listener;
         if (sub == null) {
            listener = new QueryToFilterListener(variableMap);
         } else {
            listener = new QueryToFilterListener(variableMap, sub);
         }
         walker.walk(listener, tree);
         return listener.getFilter();
      }
      else {
         throw new IllegalArgumentException("Null or empty query string is not valid");
      }
   }


   public static Filter convertToFilterWContext(String queryString, PrincipalContext pcontext,  ResourceContext rcontext) {

      if (queryString != null && !queryString.isEmpty()) {
         BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
         BIAPIQueryParser parser = new BIAPIQueryParser(new CommonTokenStream(lexer));
         parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError (Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                     int charPositionInLine, String msg, RecognitionException e) {
               throw new IllegalArgumentException("syntax error in query string: Failed to parse " + queryString + " at position "
                                                     + charPositionInLine + " due to " + msg, e);
            }
         });
         ParseTree tree = parser.query();
         ParseTreeWalker walker = new ParseTreeWalker();

         QueryToFilterListener listener;
         if (rcontext != null && pcontext != null) {
            listener = new QueryToFilterListener(pcontext, rcontext);
         } else {
            listener = new QueryToFilterListener();
         }
         walker.walk(listener, tree);
         return listener.getFilter();
      }
      else {
         throw new IllegalArgumentException("Null or empty query string is not valid");
      }

   }
}
