package com.e2eq.framework.model.persistent.collaboration;

import com.e2eq.framework.model.persistent.base.ActorReference;
import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Field;
import dev.morphia.annotations.Index;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexes;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.bson.types.ObjectId;

/** One append-oriented node in a comment chain. Descendants are queried, never embedded. */
@Data
@SuperBuilder
@NoArgsConstructor
@Entity(value = "comments", useDiscriminator = false)
@Indexes({
    @Index(
        fields = {
            @Field("chainId"),
            @Field("parentCommentId"),
            @Field("createdAt"),
            @Field("_id")
        },
        options = @IndexOptions(name = "idx_comment_chain_parent_created")
    ),
    @Index(
        fields = {@Field("chainId"), @Field("createdAt"), @Field("_id")},
        options = @IndexOptions(name = "idx_comment_chain_created")
    ),
    @Index(
        fields = {@Field("chainId"), @Field("requestId")},
        options = @IndexOptions(
            unique = true,
            partialFilter = "{ 'requestId': { '$type': 'string' } }",
            name = "uidx_comment_chain_request"
        )
    )
})
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Comment extends BaseModel {

    public static final int MAX_DEPTH = 32;

    public enum BodyFormat {
        PLAIN_TEXT,
        MARKDOWN
    }

    public enum State {
        ACTIVE,
        REDACTED,
        DELETED
    }

    @NotNull
    private ObjectId chainId;

    private ObjectId parentCommentId;

    @Min(0)
    @Max(MAX_DEPTH)
    private int depth;

    @NotBlank
    @Size(max = 100_000)
    private String body;

    @NotNull
    @Builder.Default
    private BodyFormat bodyFormat = BodyFormat.MARKDOWN;

    @Valid
    @NotNull
    private ActorReference author;

    @NotNull
    private Instant createdAt;

    private Instant editedAt;

    @NotNull
    @Builder.Default
    private State state = State.ACTIVE;

    @Valid
    @Builder.Default
    private List<EntityReference> mediaReferences = new ArrayList<>();

    private String requestId;
    private boolean systemGenerated;
    private Map<String, Object> metadata;

    @Override
    public String bmFunctionalArea() {
        return "COLLABORATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "COMMENT";
    }
}
