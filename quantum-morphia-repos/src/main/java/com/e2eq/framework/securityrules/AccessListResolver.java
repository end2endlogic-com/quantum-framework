package com.e2eq.framework.securityrules;

import com.e2eq.framework.model.persistent.base.UnversionedBaseModel;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;

import java.util.Collection;

/**
 * SPI for resolving access-controlled lists (e.g., IDs) for a principal/resource context.
 *
 * <p>Implementations of this interface provide dynamic collections of values that can be
 * referenced in rule filter strings using the {@code ${variableName}} syntax. For example,
 * a resolver that returns {@code key() = "accessibleLocationIds"} can be used in a rule's
 * andFilterString as: {@code locationId:^[${accessibleLocationIds}]}</p>
 *
 * <b>Important: supports() Method Behavior</b>
 * <p>The {@link #supports(PrincipalContext, ResourceContext, Class)} method is called with
 * the <strong>current request's ResourceContext</strong>, not the Rule's scope. This means:</p>
 * <ul>
 *   <li>If a Rule in area "sales" uses your variable {@code ${accessibleLocationIds}}</li>
 *   <li>But your resolver's supports() only returns true for area "territory"</li>
 *   <li>The resolver will NOT be invoked when processing sales requests</li>
 *   <li>This results in an unresolved variable error at runtime</li>
 * </ul>
 *
 * <b>Best Practices for supports()</b>
 * <ul>
 *   <li><strong>Be permissive:</strong> Return true for ALL contexts where your variable
 *       might be used in a rule filter, not just the "primary" context</li>
 *   <li><strong>Consider using modelClass:</strong> Filter by model type rather than
 *       area/domain if your resolver is cross-cutting</li>
 *   <li><strong>Use wildcards:</strong> Consider accepting "*" for area/domain/action
 *       to support rules that apply broadly</li>
 *   <li><strong>Document usage:</strong> Clearly document which rule filters use your
 *       resolver's variable so supports() can be configured correctly</li>
 * </ul>
 *
 * <b>Example Implementation</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class TerritoryLocationResolver implements AccessListResolver {
 *
 *     @Override
 *     public String key() {
 *         return "accessibleLocationIds";
 *     }
 *
 *     @Override
 *     public boolean supports(PrincipalContext pctx, ResourceContext rctx,
 *                            Class<? extends UnversionedBaseModel> modelClass) {
 *         // Option 1: Support all contexts (most permissive)
 *         return true;
 *
 *         // Option 2: Support based on model class
 *         // return Location.class.isAssignableFrom(modelClass);
 *
 *         // Option 3: Support multiple areas where the variable is used
 *         // String area = rctx != null ? rctx.getArea() : null;
 *         // return "territory".equalsIgnoreCase(area)
 *         //     || "sales".equalsIgnoreCase(area)
 *         //     || "*".equals(area);
 *     }
 *
 *     @Override
 *     public Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx,
 *                                  Class<? extends UnversionedBaseModel> modelClass) {
 *         // Return the list of location IDs accessible to this principal
 *         // Return empty collection (not null) if no access
 *         return fetchAccessibleLocationIds(pctx);
 *     }
 * }
 * }</pre>
 *
 * @see com.e2eq.framework.model.persistent.morphia.MorphiaUtils.VariableBundle
 */
public interface AccessListResolver {
    /**
     * The variable key to publish into the variable bundle (e.g., "accessibleCustomerIds").
     * This key is used in rule filter strings with ${key} syntax.
     *
     * @return the variable name, without the ${} wrapper
     */
    String key();

    /**
     * Whether this resolver applies for the given context and model type.
     *
     * <p><strong>IMPORTANT:</strong> This method is called with the current request's
     * ResourceContext. If you want your variable to be available when processing rules
     * from different areas/domains, you must return true for those contexts as well.</p>
     *
     * <p>If this returns false, the variable from {@link #key()} will not be populated
     * in the variable bundle, and any rule filter referencing it will fail with an
     * "unresolved variable" error.</p>
     *
     * @param pctx the principal context (authenticated user info)
     * @param rctx the resource context (area, functionalDomain, action of the current request)
     * @param modelClass the model class being queried
     * @return true if this resolver should provide its variable for this context
     */
    boolean supports(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass);

    /**
     * Resolve the collection of values for this context and model type.
     *
     * <p>The returned collection will be available in rule filter strings via the
     * ${key} syntax, typically used in IN clauses like: {@code field:^[${key}]}</p>
     *
     * <p><strong>Best Practice:</strong> Return an empty collection rather than null
     * when the principal has no access. An empty collection in an IN clause will
     * match no documents, which is typically the desired behavior for "no access".</p>
     *
     * @param pctx the principal context
     * @param rctx the resource context
     * @param modelClass the model class being queried
     * @return collection of values (IDs, etc.) - never null, return empty collection for no access
     */
    Collection<?> resolve(PrincipalContext pctx, ResourceContext rctx, Class<? extends UnversionedBaseModel> modelClass);
}
