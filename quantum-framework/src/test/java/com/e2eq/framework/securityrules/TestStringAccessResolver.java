package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.persistent.morphia.StringLiteral;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Arrays;
import java.util.Collection;

/**
 * Test resolver publishing a list of StringLiteral values under key "accessibleCustomerCodes".
 * One of the values looks like a 24-hex ObjectId, but should be treated as a plain string
 * due to the StringLiteral wrapper.
 */
@ApplicationScoped
public class TestStringAccessResolver implements AccessListResolver {

    public static final String HEX_LIKE = "5f1e1a5e5e5e5e5e5e5e5e51"; // looks like ObjectId but must remain string
    public static final String CODE_2 = "CUST-42";

    @Override
    public String key() {
        return "accessibleCustomerCodes";
    }

    @Override
    public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        return rctx != null
                && "sales".equalsIgnoreCase(rctx.getArea())
                && "order".equalsIgnoreCase(rctx.getFunctionalDomain())
                && "view".equalsIgnoreCase(rctx.getAction());
    }

    @Override
    public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        return Arrays.asList(StringLiteral.of(HEX_LIKE), StringLiteral.of(CODE_2));
    }
}
