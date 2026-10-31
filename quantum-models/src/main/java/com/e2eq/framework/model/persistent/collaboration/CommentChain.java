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
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Container that attaches an independently stored comment tree to a model or external subject. */
@Data
@SuperBuilder
@NoArgsConstructor
@Entity(value = "comment_chains", useDiscriminator = false)
@Indexes({
    @Index(
        fields = {
            @Field("subject.quantumEntity.entityId"),
            @Field("dataDomain.tenantId")
        },
        options = @IndexOptions(name = "idx_comment_chain_quantum_subject")
    ),
    @Index(
        fields = {
            @Field("subject.externalEntity.sourceSystem"),
            @Field("subject.externalEntity.entityType"),
            @Field("subject.externalEntity.externalId"),
            @Field("dataDomain.tenantId")
        },
        options = @IndexOptions(
            name = "uidx_comment_chain_external_subject",
            unique = true,
            partialFilter = "{ 'subject.externalEntity.externalId': { '$type': 'string' } }"
        )
    )
})
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class CommentChain extends BaseModel {

    public enum Status {
        OPEN,
        LOCKED,
        ARCHIVED
    }

    @Valid
    @NotNull
    private CommentSubjectReference subject;

    @NotNull
    @Builder.Default
    private Status status = Status.OPEN;

    @Valid
    @NotNull
    private ActorReference createdBy;

    @NotNull
    private Instant createdAt;

    private Map<String, Object> context;

    @Override
    public String bmFunctionalArea() {
        return "COLLABORATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "COMMENT_CHAIN";
    }
}
