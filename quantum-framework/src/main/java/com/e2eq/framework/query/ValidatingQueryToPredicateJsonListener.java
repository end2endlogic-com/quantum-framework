package com.e2eq.framework.query;

import com.e2eq.framework.annotations.QueryFieldValidator;
import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import org.antlr.v4.runtime.Token;
import org.apache.commons.text.StringSubstitutor;

import java.util.Map;
import java.util.Set;

/**
 * Extension of QueryToPredicateJsonListener that validates field references during parsing.
 * Accumulates validation errors for non-existent fields before query execution.
 */
public class ValidatingQueryToPredicateJsonListener extends QueryToPredicateJsonListener {
    private final QueryFieldValidator validator;

    public ValidatingQueryToPredicateJsonListener(Class<? extends UnversionedBaseModel> modelClass) {
        super();
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToPredicateJsonListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
        super(variableMap);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToPredicateJsonListener(Map<String, String> variableMap, Map<String, Object> objectVars, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        super(variableMap, objectVars, sub);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToPredicateJsonListener(Set<String> validFields) {
        super();
        this.validator = QueryFieldValidator.forFields(validFields);
    }

    public ValidatingQueryToPredicateJsonListener(Map<String, String> variableMap, Set<String> validFields) {
        super(variableMap);
        this.validator = QueryFieldValidator.forFields(validFields);
    }

    /**
     * Returns true if validation errors were found.
     */
    public boolean hasValidationErrors() {
        return validator.hasErrors();
    }

    /**
     * Returns list of validation error messages.
     */
    public java.util.List<String> getValidationErrors() {
        return validator.getErrors();
    }

    private void validateField(Token fieldToken) {
        if (fieldToken != null) {
            validator.validateField(fieldToken.getText());
        }
    }

    @Override
    public void enterNullExpr(BIAPIQueryParser.NullExprContext ctx) {
        validateField(ctx.field);
        super.enterNullExpr(ctx);
    }

    @Override
    public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        validateField(ctx.field);
        super.enterExistsExpr(ctx);
    }

    @Override
    public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        validateField(ctx.field);
        super.enterBooleanExpr(ctx);
    }

    @Override
    public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        validateField(ctx.field);
        super.enterRegexExpr(ctx);
    }

    @Override
    public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        validateField(ctx.field);
        super.enterInExpr(ctx);
    }

    @Override
    public void enterStringExpr(BIAPIQueryParser.StringExprContext ctx) {
        validateField(ctx.field);
        super.enterStringExpr(ctx);
    }

    @Override
    public void enterQuotedExpr(BIAPIQueryParser.QuotedExprContext ctx) {
        validateField(ctx.field);
        super.enterQuotedExpr(ctx);
    }

    @Override
    public void enterNumberExpr(BIAPIQueryParser.NumberExprContext ctx) {
        validateField(ctx.field);
        super.enterNumberExpr(ctx);
    }

    @Override
    public void enterWholenumberExpr(BIAPIQueryParser.WholenumberExprContext ctx) {
        validateField(ctx.field);
        super.enterWholenumberExpr(ctx);
    }

    @Override
    public void enterDateTimeExpr(BIAPIQueryParser.DateTimeExprContext ctx) {
        validateField(ctx.field);
        super.enterDateTimeExpr(ctx);
    }

    @Override
    public void enterDateExpr(BIAPIQueryParser.DateExprContext ctx) {
        validateField(ctx.field);
        super.enterDateExpr(ctx);
    }

    @Override
    public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) {
        validateField(ctx.field);
        super.enterReferenceExpr(ctx);
    }

    @Override
    public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        validateField(ctx.field);
        super.enterElemMatchExpr(ctx);
    }
}
