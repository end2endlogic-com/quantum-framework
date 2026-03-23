package com.e2eq.framework.model.persistent.base;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@RegisterForReflection
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ShareAccessLog extends BaseModel {
    private String linkId;
    private String publicId;

    private Instant ts;
    private String requesterIdentity;
    private String ip;
    private String userAgent;
    private String outcome;
    private String message;

    @Override
    public String bmFunctionalArea() {
        return "INTEGRATION";
    }

    @Override
    public String bmFunctionalDomain() {
        return "SHARE_LINK";
    }
}
