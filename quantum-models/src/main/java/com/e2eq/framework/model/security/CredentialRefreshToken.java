package com.e2eq.framework.model.security;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import lombok.*;
import lombok.experimental.SuperBuilder;


import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@Entity
@NoArgsConstructor
@SuperBuilder
public class CredentialRefreshToken extends BaseModel {
    @NonNull String userId;
    @NonNull String refreshToken;
    @NonNull String accessToken;
    @NonNull Date creationDate;
    @NonNull Date lastRefreshDate;
    @NonNull Date expirationDate;
    @Builder.Default
    int usedCount=0;
    @Builder.Default
    boolean revoked=false;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "REFRESH_TOKENS";
    }
}
