package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The inviter's data partition, snapshotted when an {@link AccessInvite} is created.
 *
 * <p>It is the output of the framework's partition resolution ({@code DataDomainResolver}) for the
 * inviter's <em>verified</em> principal context, never values from the request body. The partition
 * is whatever that resolution yields: every {@link DataDomain} field (tenant, org, account, data
 * segment, owner, location, business transaction) plus any policy filter or facet filters the
 * matching {@link DataDomainPolicy} entry adds. The invite's acceptance policy decides which of it
 * the invitee receives; the recorded {@code dataDomain.ownerId} is the inviter's and is never
 * copied onto the invitee.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity(useDiscriminator = false)
@RegisterForReflection
public class InviteScope {

    /** The realm the partition was resolved in (the inviter's verified request realm). */
    protected String realmRefName;

    /** The resolved partition coordinate, exactly as the resolver returned it. */
    protected DataDomain dataDomain;

    /** Policy filter expression the matching DataDomainPolicy entry adds, if any. */
    protected String policyFilter;

    /** Structured facet filters the matching DataDomainPolicy entry adds, if any. */
    protected Map<String, Object> facetFilters;

    /** Which resolution produced this snapshot (for audit), e.g. {@code DataDomainResolver:SECURITY:ACCESS_INVITE}. */
    protected String resolvedBy;

    /** True when the partition carries constraints beyond its DataDomain coordinate. */
    public boolean hasPolicyConstraints() {
        return (policyFilter != null && !policyFilter.isBlank()) || (facetFilters != null && !facetFilters.isEmpty());
    }
}
