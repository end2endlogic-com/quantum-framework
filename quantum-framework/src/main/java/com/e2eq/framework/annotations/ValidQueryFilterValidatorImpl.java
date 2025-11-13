package com.e2eq.framework.annotations;

import com.e2eq.framework.grammar.BIAPIQueryLexer;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.query.ValidatingQueryToPredicateJsonListener;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/**
 * Full implementation of ValidQueryFilter validator with ANTLR parsing.
 */
@ApplicationScoped
public class ValidQueryFilterValidatorImpl implements ConstraintValidator<ValidQueryFilter, String> {
    
    private Class<?> modelClass;
    
    @Override
    public void initialize(ValidQueryFilter annotation) {
        this.modelClass = annotation.modelClass();
    }
    
    @Override
    public boolean isValid(String queryString, ConstraintValidatorContext context) {
        if (queryString == null || queryString.trim().isEmpty()) {
            return true;
        }
        
        if (!UnversionedBaseModel.class.isAssignableFrom(modelClass)) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                "modelClass must extend UnversionedBaseModel"
            ).addConstraintViolation();
            return false;
        }
        
        try {
            BIAPIQueryLexer lexer = new BIAPIQueryLexer(CharStreams.fromString(queryString));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            BIAPIQueryParser parser = new BIAPIQueryParser(tokens);
            
            @SuppressWarnings("unchecked")
            ValidatingQueryToPredicateJsonListener listener = 
                new ValidatingQueryToPredicateJsonListener((Class<? extends UnversionedBaseModel>) modelClass);
            
            ParseTreeWalker.DEFAULT.walk(listener, parser.query());
            
            if (listener.hasValidationErrors()) {
                context.disableDefaultConstraintViolation();
                for (String error : listener.getValidationErrors()) {
                    context.buildConstraintViolationWithTemplate(error)
                           .addConstraintViolation();
                }
                return false;
            }
            
            return true;
        } catch (Exception e) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                "Invalid query syntax: " + e.getMessage()
            ).addConstraintViolation();
            return false;
        }
    }
}
