package com.e2eq.framework.model.persistent.security;


import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.e2eq.framework.util.EncryptionUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import com.e2eq.framework.model.persistent.base.BaseModel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;


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
    @Size(min =3, max=50, message="userId length must be less than or equal to 50 and greater than or equal to 3 characters")
    protected String userId;

    @Indexed(options= @IndexOptions(unique=true))
    @NonNull
    @NotNull(message = "username must be non null")
    @NotEmpty
    protected String username;


    @NonNull
    @NotNull ( message = "domain context is required")
    @Valid
    protected DomainContext domainContext;

    // ignored for rest api's for security reasons we don't expose this field to the rest api.
    @NotNull( message = "passwordHash must be non null")
    @NonNull
    @JsonIgnore
    protected String passwordHash;


    @NotNull(message = "roles must be non null and not empty")
    @NonNull
    @NotEmpty protected String[] roles;


    protected String issuer;

    @Builder.Default
    @NotNull
    @NonNull
    @JsonIgnore
    protected String hashingAlgorithm= EncryptionUtils.hashAlgorithm();

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
