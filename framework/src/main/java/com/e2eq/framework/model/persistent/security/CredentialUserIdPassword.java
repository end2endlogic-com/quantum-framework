package com.e2eq.framework.model.persistent.security;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.BaseModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import static dev.morphia.mapping.IndexType.DESC;

@Entity()
@Data
@EqualsAndHashCode(callSuper = true )
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
public class CredentialUserIdPassword extends BaseModel {

    @Indexed(options= @IndexOptions(unique=true))
    @NotNull ( message = "userId must be provided for userIdPassword credential")
    @NonNull
    protected String userId;

    @NonNull
    @NotNull ( message = "domain context is required")
    @Valid
    protected DomainContext domainContext;

    @NotNull( message = "passwordHash must be non null")
    @NonNull
    protected String passwordHash;


    @NotNull(message = "roles must be non null and not empty")
    @NonNull
    @NotEmpty protected String[] roles;

    protected String issuer;

    @Builder.Default
    @NotNull
    @NonNull
    protected String hashingAlgorithm="BCrypt.default";
    @NotNull @NonNull protected Date lastUpdate;

    @Builder.Default
    @NotNull @NonNull protected Map<String, String> area2RealmOverrides = new HashMap<>();



    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "CREDENTIAL_USERID_PASSWORD";
    }


}
