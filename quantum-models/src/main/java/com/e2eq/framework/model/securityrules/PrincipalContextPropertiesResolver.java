package com.e2eq.framework.model.securityrules;

import java.util.Map;

/**
 * SPI for contributing custom properties to PrincipalContext.
 *
 * <p>Implementations are discovered via CDI and invoked during
 * SecurityFilter.determinePrincipalContext() to enrich the
 * PrincipalContext with application-specific attributes.</p>
 *
 * <p>Properties are made available in policy rules via ${propertyName} syntax
 * in andFilterString/orFilterString fields.</p>
 *
 * <b>Example Implementation:</b>
 * <pre>{@code
 * @ApplicationScoped
 * public class AssociatePropertiesResolver implements PrincipalContextPropertiesResolver {
 *
 *     @Inject
 *     OntologyEdgeRepo edgeRepo;
 *
 *     @Override
 *     public Map<String, Object> resolve(ResolutionContext context) {
 *         Map<String, Object> props = new HashMap<>();
 *
 *         // Find associate linked to user
 *         Optional<String> associateId = edgeRepo.singleSrcIdByDst(
 *             context.getDataDomain(), "hasUser", context.getUserId());
 *
 *         if (associateId.isPresent()) {
 *             props.put("associateId", associateId.get());
 *
 *             // Get accessible locations
 *             Set<String> locationIds = edgeRepo.dstIdsBySrc(
 *                 context.getDataDomain(), "canSeeLocation", associateId.get());
 *             props.put("accessibleLocationIds", locationIds);
 *         }
 *
 *         return props;
 *     }
 * }
 * }</pre>
 *
 * <b>Using in Policy Rules:</b>
 * <pre>{@code
 * {
 *   "andFilterString": "_id:${accessibleLocationIds}"
 * }
 * }</pre>
 *
 * @see ResolutionContext
 * @see PrincipalContext#getCustomProperties()
 */
public interface PrincipalContextPropertiesResolver {

    /**
     * Order of execution. Lower values execute first.
     * Use to control dependency between resolvers when one resolver
     * depends on properties set by another.
     *
     * <p>Default: 1000</p>
     *
     * @return the priority value
     */
    default int priority() {
        return 1000;
    }

    /**
     * Resolve custom properties for the principal context.
     *
     * <p>This method is called once during authentication/authorization
     * setup in SecurityFilter. The returned properties are stored in
     * PrincipalContext and available for the duration of the request.</p>
     *
     * @param context Contains all available context for resolution:
     *                SecurityIdentity, credentials, realm, JWT claims, etc.
     *
     * @return Map of property name to value. Values can be:
     *         <ul>
     *         <li>String: Available as ${key} in rule filter strings</li>
     *         <li>Collection&lt;String&gt;: Available as ${key} for $in queries</li>
     *         <li>Object: Available in typed filter construction</li>
     *         </ul>
     *         Return empty map (not null) if no properties to contribute.
     */
    Map<String, Object> resolve(ResolutionContext context);
}
