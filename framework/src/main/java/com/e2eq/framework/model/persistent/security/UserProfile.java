package com.e2eq.framework.model.persistent.security;


import com.e2eq.framework.model.persistent.base.BaseModel;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;



@EqualsAndHashCode(callSuper = true)
@Entity
@RegisterForReflection
@NoArgsConstructor
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
            Status s =  valueOf(v);
            if (s == null) {
                throw new IllegalArgumentException("Could not resolve string:" + v + " to a status value");
            }
            return s;
        }

        public String value() {
            return this.name();
        }
    }

    @Indexed(options= @IndexOptions(unique=true))
    @JsonProperty(required = true)
    @NotNull( message = "userId must not be null")
    @NonNull
    protected String userId;

    @NotNull( message = "userName must not be null")
    @NonNull
    protected String userName;

    protected String fname;
    protected String lname;

    @JsonProperty(required = true)
    @NotNull (message = "email must not be null")
    @NonNull
    protected String email;
    protected String phoneNumber;
    protected String defaultLanguage="English";
    protected String defaultUnits = "Imperial";
    //protected CurrencyUnit defaultCurrency;
    protected String defaultCurrency = "USD";
    //protected TimeZone defaultTimezone;
    protected String defaultTimezone = "EST";
    protected DataDomainPolicy dataDomainPolicy;
    protected Status status = Status.ACTIVE;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "USER_PROFILE";
    }



}
