package com.e2eq.framework.model.security;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import com.e2eq.ontology.annotations.OntologyClass;
import com.e2eq.ontology.annotations.OntologyProperty;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mongodb.client.model.CollationStrength;
import dev.morphia.annotations.*;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

import static dev.morphia.mapping.IndexType.DESC;


@EqualsAndHashCode(callSuper = true)
@Entity
@RegisterForReflection
@NoArgsConstructor
@SuperBuilder
@Data
@ToString(callSuper = true)
@OntologyClass(id = "UserProfile")
@Indexes({
   @Index(fields={@Field( value="userId", type=DESC)},
      options=@IndexOptions(unique=true, collation = @Collation(locale = "en", strength = CollationStrength.SECONDARY), name = "idx_userIdUnique")
   )
})
public  class UserProfile extends BaseModel {
    public enum Status {
        ACTIVE("ACTIVE"),
        DEACTIVATED("DEACTIVATED"),
        ARCHIVED("ARCHIVED");

        private final String value;

        Status(String value) {
            this.value = value;
        }

        public static Status fromValue(String v) {
          return valueOf(v);
        }

        public String value() {
            return value;
        }
    }

    @Schema(oneOf = { EntityReference.class, CredentialUserIdPassword.class })
    @Valid
    @JsonProperty(required = true)
    @NotNull (message = "credential reference must not be null")
    @NonNull
    @ReferenceTarget(target = com.e2eq.framework.model.security.CredentialUserIdPassword.class, collection="credentialUserIdPassword")
    @OntologyProperty(id = "hasCredential", ref = "Credential", materializeEdge = true)
    protected EntityReference credentialUserIdPasswordRef;

    /** Additional credentials (SERVICE_TOKEN, API_KEY, etc.) linked to this user profile. */
    @ReferenceTarget(target = CredentialUserIdPassword.class, collection = "credentialUserIdPassword")
    @Builder.Default
    protected List<EntityReference> additionalCredentialRefs = new ArrayList<>();

    @OntologyProperty(id = "hasUserId", materializeEdge = false) // userId is a string, handled by provider
    protected String userId; // used to look up the credentialUserIdPassword by userId if need be
   // in most cases the refName of the credential should match the userId but in the case where the credentialUserIdPasswordRef is null
   // or points to a different value this field can be used as a way to reconcile to the right credential
   // most of the time this will be the user's email address

    protected Status status = Status.ACTIVE;
    protected String fname;
    protected String lname;

    @JsonProperty(required = true)
    @NotNull (message = "email must not be null")
    @NonNull
    protected String email;
    protected String phoneNumber;
    @Builder.Default
    protected String defaultLanguage="English";
    @Builder.Default
    protected String defaultUnits = "Imperial";
    //protected CurrencyUnit defaultCurrency;
    @Builder.Default
    protected String defaultCurrency = "USD";
    //protected TimeZone defaultTimezone;
    @Builder.Default
    protected String defaultTimezone = "EST";

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "USER_PROFILE";
    }





}
