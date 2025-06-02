package com.e2eq.framework.model.persistent.security;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.SuperBuilder;


@EqualsAndHashCode(callSuper = true)
@Entity
@RegisterForReflection
@NoArgsConstructor
@SuperBuilder
public @Data class UserProfile extends BaseModel {
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
            return this.name();
        }
    }

    @Indexed(options= @IndexOptions(unique=true))
    @JsonProperty(required = true)
    @NotNull( message = "userId must not be null")
    @NonNull
    @Size(min = 5, max = 50, message = "userId must be between 5 and 50 characters long")
    protected String userId;

    @NotNull( message = "userName must not be null")
    @NonNull
    protected String username;

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
    protected DataDomainPolicy dataDomainPolicy;
    protected Status status = Status.ACTIVE;

   /**
    * Seperated out here vs. using the data domain to define how the
    * user that is associated with this profile gets mapped from a PrincipalContext
    * which is different perhaps than the data domain settings that the user profile exists in.
    * Also, this is separate from a credential because we may not be the source of truth for
    * authentication
    * */

   //TODO replace with domainContext ? or remove
    protected String orgRefName;
    protected String accountNumber;
    protected String tenantId;
    protected String defaultRealm;



    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "USER_PROFILE";
    }



}
