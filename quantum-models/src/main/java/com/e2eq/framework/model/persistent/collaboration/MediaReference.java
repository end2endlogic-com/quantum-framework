package com.e2eq.framework.model.persistent.collaboration;

import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Metadata and governed storage identity for media; object bytes and credentials never live here. */
@Data
@SuperBuilder
@NoArgsConstructor
@Entity(value = "media_references", useDiscriminator = false)
@Indexes({
    @Index(
        fields = {
            @Field("storageProvider"),
            @Field("storageContainer"),
            @Field("objectKey"),
            @Field("dataDomain.tenantId")
        },
        options = @IndexOptions(unique = true, name = "uidx_media_storage_object_tenant")
    ),
    @Index(
        fields = {@Field("sha256"), @Field("contentLength")},
        options = @IndexOptions(name = "idx_media_digest_length")
    )
})
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class MediaReference extends BaseModel {

    public enum Status {
        PENDING_UPLOAD,
        UPLOADED,
        SCANNING,
        AVAILABLE,
        QUARANTINED,
        FAILED,
        DELETED
    }

    public enum ScanStatus {
        NOT_SCANNED,
        PENDING,
        CLEAN,
        INFECTED,
        FAILED
    }

    public enum Classification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }

    @NotBlank
    private String storageProvider;

    @NotBlank
    private String storageContainer;

    @NotBlank
    private String objectKey;

    @NotBlank
    private String displayFileName;

    @NotBlank
    private String contentType;

    @PositiveOrZero
    private long contentLength;

    @Pattern(regexp = "^[a-fA-F0-9]{64}$", message = "sha256 must be a 64 character hexadecimal digest")
    private String sha256;

    private String purpose;

    @NotNull
    @Builder.Default
    private Status status = Status.PENDING_UPLOAD;

    @NotNull
    @Builder.Default
    private ScanStatus scanStatus = ScanStatus.NOT_SCANNED;

    @NotNull
    @Builder.Default
    private Classification classification = Classification.INTERNAL;

    @Valid
    @NotNull
    private ActorReference createdBy;

    @NotNull
    private Instant createdAt;

    private Instant lastUpdatedAt;
    private Instant retentionUntil;
    private Instant deletedAt;
    private Map<String, Object> metadata;

    @Override
    public String bmFunctionalArea() {
        return "COLLABORATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "MEDIA_REFERENCE";
    }
}
