package com.e2eq.framework.model.persistent.base;

import java.util.Locale;

/**
 * How far a permission rule's relationship lookups ({@code hasEdge}, {@code hasIncomingEdge}) reach.
 *
 * <p>Configured by {@code quantum.ontology.rule-edge-scope}:</p>
 * <ul>
 *   <li>{@code DATA_DOMAIN} (the default): edges in the caller's whole DataDomain -- organisation, account, tenant
 *       and data segment.</li>
 *   <li>{@code ACCOUNT}: edges in any tenant of the caller's organisation, account and data segment -- never another
 *       organisation or account. For an application whose tenants share one realm and whose relationships cross
 *       them (a party reading records another tenant holds about it). The query a rule constrains keeps every other
 *       constraint the rules put on it.</li>
 * </ul>
 *
 * <p>Only rule lookups are widened; the DataDomain a widened lookup uses carries {@link #ANY_TENANT}, which the edge
 * store accepts for reads only.</p>
 */
public enum RuleEdgeScope {
    DATA_DOMAIN, ACCOUNT;

    /** The tenant of a widened rule lookup: any tenant of the organisation, account and data segment. */
    public static final String ANY_TENANT = "*";

    public static final String CONFIG_KEY = "quantum.ontology.rule-edge-scope";

    /** The configured scope; the default when unset or unreadable (outside a configured runtime). */
    public static RuleEdgeScope configured() {
        try {
            return org.eclipse.microprofile.config.ConfigProvider.getConfig().getOptionalValue(CONFIG_KEY, String.class)
                    .map(v -> RuleEdgeScope.valueOf(v.trim().toUpperCase(Locale.ROOT))).orElse(DATA_DOMAIN);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(CONFIG_KEY + " must be DATA_DOMAIN or ACCOUNT", e);
        } catch (RuntimeException e) {
            return DATA_DOMAIN;
        }
    }

    /** The DataDomain a rule's relationship lookup uses under this scope, for a caller's DataDomain. */
    public DataDomain lookupDomain(DataDomain caller) {
        if (this != ACCOUNT || caller == null) {
            return caller;
        }
        DataDomain any = new DataDomain();
        any.setOrgRefName(caller.getOrgRefName());
        any.setAccountNum(caller.getAccountNum());
        any.setTenantId(ANY_TENANT);
        any.setDataSegment(caller.getDataSegment());
        if (caller.getOwnerId() != null) {
            any.setOwnerId(caller.getOwnerId());
        }
        return any;
    }

    /** The DataDomain a rule's relationship lookup uses, under the configured scope. */
    public static DataDomain forRuleLookup(DataDomain caller) {
        return configured().lookupDomain(caller);
    }
}
