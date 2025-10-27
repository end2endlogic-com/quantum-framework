package com.e2eq.framework.model.security;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.e2eq.framework.model.persistent.base.EntityReference;
import com.e2eq.framework.model.persistent.base.ReferenceTarget;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;


@EqualsAndHashCode(callSuper = true)
@Entity
@RegisterForReflection
@NoArgsConstructor
@SuperBuilder
@Data
@ToString(callSuper = true)
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

    @Valid
    @JsonProperty(required = true)
    @NotNull (message = "credential reference must not be null")
    @NonNull
    @ReferenceTarget(target = com.e2eq.framework.model.security.CredentialUserIdPassword.class, collection="credentialUserIdPassword")
    protected EntityReference credentialUserIdPasswordRef;
    protected String userId;

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
