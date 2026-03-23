package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@Entity
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ShareLink extends BaseModel {

    public enum Status {
        ACTIVE,
        REVOKED
    }

    private String publicId;

    private String bucket;
    private String region;
    private String objectKey;

    private Instant expiresAt;
    private Long maxUses;
    private Boolean allowAnonymous;

    private String createdBy;
    private String contentDisposition;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @Builder.Default
    private Long usedCount = 0L;

    private Instant lastAccessedAt;

    private Instant createdAt;
    private Instant lastUpdatedAt;

    @Override
    public String bmFunctionalArea() {
        return "INTEGRATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "SHARE_LINK";
    }
}
