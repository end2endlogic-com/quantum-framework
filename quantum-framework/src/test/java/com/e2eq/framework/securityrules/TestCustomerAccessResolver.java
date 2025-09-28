package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.types.ObjectId;

import java.util.Arrays;
import java.util.Collection;

/**
 * Test resolver that publishes a fixed list of customerIds for use in rules via ${accessibleCustomerIds}.
 */
@ApplicationScoped
public class TestCustomerAccessResolver implements AccessListResolver {

    public static final ObjectId ID1 = new ObjectId("5f1e1a5e5e5e5e5e5e5e5e51");
    public static final ObjectId ID2 = new ObjectId("5f1e1a5e5e5e5e5e5e5e5e52");

    @Override
    public String key() {
        return "accessibleCustomerIds";
    }

    @Override
    public boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        // Only apply to sales/order VIEW to keep test scope tight
        return rctx != null
                && "sales".equalsIgnoreCase(rctx.getArea())
                && "order".equalsIgnoreCase(rctx.getFunctionalDomain())
                && "view".equalsIgnoreCase(rctx.getAction());
    }

    @Override
    public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass) {
        // Return a fixed set of ObjectIds; the listener should pass these through as typed values in $in
        return Arrays.asList(ID1, ID2);
    }
}
