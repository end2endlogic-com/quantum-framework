package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Entity(value = "access_invites", useDiscriminator = false)
@Indexes({
    @Index(fields = {
        @Field("tokenHash")
    }, options = @IndexOptions(unique = true, name = "uidx_access_invite_token_hash")),
    @Index(fields = {
        @Field("email"),
        @Field("status")
    }, options = @IndexOptions(name = "idx_access_invite_email_status")),
    @Index(fields = {
        @Field("targetUserId"),
        @Field("status")
    }, options = @IndexOptions(name = "idx_access_invite_target_user_status"))
})
@Data
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
public class AccessInvite extends BaseModel {

    public enum Status {
        PENDING,
        ACCEPTED,
        REVOKED,
        EXPIRED
    }

    protected String email;

    protected String targetUserId;

    @NotNull
    protected String invitedByUserId;

    @NotNull
    protected String tokenHash;

    protected String tokenHint;

    /**
     * Hash of a replacement token staged by a resend while its notification is being delivered.
     * It never authenticates an acceptance; it becomes {@link #tokenHash} only after delivery
     * succeeds, so a failed delivery leaves the previously delivered token working.
     */
    protected String pendingTokenHash;

    /** Hint of {@link #pendingTokenHash}; promoted with it. */
    protected String pendingTokenHint;

    /** Expiry that applies once {@link #pendingTokenHash} is promoted. */
    protected Date pendingExpiresAt;

    /**
     * When {@link #pendingTokenHash} was staged. A staged token is a short lease: a second resend
     * is refused while it is held, and may take it over once it is older than the lease (for
     * example after a resend crashed mid-delivery).
     */
    protected Date pendingStagedAt;

    @Valid
    @Builder.Default
    protected List<EntityReference> scopeRefs = new ArrayList<>();

    @Builder.Default
    protected List<String> grantedRoles = new ArrayList<>();

    @Builder.Default
    protected List<String> allowedFunctionalAreas = new ArrayList<>();

    @Builder.Default
    protected List<String> allowedFunctionalDomains = new ArrayList<>();

    @Builder.Default
    protected List<String> allowedActions = new ArrayList<>();

    /**
     * Applications granted in this invite's realm; written to the invitee's
     * {@code UserRealmRole.authorizedApplications} on acceptance. Null or empty: not named.
     */
    protected List<String> authorizedApplications;

    /**
     * True only when the framework system principal created the invite. Such invites are exempt
     * from the acceptance-time re-check of the inviter; the inviter's userId alone never is.
     */
    protected boolean issuedBySystemPrincipal;

    /**
     * Id of the {@code InviteAcceptancePolicy} that governs this invite, fixed at creation from the
     * realm's configuration. Null only on invites created before acceptance policies existed; those
     * use the realm's configured policy.
     */
    protected String acceptancePolicy;

    /**
     * The inviter's resolved data partition when the invite was created, from its verified
     * principal context (never the request body). The acceptance policy derives the invitee's
     * placement from it.
     */
    @Valid
    protected InviteScope scope;

    protected String inviteMessage;

    @NotNull
    protected Date expiresAt;

    protected Date acceptedAt;

    protected String acceptedUserId;

    @NotNull
    @Builder.Default
    protected Status status = Status.PENDING;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "ACCESS_INVITE";
    }
}
