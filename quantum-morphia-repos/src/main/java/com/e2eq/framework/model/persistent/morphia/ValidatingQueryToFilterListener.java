package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.grammar.BIAPIQueryParser;
import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.annotations.QueryFieldValidator;
import org.antlr.v4.runtime.Token;
import org.apache.commons.text.StringSubstitutor;

import java.util.Map;

/**
 * Extension of QueryToFilterListener that validates field references during parsing.
 * Accumulates validation errors for non-existent fields before query execution.
 */
public class ValidatingQueryToFilterListener extends QueryToFilterListener {
    private final QueryFieldValidator validator;

    public ValidatingQueryToFilterListener(Map<String, String> variableMap, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        super(variableMap, sub, modelClass);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToFilterListener(Map<String, Object> objectVars, Map<String, String> variableMap, StringSubstitutor sub, Class<? extends UnversionedBaseModel> modelClass) {
        super(objectVars, variableMap, sub, modelClass);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToFilterListener(Map<String, String> variableMap, Class<? extends UnversionedBaseModel> modelClass) {
        super(variableMap, modelClass);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToFilterListener(PrincipalContext pcontext, ResourceContext rcontext, Class<? extends UnversionedBaseModel> modelClass) {
        super(pcontext, rcontext, modelClass);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
    }

    public ValidatingQueryToFilterListener(Class<? extends UnversionedBaseModel> modelClass) {
        super(modelClass);
        this.validator = QueryFieldValidator.forModelClass(modelClass);
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
    public void enterReferenceExpr(BIAPIQueryParser.ReferenceExprContext ctx) {
        validateField(ctx.field);
        super.enterReferenceExpr(ctx);
    }

    @Override
    public void enterRegexExpr(BIAPIQueryParser.RegexExprContext ctx) {
        validateField(ctx.field);
        super.enterRegexExpr(ctx);
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
    public void enterBooleanExpr(BIAPIQueryParser.BooleanExprContext ctx) {
        validateField(ctx.field);
        super.enterBooleanExpr(ctx);
    }

    @Override
    public void enterInExpr(BIAPIQueryParser.InExprContext ctx) {
        validateField(ctx.field);
        super.enterInExpr(ctx);
    }

    @Override
    public void enterExistsExpr(BIAPIQueryParser.ExistsExprContext ctx) {
        validateField(ctx.field);
        super.enterExistsExpr(ctx);
    }

    @Override
    public void enterElemMatchExpr(BIAPIQueryParser.ElemMatchExprContext ctx) {
        validateField(ctx.field);
        super.enterElemMatchExpr(ctx);
    }
}
