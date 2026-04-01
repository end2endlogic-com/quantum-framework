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
