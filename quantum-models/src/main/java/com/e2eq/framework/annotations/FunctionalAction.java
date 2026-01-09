package com.e2eq.framework.annotations;

import java.lang.annotation.*;

/**
 * Method-level annotation to declare the functional action (e.g., CREATE, VIEW, UPDATE, DELETE).
 * 
 * <p>This annotation is used by the security framework to determine the action being performed
 * and apply appropriate permission rules.</p>
 * 
 * <p><strong>bypassDataScoping attribute:</strong>
 * When {@code bypassDataScoping = true}, the endpoint is marked as a system-level operation
 * that does not operate on data entities with dataDomain scoping. This means:</p>
 * <ul>
 *   <li>Permission checks still verify ALLOW/DENY based on rules</li>
 *   <li>SCOPED constraints (like {@code dataDomain.tenantId:${pTenantId}}) are ignored</li>
 *   <li>The operation proceeds without requiring data-level filter validation</li>
 * </ul>
 * 
 * <p><strong>Use cases for bypassDataScoping:</strong></p>
 * <ul>
 *   <li>Database migration operations (index creation, schema changes)</li>
 *   <li>System initialization and setup endpoints</li>
 *   <li>Administrative operations that don't query/modify tenant-scoped data</li>
 * </ul>
 * 
 * <p><strong>Security considerations:</strong> Only use {@code bypassDataScoping = true} for
 * endpoints that genuinely do not operate on tenant-scoped data. Misuse could expose
 * cross-tenant data access vulnerabilities.</p>
 * 
 * @see FunctionalMapping
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface FunctionalAction {
    /**
     * The action name (e.g., "CREATE", "VIEW", "UPDATE", "DELETE", "APPLY_ALL_INDEXES").
     * @return the action name
     */
    String value();
    
    /**
     * When true, indicates this endpoint performs a system-level operation that does not
     * operate on data entities with dataDomain scoping. Permission checks still apply,
     * but SCOPED constraints (data-level filters) are bypassed.
     * 
     * <p>Default is {@code false} for backward compatibility and security.</p>
     * 
     * @return true to bypass data scoping constraints, false otherwise
     */
    boolean bypassDataScoping() default false;
}
