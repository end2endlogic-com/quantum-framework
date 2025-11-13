package com.e2eq.framework.query;

import com.e2eq.framework.annotations.QueryFieldValidator;
import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.BaseModel;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryFieldValidatorTest {

    static class TestModel extends BaseModel {
        private String name;
        private String email;
        private Integer age;
        private TestAddress address;

       @Override
       public String bmFunctionalArea () {
          return "TST";
       }

       @Override
       public String bmFunctionalDomain () {
          return "TST";
       }
    }

    static class TestAddress {
        private String city;
        private String zipCode;
    }

    @Test
    void testValidFieldsPass() {
        QueryFieldValidator validator = QueryFieldValidator.forModelClass(TestModel.class);

        assertTrue(validator.validateField("name"));
        assertTrue(validator.validateField("email"));
        assertTrue(validator.validateField("age"));
        assertTrue(validator.validateField("address"));
        assertFalse(validator.hasErrors());
    }

    @Test
    void testInvalidFieldsFail() {
        QueryFieldValidator validator = QueryFieldValidator.forModelClass(TestModel.class);

        assertFalse(validator.validateField("nonExistentField"));
        assertTrue(validator.hasErrors());
        assertEquals(1, validator.getErrors().size());
        assertTrue(validator.getErrors().get(0).contains("nonExistentField"));
    }

    @Test
    void testValidatingListenerDetectsInvalidFields() {
        String query = "name:John && invalidField:test";

        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(query));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);

        ValidatingQueryToPredicateJsonListener listener = new ValidatingQueryToPredicateJsonListener(TestModel.class);
        ParseTreeWalker.DEFAULT.walk(listener, parser.query());

        assertTrue(listener.hasValidationErrors());
        assertTrue(listener.getValidationErrors().stream()
                .anyMatch(e -> e.contains("invalidField")));
    }

    @Test
    void testValidatingListenerPassesValidFields() {
        String query = "name:John AND email:test@example.com";

        BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(query));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        BIAPIQueryParser parser = new BIAPIQueryParser(tokens);

        ValidatingQueryToPredicateJsonListener listener = new ValidatingQueryToPredicateJsonListener(TestModel.class);
        ParseTreeWalker.DEFAULT.walk(listener, parser.query());

        assertFalse(listener.hasValidationErrors());
    }
}
